package fi.tyomatka.diag

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagHttpInterceptorTest {
    private val events = mutableListOf<String>()

    private fun capture() = Diag.install { _, line -> events += line }

    @AfterTest
    fun reset() = Diag.install(null)

    @Test
    fun `kyselyparametrit jatetaan pois`() {
        val line = DiagHttpInterceptor.describe("GET", "https://h.example/p/q?key=SALAINEN&x=1".toHttpUrl(), 200, 1234, 412, null, canceled = false)
        assertFalse(line.contains("SALAINEN"), line)
        assertEquals("GET h.example/p/q 200 1234 t 412 ms", line)
    }

    @Test
    fun `virhe kirjataan luokalla ja viestilla`() {
        val line = DiagHttpInterceptor.describe("POST", "https://valhalla.example/route".toHttpUrl(), null, null, 15002, SocketTimeoutException("timeout"), canceled = false)
        assertEquals("POST valhalla.example/route VIRHE SocketTimeoutException: timeout 15002 ms", line)
    }

    @Test
    fun `peruttu kutsu kirjataan peruttuna eika virheena`() {
        val line = DiagHttpInterceptor.describe("GET", "https://h.example/geocode".toHttpUrl(), null, null, 12, IOException("Canceled"), canceled = true)
        assertEquals("GET h.example/geocode PERUTTU 12 ms", line)
    }

    @Test
    fun `pyynto kirjataan tapahtumana`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("ok")) }
        capture()
        val client = OkHttpClient.Builder().addInterceptor(DiagHttpInterceptor()).build()
        client.newCall(Request.Builder().url(server.url("/geocode?q=x")).build()).execute().use { it.body.string() }
        server.shutdown()
        assertEquals(1, events.size, events.toString())
        assertTrue(events[0].startsWith("http: GET ") && events[0].contains(" 200 ") && !events[0].contains("q=x"), events[0])
    }

    @Test
    fun `onnistunut pyynto kirjaa luetut tavut`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("abcdef")) }
        capture()
        val client = OkHttpClient.Builder().addInterceptor(DiagHttpInterceptor()).build()
        client.newCall(Request.Builder().url(server.url("/p")).build()).execute().use { it.body.string() }
        server.shutdown()
        assertEquals(1, events.size, events.toString())
        assertTrue(events[0].contains(" 200 6 t "), events[0])
    }

    @Test
    fun `rivi kirjataan vasta rungon luvun jalkeen ja rungon katkeaminen on virhe`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("x".repeat(4000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        }
        capture()
        val client = OkHttpClient.Builder().addInterceptor(DiagHttpInterceptor()).build()
        val response = client.newCall(Request.Builder().url(server.url("/route")).build()).execute()
        assertTrue(events.isEmpty(), events.toString()) // otsakkeet eivät vielä riitä riviin
        assertFailsWith<IOException> { response.body.string() }
        server.shutdown()
        assertEquals(1, events.size, events.toString())
        assertTrue(events[0].contains(" VIRHE "), events[0])
    }

    @Test
    fun `poikkeus kirjataan ja heitetaan edelleen`() {
        val server = MockWebServer()
        val url = server.url("/route")
        server.shutdown()
        capture()
        val client = OkHttpClient.Builder().addInterceptor(DiagHttpInterceptor()).build()
        assertFailsWith<IOException> { client.newCall(Request.Builder().url(url).build()).execute() }
        assertEquals(1, events.size, events.toString())
        assertTrue(events[0].contains(" VIRHE "), events[0])
    }
}
