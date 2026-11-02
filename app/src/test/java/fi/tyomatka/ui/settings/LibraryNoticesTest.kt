package fi.tyomatka.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Oikea rakennuksen kokoama luettelo: uusi BSD- tai MIT-riippuvuus kaatuu tähän, kunnes sen alkuperäinen merkintä on lisätty. */
class LibraryNoticesTest {
    private val generatedText = File("build/generated/aboutLibraries/debug/res/raw/aboutlibraries.json").readText()
    private val notices = parseNotices(File("src/main/res/raw/library_notices.json").readText())
    private val generated = Json.parseToJsonElement(generatedText) as JsonObject
    private val libraries = generated.getValue("libraries") as JsonArray

    private fun JsonObject.id() = (getValue("uniqueId") as JsonPrimitive).content

    private fun JsonObject.licenses() = (getValue("licenses") as JsonArray).map { (it as JsonPrimitive).content }

    @Test
    fun `jokaisella BSD- ja MIT-kirjastolla on alkuperäinen tekijänoikeusmerkintä`() {
        val needing = libraries.map { it as JsonObject }
            .filter { library -> library.licenses().any { it.startsWith("BSD") || it == "MIT" } }
            .map { it.id() }

        assertTrue(needing.isNotEmpty())
        assertEquals(emptyList(), needing.filterNot { notices[it].orEmpty().contains("Copyright") })
    }

    @Test
    fun `merkinnät koskevat käytössä olevia kirjastoja`() {
        val ids = libraries.map { (it as JsonObject).id() }.toSet()

        assertEquals(emptySet(), notices.keys - ids)
    }

    @Test
    fun `yhdenkään kirjaston teksti ei ole täyttämätön mallipohja`() {
        val shown = parseLibraries(generatedText, notices)
        val onlyTemplates = libraries.map { it as JsonObject }
            .filter { library -> library.licenses().all { it.startsWith("BSD") || it == "MIT" } }
            .map { it.id() }
            .toSet()

        assertTrue(onlyTemplates.isNotEmpty())
        assertEquals(emptyList(), shown.filter { it.licenseText == null }.map { it.id })
        assertEquals(emptyList(), shown.filter { it.id in onlyTemplates && it.licenseText != notices[it.id] }.map { it.id })
    }

    /** POM ilmoittaa Apachen, mutta lähdekoodin lisenssi on MIT, joten luettelosta tätä ei voi päätellä. */
    @Test
    fun `maplibre-javan kirjastoilla on alkuperäinen MIT-merkintä`() {
        listOf("org.maplibre.gl:android-sdk-geojson", "org.maplibre.gl:android-sdk-turf").forEach {
            assertTrue("Copyright (c) 2018 Mapbox" in notices[it].orEmpty(), it)
        }
    }

    @Test
    fun `MapLibren lisenssi sisältää alkuperäiset tekijät`() {
        val text = notices.getValue("org.maplibre.gl:android-sdk")

        listOf("MapLibre contributors", "MapTiler.com", "Mapbox").forEach { assertTrue(it in text, it) }
    }
}
