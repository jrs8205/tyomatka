package fi.tyomatka.core.api

import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportClientTest {
    private lateinit var server: MockWebServer
    private val device = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val rid = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = ReportClient(OkHttpClient(), server.url("/").toString().removeSuffix("/"), ZoneId.of("Europe/Helsinki"))

    private fun day(text: String) = LocalDate.parse(text).toEpochDay()

    @Test
    fun `lähetys kulkee laitetunnisteella ja palauttaa palvelimen päivän`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"date":"2026-10-22","at":"2026-10-22T22:30:00.000Z"}"""))

        val epochDay = client().send(rid, "n1", "closed", "yes", device)

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertEquals("POST", request.method)
        assertEquals("/v1/reports", request.path)
        assertEquals(device, request.getHeader("X-Device"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(mapOf("poi" to "n1", "field" to "closed", "value" to "yes", "rid" to rid), body.mapValues { it.value.jsonPrimitive.content })
        assertNull(body["at"])
        // 22.30 UTC on jo 23.10. Suomen aikaa: päivä näytetään kuljettajan aikavyöhykkeellä.
        assertEquals(day("2026-10-23"), epochDay)
    }

    @Test
    fun `aiemmin tallentunut sama ilmoitus on onnistuminen`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"date":"2026-10-22","at":"2026-10-22T05:00:00.000Z","duplicate":true}"""))
        assertEquals(day("2026-10-22"), client().send(rid, "n1", "closed", "yes", device))
    }

    @Test
    fun `jonossa ollut ilmoitus kulkee tekohetkellään`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"date":"2026-10-20","at":"2026-10-20T13:45:00.000Z"}"""))
        client().send(rid, "n1", "closed", "yes", device, at = Instant.parse("2026-10-20T13:45:00Z"))
        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertEquals("2026-10-20T13:45:00Z", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["at"]?.jsonPrimitive?.content)
    }

    @Test
    fun `hylkäys ja raja kertovat koodin`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"value"}"""))
        assertEquals(400, assertFailsWith<ApiException> { client().send(rid, "n1", "closed", "maybe", device) }.httpCode)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"ip"}"""))
        assertEquals(429, assertFailsWith<ApiException> { client().send(rid, "n1", "closed", "yes", device) }.httpCode)
    }

    @Test
    fun `ikkunan ilmoitukset jäsentyvät ja tuntematon kenttä ohitetaan`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"poi":"n1","field":"closed","value":"yes","date":"2026-10-22","at":"2026-10-22T22:30:00.000Z"},""" +
                    """{"poi":"k-neste-2605","field":"fuels","value":"diesel;hvo","date":"2026-10-22"},""" +
                    """{"poi":"n2","field":"phone","value":"x","date":"2026-10-22"},""" +
                    """{"poi":"n3","field":"shop","value":"no","date":"eilen"}]}""",
            ),
        )

        val reports = client().window(PoiBox(24.5, 60.25, 25.0, 60.5))

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertEquals("/v1/reports?bbox=24.50,60.25,25.00,60.50", request.path)
        assertEquals(
            listOf(
                StationReport("n1", ReportField.CLOSED, "yes", day("2026-10-23"), atEpochSec = Instant.parse("2026-10-22T22:30:00Z").epochSecond),
                StationReport("k-neste-2605", ReportField.FUELS, "diesel;hvo", day("2026-10-22")),
            ),
            reports,
        )
    }

    @Test
    fun `ikkunan virhe kertoo koodin`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        assertEquals(503, assertFailsWith<ApiException> { client().window(PoiBox(24.5, 60.25, 25.0, 60.5)) }.httpCode)
    }

    @Test
    fun `oman laitteen ilmoitusten poisto lähettää tunnuksen otsakkeessa`() = runTest {
        server.enqueue(MockResponse().setBody("{\"deleted\":2}"))

        client().forget(device)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/reports/forget", request.requestUrl!!.encodedPath)
        assertEquals(device, request.getHeader("X-Device"))
        assertEquals("", request.body.readUtf8())
    }

    /** Kelvollinen vastaus tippuu tavu kerrallaan alle lukurajan tauoin: vain kokonaisaikaraja voi katkaista kutsun. */
    private fun trickling(body: String) = MockResponse().setBody(body + " ".repeat(100)).throttleBody(1, 10, TimeUnit.MILLISECONDS)

    private fun impatient() = ReportClient(OkHttpClient(), server.url("/").toString().removeSuffix("/"), ZoneId.of("Europe/Helsinki"), callTimeoutMs = 300)

    @Test
    fun `hitaasti tippuva poistovastaus katkeaa aikarajaan`() = runTest {
        server.enqueue(trickling("{\"deleted\":1}"))

        assertFailsWith<IOException> { impatient().forget(device) }
    }

    @Test
    fun `hitaasti tippuva lähetysvastaus katkeaa aikarajaan`() = runTest {
        server.enqueue(trickling("""{"date":"2026-10-22","at":"2026-10-22T05:00:00.000Z"}"""))

        assertFailsWith<IOException> { impatient().send(rid, "n1", "closed", "yes", device) }
    }

    @Test
    fun `täysi yhteinen jono ei estä poistoa`() = runTest {
        val arrived = CountDownLatch(5)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") return MockResponse().setBody("{\"deleted\":0}")
                arrived.countDown()
                release.await(8, TimeUnit.SECONDS)
                return MockResponse().setBody("{\"items\":[]}")
            }
        }
        val reports = client()
        val windows = List(5) { async(Dispatchers.Default) { reports.windowRaw(PoiBox(24.5, 60.25, 25.0, 60.5)) } }
        try {
            assertTrue(withContext(Dispatchers.Default) { arrived.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.Default) { withTimeout(3_000) { reports.forget(device) } }
        } finally {
            release.countDown()
        }
        windows.awaitAll()
    }

    @Test
    fun `palvelimen poistama tunnus tunnistetaan vastauksesta`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410).setBody("{\"error\":\"tunnus poistettu\"}"))

        assertFailsWith<DeviceForgottenException> { client().send(rid, "n1", "closed", "yes", device) }
    }

    @Test
    fun `muun lähteen 410 on tavallinen hylkäys`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410).setBody("<html>Gone</html>"))

        val e = assertFailsWith<ApiException> { client().send(rid, "n1", "closed", "yes", device) }

        assertFalse(e is DeviceForgottenException)
        assertEquals(410, e.httpCode)
    }

    @Test
    fun `poisto vaatii palvelimen vahvistuksen`() = runTest {
        listOf("<html>ok</html>", "{}", "").forEach { body ->
            server.enqueue(MockResponse().setBody(body))

            assertFailsWith<ApiException>(body) { client().forget(device) }
        }
    }

    @Test
    fun `poiston virhe nousee koodin kanssa`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":\"ip\"}"))

        val e = assertFailsWith<ApiException> { client().forget(device) }

        assertEquals(429, e.httpCode)
    }
}
