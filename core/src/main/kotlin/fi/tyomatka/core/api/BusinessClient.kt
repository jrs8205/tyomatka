package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** Overture Places -aineiston yritys oman Workerin nimihausta. */
data class Business(
    val id: String,
    val name: String,
    val street: String?,
    val postcode: String?,
    val locality: String?,
    val location: GeoPoint,
) {
    /** "Teollisuustie 3, Askola"; ilman katua pelkkä paikkakunta. Overturen isoin kirjoitetut sanat ("ASKOLA") siistitään. */
    val addressLine: String
        get() = listOfNotNull(street, locality).filter { it.isNotBlank() }.joinToString(", ") { capitalizeUpperWords(it) }
}

fun interface BusinessSource {
    suspend fun search(query: String, near: GeoPoint?): List<Business>
}

/**
 * Oman Workerin yrityshaku (`GET /v1/places`). Sijainti kulkee kahdella desimaalilla (noin kilometrin tarkkuus), jotta
 * reunavälimuisti osuu eikä tarkka sijainti lähde laitteelta.
 */
class BusinessClient(private val client: OkHttpClient, baseUrl: String) : BusinessSource {
    private val base = baseUrl.trimEnd('/').toHttpUrl()

    override suspend fun search(query: String, near: GeoPoint?): List<Business> {
        val builder = base.newBuilder()
            .addPathSegments("v1/places")
            .addQueryParameter("q", query)
        if (near != null) {
            // Pilkku sellaisinaan: sama osoite kuin selaimella ja reunavälimuistissa.
            builder.addEncodedQueryParameter("near", String.format(Locale.ROOT, "%.2f,%.2f", near.lat, near.lng))
        }
        val request = Request.Builder().url(builder.build()).get().header("Accept", "application/json").build()
        val raw = client.executeRaw(request)
        if (raw.code == 429 || raw.code == 503) throw PoiUnavailableException(raw.code)
        if (raw.code !in 200..299) throw ApiException("HTTP ${raw.code}: ${raw.body.take(500)}", httpCode = raw.code)
        val parsed = try {
            json.decodeFromString<Body>(raw.body)
        } catch (e: SerializationException) {
            throw ApiException("Virheellinen yrityshaun vastaus: ${e.message}")
        }
        return parsed.items.map { item ->
            Business(
                id = item.id,
                name = item.name,
                street = item.street?.takeIf { it.isNotBlank() },
                postcode = item.postcode?.takeIf { it.isNotBlank() },
                locality = item.locality?.takeIf { it.isNotBlank() },
                location = GeoPoint(item.lat, item.lon),
            )
        }
    }

    @Serializable
    private data class Body(val items: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val id: String,
        val name: String,
        val street: String? = null,
        val postcode: String? = null,
        val locality: String? = null,
        val lat: Double,
        val lon: Double,
    )

    companion object {
        const val PREFIX = "overture:"
        const val SOURCE = "© Overture Maps Foundation"

        private val json = Json { ignoreUnknownKeys = true }

        fun isBusiness(placeId: String): Boolean = placeId.startsWith(PREFIX)
    }
}

private val letterRun = Regex("\\p{L}+")

/** Kokonaan isoin kirjoitettu sana ("MONNINKYLÄ") muotoon "Monninkylä"; yksittäiset kirjaimet ("3 B") ja sekamuodot ennallaan. */
internal fun capitalizeUpperWords(text: String): String = letterRun.replace(text) { match ->
    val word = match.value
    val isUpper = word == word.uppercase(Locale.ROOT) && word != word.lowercase(Locale.ROOT)
    if (isUpper) word.first() + word.substring(1).lowercase(Locale.ROOT) else word
}
