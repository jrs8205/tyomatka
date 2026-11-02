package fi.tyomatka.nav.ferrostar

import fi.tyomatka.core.api.RateLimiter
import fi.tyomatka.core.api.UserAgentInterceptor
import fi.tyomatka.core.nav.RouteFailure
import fi.tyomatka.nav.RoutingException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reittipyyntö FOSSGIS-Valhallalle: runko tulee Ferrostarin generaattorilta (testissä valmiina), vastaus menee
 * Ferrostarin jäsentimelle (testissä talteen). Tässä testataan verkko-osuus: osoite, otsakkeet, kuristin ja virheet.
 */
class ValhallaRouteClientTest {
    private lateinit var server: MockWebServer
    private var parsed: ByteArray? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** [clockOffsetMs]: kellon lähtöarvo; laitteella kello on käynnistyksestä kulunut aika, ei nolla. */
    private fun TestScope.client(
        parse: (ByteArray) -> List<Route> = { parsed = it; emptyList() },
        clockOffsetMs: Long = 0,
        latestFix: () -> UserLocation? = { null },
        onRequest: (UserLocation) -> Unit = {},
    ) = ValhallaRouteClient(
        http = valhallaHttpClient(OkHttpClient.Builder().addInterceptor(UserAgentInterceptor("9.9")).build(), "9.9"),
        limiter = RateLimiter(minIntervalMs = 1000, now = { clockOffsetMs + testScheduler.currentTime }),
        now = { clockOffsetMs + testScheduler.currentTime },
        latestFix = latestFix,
        requestBody = { fix, _ ->
            onRequest(fix)
            RouteRequest.HttpPost(
                server.url("/route").toString(),
                mapOf("Content-Type" to "application/json"),
                """{"locations":[]}""".toByteArray(),
            )
        },
        parse = parse,
    )

    private val here = UserLocation(GeographicCoordinate(61.4978, 23.7610), 5.0, null, Instant.ofEpochSecond(1_700_000_000), null)
    private val target = listOf(Waypoint(GeographicCoordinate(61.4500, 23.8500), WaypointKind.BREAK, null))

    private suspend fun failureOf(block: suspend () -> Unit): RouteFailure =
        assertFailsWith<RoutingException> { block() }.failure

    @Test
    fun `pyynto on POST reittiosoitteeseen tunnistettavin otsakkein ja runko menee jasentimelle`() = runTest {
        server.enqueue(MockResponse().setBody("""{"routes":[]}"""))

        val routes = client().getRoutes(here, target)

        assertEquals(emptyList<Route>(), routes)
        assertEquals("""{"routes":[]}""", parsed?.decodeToString())
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/route", request.path)
        assertEquals("""{"locations":[]}""", request.body.readUtf8())
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("Tyomatka/9.9", request.getHeader("X-Client-Id"))
        assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("Tyomatka/9.9"), request.getHeader("User-Agent"))
    }

    @Test
    fun `ensimmainen pyynto ei odota vaikka kello on kaynnistyksesta kulunut aika`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        val client = client(clockOffsetMs = 432_000_000L)
        val start = testScheduler.currentTime

        client.getRoutes(here, target)

        assertEquals(start, testScheduler.currentTime, "odotus ${testScheduler.currentTime - start} ms")
    }

    @Test
    fun `perakkaiset pyynnot odottavat kuristinta vahintaan sekunnin`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        val client = client()
        val start = testScheduler.currentTime

        client.getRoutes(here, target)
        client.getRoutes(here, target)

        assertTrue(testScheduler.currentTime - start >= 1000, "odotus ${testScheduler.currentTime - start} ms")
    }

    @Test
    fun `reitin puuttuminen luetaan OSRM-rungosta`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"NoRoute","message":"No route found"}"""))

        assertEquals(RouteFailure.NoRoute, failureOf { client().getRoutes(here, target) })
    }

    @Test
    fun `palvelinvirhe tarkoittaa etta palvelin ei vastaa`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(RouteFailure.Unavailable, failureOf { client().getRoutes(here, target) })
    }

    @Test
    fun `kuormaraja lukee Retry-After-otsakkeen`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

        assertEquals(RouteFailure.RateLimited(30), failureOf { client().getRoutes(here, target) })
    }

    @Test
    fun `kuormarajan odotus koskee asiakkaan kaikkia pyyntoja`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        server.enqueue(MockResponse().setBody("{}"))
        val client = client()
        assertEquals(RouteFailure.RateLimited(60), failureOf { client.getRoutes(here, target) })

        // Toinen pyyntö (vaikkapa uudelleenreititys toiseen kohteeseen) odottaa saman palvelimen odotusajan.
        val second = launch { client.getRoutes(here, target) }
        testScheduler.advanceTimeBy(59_999)
        testScheduler.runCurrent()
        assertEquals(1, server.requestCount, "pyyntö ei saa lähteä ennen Retry-After-aikaa")
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null, "toinen pyyntö lähtee odotusajan jälkeen")
        second.join()
    }

    @Test
    fun `odotuksen jalkeen pyynto lahtee tuoreesta sijainnista`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        server.enqueue(MockResponse().setBody("{}"))
        val fresh = UserLocation(GeographicCoordinate(61.5100, 23.7700), 5.0, null, Instant.ofEpochSecond(1_700_000_060), null)
        val requested = mutableListOf<UserLocation>()
        val client = client(latestFix = { fresh }, onRequest = { requested += it })
        assertEquals(RouteFailure.RateLimited(60), failureOf { client.getRoutes(here, target) })

        // Minuutin odotuksen aikana auto on liikkunut: lähtöpiste haetaan uudelleen odotuksen jälkeen.
        val second = launch { client.getRoutes(here, target) }
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null, "toinen pyyntö lähtee odotusajan jälkeen")
        second.join()

        assertEquals(fresh.coordinates, requested.last().coordinates, "odotuksen jälkeen lähtöpiste on tuore")
    }

    @Test
    fun `kesken katkennut vastaus tarkoittaa etta palvelin ei vastaa`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"routes":[{"long":"body"}]}""".repeat(200))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        assertEquals(RouteFailure.Unavailable, failureOf { client().getRoutes(here, target) })
    }

    @Test
    fun `yhteysvirhe tarkoittaa etta palvelin ei vastaa`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RouteFailure.Unavailable, failureOf { client().getRoutes(here, target) })
    }

    @Test
    fun `valhallan asiakas noudattaa aikakatkaisuja ja kahden yhteyden rajaa`() {
        val client = valhallaHttpClient(OkHttpClient(), "9.9")

        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(30_000, client.callTimeoutMillis)
        assertEquals(2, client.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun `onnistunut vastaus jota jasennin ei ymmarra on asetusvirhe`() = runTest {
        server.enqueue(MockResponse().setBody("not json"))

        val failure = failureOf { client(parse = { throw IllegalArgumentException("parse") }).getRoutes(here, target) }

        assertEquals(RouteFailure.BadConfig, failure)
    }
}
