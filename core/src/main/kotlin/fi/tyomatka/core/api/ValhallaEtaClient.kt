package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToLong

data class RouteEta(val distanceM: Long, val durationSec: Long)

/**
 * Kevyt Valhalla-reittipyyntö kohdekortin matkalle ja ajalle: sama palvelin ja profiili kuin ajossa, mutta ilman ohjeita
 * (`directions_type=none`). Kuristin on sama kuin ajon reittihauilla (FOSSGIS: enintään yksi pyyntö sekunnissa).
 */
class ValhallaEtaClient(
    private val client: OkHttpClient,
    baseUrl: String = Defaults.VALHALLA_URL,
    private val limiter: RateLimiter,
    private val costing: String = "auto",
) {
    private val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("route").build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun eta(from: GeoPoint, to: GeoPoint): RouteEta {
        val body = buildJsonObject {
            putJsonArray("locations") {
                add(buildJsonObject { put("lat", from.lat); put("lon", from.lng) })
                add(buildJsonObject { put("lat", to.lat); put("lon", to.lng) })
            }
            put("costing", costing)
            put("units", "kilometers")
            put("directions_type", "none")
            putJsonObject("directions_options") { put("units", "kilometers") }
        }
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        limiter.acquire(url.host)
        val response = json.decodeFromString<Response>(client.execute(request))
        val summary = response.trip?.summary ?: throw ApiException("Reittivastauksesta puuttuu yhteenveto")
        return RouteEta(distanceM = (summary.length * 1000).roundToLong(), durationSec = summary.time.roundToLong())
    }

    @Serializable
    private data class Response(val trip: Trip? = null)

    @Serializable
    private data class Trip(val summary: Summary? = null)

    @Serializable
    private data class Summary(val length: Double = 0.0, val time: Double = 0.0)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
