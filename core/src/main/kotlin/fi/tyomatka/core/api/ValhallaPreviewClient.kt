package fi.tyomatka.core.api

import fi.tyomatka.core.geo.Polyline
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteChoice
import fi.tyomatka.core.nav.ValhallaRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToLong

/** Reitti kartalle: viiva, matka, arvioitu aika ja aika osuuksittain (yksi osuus stoppia kohti). */
data class RoutePreview(
    val points: List<GeoPoint>,
    val distanceM: Long,
    val durationSec: Long,
    val legDurationsSec: List<Long> = emptyList(),
)

/**
 * Koko päivän reitti kartan vertailuun samalta palvelimelta ja samoilla valinnoilla kuin ajon opastus. Ajo-ohjeita ei
 * pyydetä (`directions_type=none`). Kuristin on sama kuin ajon reittihauilla (FOSSGIS: enintään yksi pyyntö sekunnissa).
 */
class ValhallaPreviewClient(
    private val client: OkHttpClient,
    baseUrl: String = Defaults.VALHALLA_URL,
    private val limiter: RateLimiter,
    private val costing: String = "auto",
) {
    private val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("route").build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun preview(origin: GeoPoint, stops: List<GeoPoint>, choice: RouteChoice): RoutePreview {
        require(stops.isNotEmpty()) { "Reitti vaatii vähintään yhden stopin" }
        // Seuraava osa alkaa edellisen lopusta, joten koko päivä on yhtenäinen viiva.
        val parts = (listOf(origin) + stops).windowed(MAX_LOCATIONS, MAX_LOCATIONS - 1, partialWindows = true)
            .filter { it.size >= 2 }
            .map { part(it, choice) }
        val points = ArrayList<GeoPoint>()
        parts.forEach { part ->
            points += if (points.lastOrNull() == part.points.firstOrNull()) part.points.drop(1) else part.points
        }
        return RoutePreview(
            points = points,
            distanceM = parts.sumOf { it.distanceM },
            durationSec = parts.sumOf { it.durationSec },
            legDurationsSec = parts.flatMap { it.legDurationsSec },
        )
    }

    private suspend fun part(locations: List<GeoPoint>, choice: RouteChoice): RoutePreview {
        val options = json.parseToJsonElement(ValhallaRequest.optionsJson(LANGUAGE, choice)).jsonObject
        val body = buildJsonObject {
            putJsonArray("locations") {
                locations.forEach { point ->
                    add(buildJsonObject { put("lat", point.lat); put("lon", point.lng) })
                }
            }
            put("costing", costing)
            put("units", "kilometers")
            put("directions_type", "none")
            options[COSTING_OPTIONS]?.let { put(COSTING_OPTIONS, it) }
        }
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)).build()
        limiter.acquire(url.host)
        val raw = client.executeRaw(request)
        val response = runCatching { json.decodeFromString<Response>(raw.body) }.getOrNull()
        if (raw.code !in 200..299) {
            val detail = "Reitin haku epäonnistui: HTTP ${raw.code} ${response?.error_code ?: ""}".trim()
            if (response?.error_code in NO_ROUTE_ERRORS) throw RouteNotFoundException(detail, raw.code)
            throw ApiException(detail, httpCode = raw.code)
        }
        val trip = response?.trip
        val summary = trip?.summary ?: throw ApiException("Reittivastauksesta puuttuu yhteenveto", raw.code)
        val points = ArrayList<GeoPoint>()
        trip.legs.forEach { leg ->
            val decoded = Polyline.decode(leg.shape, factor = SHAPE_FACTOR)
            // Osuudet jatkuvat samasta pisteestä: yhteinen piste kerran.
            points += if (points.lastOrNull() == decoded.firstOrNull()) decoded.drop(1) else decoded
        }
        if (points.size < 2) throw ApiException("Reittivastauksesta puuttuu viiva", raw.code)
        return RoutePreview(
            points = points,
            distanceM = (summary.length * 1000).roundToLong(),
            durationSec = summary.time.roundToLong(),
            legDurationsSec = trip.legs.map { it.summary?.time?.roundToLong() ?: 0L },
        )
    }

    @Serializable
    private data class Response(val trip: Trip? = null, val error_code: Int? = null)

    @Serializable
    private data class Trip(val summary: Summary? = null, val legs: List<Leg> = emptyList())

    @Serializable
    private data class Summary(val length: Double = 0.0, val time: Double = 0.0)

    @Serializable
    private data class Leg(val shape: String = "", val summary: Summary? = null)

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        const val COSTING_OPTIONS = "costing_options"

        /** Valinnat eivät riipu kielestä, koska ajo-ohjeita ei pyydetä. */
        const val LANGUAGE = "en-US"

        /** Valhallan oma muoto käyttää kuutta desimaalia. */
        const val SHAPE_FACTOR = 1e6

        /** FOSSGIS-palvelimen raja pisteille yhdessä pyynnössä (mitattu palvelimelta). */
        const val MAX_LOCATIONS = 10

        /** Ei reittiä pisteiden välillä tai piste ei osu tiehen. */
        val NO_ROUTE_ERRORS = setOf(170, 171, 442, 443)
    }
}
