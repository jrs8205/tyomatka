package fi.tyomatka.ui.settings

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Kirjasto ja sen lisenssi rakennuksessa kootusta luettelosta. */
internal data class LibraryInfo(
    val id: String,
    val name: String,
    val version: String?,
    val licenseNames: List<String>,
    val licenseText: String?,
    val url: String?,
)

/**
 * Luetaan rakennuksen tuottama luettelo. Lisenssisivu ei saa kaataa sovellusta, joten rikkinäinen luettelo on tyhjä.
 *
 * Luettelon BSD- ja MIT-tekstit ovat täyttämättömiä mallipohjia, jotka eivät kerro tekijöitä, joten niitä ei näytetä.
 * Alkuperäiset merkinnät tulevat [notices]-tiedostosta (`res/raw/library_notices.json`); `LibraryNoticesTest` kaatuu,
 * jos uudelta riippuvuudelta puuttuu merkintä.
 */
internal fun parseLibraries(json: String, notices: Map<String, String> = emptyMap()): List<LibraryInfo> {
    val root = parseJson(json) as? JsonObject ?: return emptyList()
    val licenses = root["licenses"] as? JsonObject
    val libraries = root["libraries"] as? JsonArray ?: return emptyList()
    return libraries.filterIsInstance<JsonObject>().map { library ->
        val id = library.text("uniqueId").orEmpty()
        val keys = (library["licenses"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val known = keys.mapNotNull { licenses?.get(it) as? JsonObject }
        val texts = listOfNotNull(notices[id]) + known.mapNotNull { license -> license.text("content")?.takeUnless(::isTemplate) }
        LibraryInfo(
            id = id,
            name = library.text("name") ?: id,
            version = library.text("artifactVersion"),
            licenseNames = keys.map { key -> (licenses?.get(key) as? JsonObject)?.text("name") ?: key },
            licenseText = texts.joinToString("\n\n").ifEmpty { null },
            url = known.firstNotNullOfOrNull { it.text("url") } ?: library.text("website"),
        )
    }.sortedBy { it.name.lowercase() }
}

/** Kirjastoryhmien alkuperäiset lisenssi- ja NOTICE-tekstit kirjastoittain. Rikkinäinen tiedosto on tyhjä. */
internal fun parseNotices(json: String): Map<String, String> =
    (parseJson(json) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { group ->
        val text = group.text("text") ?: return@flatMap emptyList()
        (group["libraries"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { id -> id.isString }?.content }.map { it to text }
    }.toMap()

private fun parseJson(json: String) = try {
    Json.parseToJsonElement(json)
} catch (e: SerializationException) {
    null
}

private fun isTemplate(text: String) = TEMPLATE_MARKS.any { it in text }

/** BSD: `Copyright (c) <<var;name=copyright;...>>`. MIT: `Copyright (c) <year> <copyright holders>`. */
private val TEMPLATE_MARKS = listOf("<<var", "<year>")

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
