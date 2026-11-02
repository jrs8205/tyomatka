package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValhallaEtaClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(limiter: RateLimiter = RateLimiter(minIntervalMs = 0)) =
        ValhallaEtaClient(OkHttpClient(), server.url("/").toString().removeSuffix("/"), limiter)

    private val trip = """{"trip":{"summary":{"length":12.3,"time":845.6},"status":0}}"""

    @Test
    fun `pyyntö on auto-profiilin reitti ilman ohjeita ja vastaus antaa matkan ja ajan`() = runTest {
        server.enqueue(MockResponse().setBody(trip))

        val eta = client().eta(GeoPoint(60.17, 24.94), GeoPoint(60.33, 25.05))

        assertEquals(RouteEta(distanceM = 12_300, durationSec = 846), eta)
        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertEquals("POST", request.method)
        assertEquals("/route", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("auto", body.getValue("costing").jsonPrimitive.content)
        assertEquals("none", body.getValue("directions_type").jsonPrimitive.content)
        assertEquals("kilometers", body.getValue("units").jsonPrimitive.content)
        val locations = body.getValue("locations").jsonArray.map { it.jsonObject }
        assertEquals(listOf(60.17, 60.33), locations.map { it.getValue("lat").jsonPrimitive.content.toDouble() })
        assertEquals(listOf(24.94, 25.05), locations.map { it.getValue("lon").jsonPrimitive.content.toDouble() })
    }

    @Test
    fun `virhekoodi on rajapintavirhe`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_code":171,"error":"No suitable edges near location"}"""))

        assertFailsWith<ApiException> { client().eta(GeoPoint(60.17, 24.94), GeoPoint(60.33, 25.05)) }
    }

    @Test
    fun `peräkkäiset pyynnöt kulkevat kuristimen läpi`() {
        val scheduler = TestCoroutineScheduler()
        runTest(scheduler) {
            server.enqueue(MockResponse().setBody(trip))
            server.enqueue(MockResponse().setBody(trip))
            val limited = client(RateLimiter(minIntervalMs = 1_000) { scheduler.currentTime })

            limited.eta(GeoPoint(60.17, 24.94), GeoPoint(60.33, 25.05))
            limited.eta(GeoPoint(60.17, 24.94), GeoPoint(60.33, 25.05))

            assertTrue(scheduler.currentTime >= 1_000, "toinen pyyntö odotti ${scheduler.currentTime} ms")
        }
    }
}
