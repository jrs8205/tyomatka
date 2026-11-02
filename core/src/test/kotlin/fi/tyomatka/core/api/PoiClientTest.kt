package fi.tyomatka.core.api

import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiPart
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoiClientTest {
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

    private fun client() = PoiClient(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

    private val body = """{"generatedAt":"2026-10-22T05:00:00Z","source":"© OpenStreetMap contributors (ODbL)","items":[""" +
        """{"id":"n1","cat":"fuel","lat":60.33,"lon":25.05,"name":"Neste Truck","hgv":true,"tags":{"fuel:HGV_diesel":"yes","Fuel:AdBlue":"yes"}},""" +
        """{"id":"w2","cat":"rest","lat":60.4,"lon":24.9,"brand":"ABC","addr":"Tie 1","hgv":false,"tags":{}},""" +
        """{"id":"n3","cat":"foo","lat":60.5,"lon":24.8,"hgv":false,"tags":{}}]}"""

    @Test
    fun `pyyntö kulkee pyöristetyllä ikkunalla ja yhdellä luokalla`() = runTest {
        server.enqueue(MockResponse().setBody(body))

        val response = client().fetch(PoiBox(24.5, 60.25, 25.0, 60.5), PoiCategory.FUEL)

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertEquals("GET", request.method)
        assertEquals("/v1/poi?bbox=24.50,60.25,25.00,60.50&cats=fuel", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("© OpenStreetMap contributors (ODbL)", response.source)
        assertEquals(2, response.items.size)
    }

    @Test
    fun `katkaisutieto jäsentyy ja puuttuva tieto on ei katkaistu`() {
        assertTrue(PoiClient.parse("""{"items":[],"truncated":true}""").truncated)
        assertFalse(PoiClient.parse("""{"items":[],"truncated":false}""").truncated)
        assertFalse(PoiClient.parse(body).truncated)
    }

    @Test
    fun `vastaus jäsentyy, tagien avaimet pieniksi ja tuntematon luokka ohitetaan`() {
        val response = PoiClient.parse(body)
        val truck = response.items[0]
        assertEquals("n1", truck.id)
        assertEquals(PoiCategory.FUEL, truck.category)
        assertEquals(60.33, truck.location.lat)
        assertEquals(25.05, truck.location.lng)
        assertEquals("Neste Truck", truck.name)
        assertNull(truck.brand)
        assertTrue(truck.hgv)
        assertEquals(mapOf("fuel:hgv_diesel" to "yes", "fuel:adblue" to "yes"), truck.tags)
        val rest = response.items[1]
        assertEquals(PoiCategory.REST, rest.category)
        assertNull(rest.name)
        assertEquals("ABC", rest.brand)
        assertEquals("Tie 1", rest.address)
        assertFalse(rest.hgv)
        assertEquals(listOf("n1", "w2"), response.items.map { it.id })
    }

    @Test
    fun `429 ja 503 kertovat, ettei palvelu ole saatavilla`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"ip"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"paused"}"""))

        val first = assertFailsWith<PoiUnavailableException> { client().fetch(PoiBox(24.5, 60.25, 25.0, 60.5), PoiCategory.FUEL) }
        assertEquals(429, first.httpCode)
        assertFailsWith<PoiUnavailableException> { client().fetch(PoiBox(24.5, 60.25, 25.0, 60.5), PoiCategory.FUEL) }
    }

    @Test
    fun `muu virhekoodi on tavallinen rajapintavirhe`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("virhe"))

        val error = assertFailsWith<ApiException> { client().fetch(PoiBox(24.5, 60.25, 25.0, 60.5), PoiCategory.FUEL) }
        assertFalse(error is PoiUnavailableException)
    }

    private val parts = listOf(
        PoiPart(PoiBox(24.5, 60.25, 24.75, 60.5), PoiCategory.FUEL),
        PoiPart(PoiBox(24.75, 60.25, 25.0, 60.5), PoiCategory.GAS),
    )

    private val batchBody = """{"parts":[$body,""" +
        """{"generatedAt":"2026-10-22T05:00:00Z","source":"s","items":[{"id":"n9","cat":"gas","lat":60.4,"lon":24.8,"hgv":false,"tags":{}}],"truncated":true}]}"""

    @Test
    fun `yhteishaku lähettää palat yhdellä POSTilla ja palauttaa osat palojen järjestyksessä`() = runTest {
        server.enqueue(MockResponse().setBody(batchBody))
        // Desimaalipilkun lokaali: ikkunan pitää silti kulkea pisteillä.
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("fi-FI"))
        val responses = try {
            client().fetchBatch(parts)
        } finally {
            Locale.setDefault(locale)
        }

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertEquals("POST", request.method)
        assertEquals("/v1/poi/batch", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(
            """{"parts":[{"bbox":"24.50,60.25,24.75,60.50","cats":"fuel"},{"bbox":"24.75,60.25,25.00,60.50","cats":"gas"}]}""",
            request.body.readUtf8(),
        )
        assertEquals(listOf(listOf("n1", "w2"), listOf("n9")), responses.map { r -> r.items.map { it.id } })
        assertEquals(listOf(false, true), responses.map { it.truncated })
        // Osan oma runko välimuistiin: sama jäsennys kuin erillisellä haulla.
        responses.forEach { assertEquals(it.copy(raw = ""), PoiClient.parse(it.raw).copy(raw = "")) }
        assertEquals(PoiClient.parse(body).items, PoiClient.parse(responses[0].raw).items)
    }

    @Test
    fun `yhteishaun 429 ja 503 kertovat, ettei palvelu ole saatavilla, ja muu virhe on rajapintavirhe`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"ip"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"paused"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("virhe"))

        assertEquals(429, assertFailsWith<PoiUnavailableException> { client().fetchBatch(parts) }.httpCode)
        assertEquals(503, assertFailsWith<PoiUnavailableException> { client().fetchBatch(parts) }.httpCode)
        val error = assertFailsWith<ApiException> { client().fetchBatch(parts) }
        assertFalse(error is PoiUnavailableException)
        assertEquals(500, error.httpCode)
    }

    @Test
    fun `yhteishaun osien määrä pitää täsmätä paloihin`() = runTest {
        server.enqueue(MockResponse().setBody("""{"parts":[$body]}"""))

        assertFailsWith<ApiException> { client().fetchBatch(parts) }
    }

    @Test
    fun `rikkinäinen yhteishaun vastaus on rajapintavirhe`() = runTest {
        for (broken in listOf("<html>virhe</html>", """{"parts":"x"}""", """{"parts":[null,null]}""")) {
            server.enqueue(MockResponse().setBody(broken))
            val error = assertFailsWith<ApiException>(broken) { client().fetchBatch(parts) }
            assertFalse(error is PoiUnavailableException)
        }
    }

    @Test
    fun `yli 50 tai nolla palaa hylätään ennen verkkoa`() = runTest {
        assertEquals(50, PoiClient.MAX_BATCH)
        val many = List(PoiClient.MAX_BATCH + 1) { parts[0] }
        assertFailsWith<IllegalArgumentException> { client().fetchBatch(many) }
        assertFailsWith<IllegalArgumentException> { client().fetchBatch(emptyList()) }
        assertEquals(0, server.requestCount)
    }
}
