package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiGrid
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/** Palvelin on pysäytetty tai kiintiö täynnä (429 tai 503): kohteet eivät ole juuri nyt saatavilla. */
class PoiUnavailableException(httpCode: Int) : ApiException("Kohteet eivät ole saatavilla ($httpCode)", httpCode)

/** [raw] on vastauksen runko sellaisenaan laitteen välimuistia varten; [truncated] ikkunasta jäi kohteita pois (yli 300). */
data class PoiResponse(val generatedAt: String, val source: String, val items: List<PoiItem>, val raw: String = "", val truncated: Boolean = false)

/**
 * Oman Workerin karttakohdehaku: yksi pyöristetty ikkuna ja yksi luokka pyyntöä kohti, jotta reunavälimuisti ja laitteen
 * välimuisti osuvat. Pyynnössä ei kulje mitään käyttäjästä.
 */
class PoiClient(private val client: OkHttpClient, baseUrl: String) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun fetch(box: PoiBox, category: PoiCategory): PoiResponse {
        val url = base.newBuilder()
            .addPathSegments("v1/poi")
            // Pilkut sellaisinaan: sama osoite kuin selaimella ja reunavälimuistissa.
            .addEncodedQueryParameter("bbox", PoiGrid.bbox(box))
            .addQueryParameter("cats", category.apiName)
            .build()
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        return parse(send(request))
    }

    /**
     * Yhteishaku (`POST /v1/poi/batch`): monta palaa yhdellä pyynnöllä, joka lasketaan palvelimen rajaan yhtenä hakuna.
     * Vastaukset tulevat palojen järjestyksessä, ja jokaisen [PoiResponse.raw] on osan oma runko, joten välimuistin
     * rivi jäsentyy [parse]lla kuten erillisen haun vastaus.
     */
    suspend fun fetchBatch(parts: List<PoiPart>): List<PoiResponse> {
        require(parts.size in 1..MAX_BATCH) { "yhteishaussa 1–$MAX_BATCH palaa, nyt ${parts.size}" }
        val body = buildJsonObject {
            putJsonArray("parts") {
                parts.forEach { part ->
                    addJsonObject {
                        put("bbox", PoiGrid.bbox(part.box))
                        put("cats", part.category.apiName)
                    }
                }
            }
        }.toString()
        val url = base.newBuilder().addPathSegments("v1/poi/batch").build()
        val request = Request.Builder().url(url)
            .post(body.toRequestBody(JSON_TYPE))
            .header("Accept", "application/json")
            .build()
        val raw = send(request)
        // Rikkinäinen vastaus (esim. välityspalvelimen virhesivu) on rajapintavirhe, jotta kutsuja käsittelee sen kuten verkkovirheen.
        val responses = try {
            json.decodeFromString<BatchBody>(raw).parts.map { parse(it.toString()) }
        } catch (e: SerializationException) {
            throw ApiException("Virheellinen yhteishaun vastaus: ${e.message?.take(200)}")
        }
        if (responses.size != parts.size) throw ApiException("Yhteishaun osia ${responses.size}, paloja ${parts.size}")
        return responses
    }

    /** 429 ja 503: palvelu ei ole juuri nyt saatavilla; muu virhekoodi on tavallinen rajapintavirhe. */
    private suspend fun send(request: Request): String {
        val raw = client.executeRaw(request)
        if (raw.code == 429 || raw.code == 503) throw PoiUnavailableException(raw.code)
        if (raw.code !in 200..299) throw ApiException("HTTP ${raw.code}: ${raw.body.take(500)}", httpCode = raw.code)
        return raw.body
    }

    @Serializable
    private data class BatchBody(val parts: List<JsonElement> = emptyList())

    @Serializable
    private data class Body(
        val generatedAt: String = "",
        val source: String = "",
        val items: List<Item> = emptyList(),
        val truncated: Boolean = false,
    )

    @Serializable
    private data class Item(
        val id: String,
        val cat: String,
        val lat: Double,
        val lon: Double,
        val name: String? = null,
        val brand: String? = null,
        val addr: String? = null,
        val hgv: Boolean = false,
        val tags: Map<String, String> = emptyMap(),
    )

    companion object {
        /** Yhteishaun palojen enimmäismäärä (palvelimen `MAX_BATCH_PARTS`). */
        const val MAX_BATCH = 50

        private val json = Json { ignoreUnknownKeys = true }
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()


        /** Tagien avaimet pieniksi ja tuntemattomat luokat pois; sama jäsennys välimuistin riveille. */
        fun parse(body: String): PoiResponse {
            val parsed = json.decodeFromString<Body>(body)
            val items = parsed.items.mapNotNull { item ->
                val category = PoiCategory.of(item.cat) ?: return@mapNotNull null
                PoiItem(
                    id = item.id,
                    category = category,
                    location = GeoPoint(item.lat, item.lon),
                    name = item.name?.takeIf { it.isNotBlank() },
                    brand = item.brand?.takeIf { it.isNotBlank() },
                    address = item.addr?.takeIf { it.isNotBlank() },
                    hgv = item.hgv,
                    tags = item.tags.mapKeys { (key, _) -> key.lowercase(Locale.ROOT) },
                )
            }
            return PoiResponse(parsed.generatedAt, parsed.source, items, raw = body, truncated = parsed.truncated)
        }
    }
}
