package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlannedStop
import fi.tyomatka.core.model.RouteOverview
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import kotlin.math.roundToLong

/** OSRM `route`-palvelu: koko päivän reitti annetussa järjestyksessä, legit ja polyline-5-viiva. */
class OsrmClient(
    private val client: OkHttpClient,
    private val baseUrl: String = Defaults.OSRM_URL,
    private val limiter: RateLimiter? = null,
) : RouteOverviewSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val host = baseUrl.toHttpUrl().host

    override suspend fun computeRouteOverview(origin: GeoPoint, orderedStops: List<PlannedStop>): RouteOverview {
        require(orderedStops.isNotEmpty()) { "Reitti vaatii vähintään yhden stopin" }
        val coordinates = (listOf(origin) + orderedStops.map { it.location })
            .joinToString(";") { "%.6f,%.6f".format(Locale.ROOT, it.lng, it.lat) }
        val url = "$baseUrl/route/v1/driving/$coordinates?overview=full&geometries=polyline&steps=false"
        limiter?.acquire(host)
        val raw = client.executeRaw(Request.Builder().url(url).get().build())
        val response = runCatching { json.decodeFromString<OsrmResponse>(raw.body) }.getOrNull()
        if (raw.code !in 200..299 || response == null || response.code != "Ok") {
            val reason = response?.code ?: "HTTP ${raw.code}"
            val detail = "Reititys epäonnistui: $reason ${response?.message.orEmpty()}".trim()
            if (reason in NO_ROUTE_CODES) throw RouteNotFoundException(detail, raw.code)
            throw ApiException(detail, httpCode = raw.code)
        }
        val route = response.routes.firstOrNull()
            ?: throw RouteNotFoundException("Reititys ei palauttanut reittiä", raw.code)
        return RouteOverview(
            encodedPolyline = route.geometry.orEmpty(),
            legDurationsSec = route.legs.map { it.duration.roundToLong() },
            legDistancesM = route.legs.map { it.distance.roundToLong() },
            totalDurationSec = route.duration.roundToLong(),
        )
    }
}

@Serializable
internal data class OsrmResponse(
    val code: String = "",
    val message: String? = null,
    val routes: List<OsrmRoute> = emptyList(),
)

@Serializable
internal data class OsrmRoute(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val geometry: String? = null,
    val legs: List<OsrmLeg> = emptyList(),
)

@Serializable
internal data class OsrmLeg(val distance: Double = 0.0, val duration: Double = 0.0)

private val NO_ROUTE_CODES = setOf("NoRoute", "NoSegment")
