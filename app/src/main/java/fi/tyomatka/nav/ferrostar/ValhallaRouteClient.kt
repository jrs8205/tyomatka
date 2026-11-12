package fi.tyomatka.nav.ferrostar

import com.stadiamaps.ferrostar.core.CustomRouteProvider
import fi.tyomatka.core.api.RateLimiter
import fi.tyomatka.core.nav.RouteFailure
import fi.tyomatka.core.nav.ValhallaRequest
import fi.tyomatka.nav.RoutingException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reittipyyntö FOSSGIS-Valhallalle Ferrostarin omana reittilähteenä. Pyynnön rungon tekee Ferrostarin generaattori
 * ja vastauksen jäsentää sen OSRM-jäsennin; tämä luokka hoitaa verkon: kuristimen (enintään yksi pyyntö sekunnissa
 * palvelinta kohti), tunnisteotsakkeet ja virheiden tulkinnan [RoutingException]iksi, joiden [RouteFailure] kertoo
 * ajosessiolle, miten uudelleen yritetään.
 */
class ValhallaRouteClient(
    private val http: OkHttpClient,
    private val limiter: RateLimiter,
    private val requestBody: (UserLocation, List<Waypoint>) -> RouteRequest.HttpPost,
    private val parse: (ByteArray) -> List<Route>,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val latestFix: () -> UserLocation? = { null },
) : CustomRouteProvider {

    /**
     * Palvelimen pyytämä odotus (HTTP 429 Retry-After) koskee asiakkaan kaikkia pyyntöjä, myös uudelleenreititystä.
     * Null, kun odotusta ei ole: vähennyslasku `Long.MIN_VALUE - now()` vuotaisi yli positiiviseksi ja odottaisi
     * ikuisesti, eikä reittiä tulisi koskaan.
     */
    @Volatile
    private var holdUntilMs: Long? = null

    override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> {
        val hold = holdUntilMs?.let { it - now() } ?: 0L
        // Odotuksen aikana auto liikkuu: lähtöpiste haetaan uudelleen odotuksen jälkeen, myös
        // Ferrostarin omalla uudelleenreitityspolulla, joka antaa odotusta edeltävän sijainnin.
        val origin = if (hold > 0) {
            delay(hold)
            latestFix() ?: userLocation
        } else {
            userLocation
        }
        val post = requestBody(origin, waypoints)
        val url = post.url.toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .post(post.body.toRequestBody(JSON))
            .apply { post.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        limiter.acquire(url.host)
        val response = try {
            http.executeBytes(request)
        } catch (e: IOException) {
            throw RoutingException(RouteFailure.Unavailable, cause = e)
        }
        if (response.code !in 200..299) {
            val failure = ValhallaRequest.failureOf(response.code, response.body.decodeToString(), response.retryAfter)
            if (failure is RouteFailure.RateLimited) holdUntilMs = now() + failure.retryAfterSec * 1000
            throw RoutingException(failure)
        }
        return try {
            parse(response.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RoutingException(RouteFailure.BadConfig, cause = e)
        }
    }

    private class RawResponse(val code: Int, val body: ByteArray, val retryAfter: String?)

    /** Myös rungon luvun katkeaminen palaa odottajalle: ilman kiinniottoa se katoaisi OkHttp:n säikeeseen. */
    private suspend fun OkHttpClient.executeBytes(request: Request): RawResponse =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { RawResponse(it.code, it.body.bytes(), it.header("Retry-After")) }
                    }
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { if (!cont.isCancelled) cont.resumeWithException(it) },
                    )
                }
            })
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/**
 * Valhallan oma HTTP-asiakas yhteisestä: aikakatkaisut 10 s yhteys, 20 s luku ja 30 s kokonaisuus, `X-Client-Id`
 * käyttäjäagentin rinnalle ja enintään kaksi yhtäaikaista pyyntöä palvelimelle (FOSSGIS-ehdot).
 */
fun valhallaHttpClient(shared: OkHttpClient, versionName: String): OkHttpClient = shared.newBuilder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 })
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("X-Client-Id", "Tyomatka/$versionName").build())
    }
    .build()
