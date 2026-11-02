package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BusinessClientTest {
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

    private fun client() = BusinessClient(OkHttpClient(), server.url("/").toString())

    private val body = """{"source":"Overture Maps Foundation (CDLA Permissive 2.0; Foursquare Apache 2.0)","items":[""" +
        """{"id":"0b6f4a2e-1c3d-4e5f-8a9b-0c1d2e3f4a5b","name":"Askolan Auto- ja Konekorjaamo","street":"Teollisuustie 3",""" +
        """"postcode":"07230","locality":"ASKOLA","lat":60.5291,"lon":25.6002},""" +
        """{"id":"1c7f4a2e-1c3d-4e5f-8a9b-0c1d2e3f4a5b","name":"Askolan Kone Oy","lat":60.53,"lon":25.6}]}"""

    @Test
    fun `haku kulkee hakusanalla ja kahden desimaalin sijainnilla`() = runTest {
        server.enqueue(MockResponse().setBody(body))

        client().search("Askolan auto ja kone oy", GeoPoint(60.3912, 25.6634))

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertEquals("GET", request.method)
        assertEquals("/v1/places", request.requestUrl!!.encodedPath)
        assertEquals("Askolan auto ja kone oy", request.requestUrl!!.queryParameter("q"))
        // Pilkku sellaisinaan, kuten karttakohteissa: sama osoite reunavälimuistissa.
        assertTrue(request.path!!.endsWith("&near=60.39,25.66"), request.path)
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `sijainnin desimaalierotin on piste suomenkielisessäkin laitteessa`() = runTest {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("fi-FI"))
        try {
            server.enqueue(MockResponse().setBody(body))
            client().search("lidl", GeoPoint(60.3951, -25.6649))
        } finally {
            Locale.setDefault(default)
        }

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertEquals("60.40,-25.66", request.requestUrl!!.queryParameter("near"))
    }

    @Test
    fun `ilman sijaintia pyynnössä ei ole near-parametria`() = runTest {
        server.enqueue(MockResponse().setBody(body))

        client().search("lidl", null)

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
        assertNull(request.requestUrl!!.queryParameter("near"))
        assertEquals(listOf("q"), request.requestUrl!!.queryParameterNames.toList())
    }

    @Test
    fun `vastauksen rivit jäsentyvät ja puuttuvat kentät ovat null`() = runTest {
        server.enqueue(MockResponse().setBody(body))

        val items = client().search("askola", null)

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("0b6f4a2e-1c3d-4e5f-8a9b-0c1d2e3f4a5b", first.id)
        assertEquals("Askolan Auto- ja Konekorjaamo", first.name)
        assertEquals("Teollisuustie 3", first.street)
        assertEquals("07230", first.postcode)
        assertEquals("ASKOLA", first.locality)
        assertEquals(GeoPoint(60.5291, 25.6002), first.location)
        val second = items[1]
        assertEquals("Askolan Kone Oy", second.name)
        assertNull(second.street)
        assertNull(second.postcode)
        assertNull(second.locality)
        assertEquals(GeoPoint(60.53, 25.6), second.location)
    }

    @Test
    fun `tyhjät tekstikentät ovat null`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"items":[{"id":"x","name":"Kioski","street":" ","postcode":"","locality":"","lat":60.1,"lon":24.9}]}""",
        ))

        val business = client().search("kioski", null).single()

        assertNull(business.street)
        assertNull(business.postcode)
        assertNull(business.locality)
        assertEquals("", business.addressLine)
    }

    @Test
    fun `tyhjä vastaus on tyhjä lista`() = runTest {
        server.enqueue(MockResponse().setBody("""{"source":"x","items":[]}"""))

        assertEquals(emptyList(), client().search("oy", null))
    }

    @Test
    fun `osoiterivi muuttaa isoin kirjoitetut sanat alkukirjaimeksi`() {
        val business = Business("a", "Askolan Auto- ja Konekorjaamo", "Teollisuustie 3", "07230", "ASKOLA", GeoPoint(60.5, 25.6))
        assertEquals("Teollisuustie 3, Askola", business.addressLine)
    }

    @Test
    fun `ilman katua osoiterivi on pelkkä paikkakunta`() {
        val business = Business("a", "Kauppa", null, null, "MONNINKYLÄ", GeoPoint(60.5, 25.6))
        assertEquals("Monninkylä", business.addressLine)
    }

    @Test
    fun `osoiterivi säilyttää sekakirjaimet, porraskirjaimet ja yhdysmerkin osat`() {
        assertEquals(
            "Koski-Mäki 3 B, Pedersöre",
            Business("a", "N", "KOSKI-MÄKI 3 B", null, "Pedersöre", GeoPoint(0.0, 0.0)).addressLine,
        )
        assertEquals("Mannerheimintie 12A, Helsinki", Business("a", "N", "Mannerheimintie 12A", null, "Helsinki", GeoPoint(0.0, 0.0)).addressLine)
        // Vain kokonaan isoin kirjoitetut sanat muuttuvat; sekamuoto säilyy.
        assertEquals("McAdamintie 2, Askola", Business("a", "N", "McAdamintie 2", null, "ASKOLA", GeoPoint(0.0, 0.0)).addressLine)
        assertEquals("Teollisuustie 3", Business("a", "N", "Teollisuustie 3", null, null, GeoPoint(0.0, 0.0)).addressLine)
        assertEquals("", Business("a", "N", null, "07230", null, GeoPoint(0.0, 0.0)).addressLine)
    }

    @Test
    fun `429 ja 503 kertovat, ettei haku ole juuri nyt saatavilla`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(429, assertFailsWith<PoiUnavailableException> { client().search("lidl", null) }.httpCode)
        assertEquals(503, assertFailsWith<PoiUnavailableException> { client().search("lidl", null) }.httpCode)
    }

    @Test
    fun `muu virhekoodi on rajapintavirhe`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("kaatui"))

        val error = assertFailsWith<ApiException> { client().search("lidl", null) }
        assertFalse(error is PoiUnavailableException)
        assertEquals(500, error.httpCode)
    }

    @Test
    fun `virheellinen runko on rajapintavirhe`() = runTest {
        server.enqueue(MockResponse().setBody("<html>"))

        assertIs<ApiException>(assertFailsWith<Exception> { client().search("lidl", null) })
    }

    @Test
    fun `tunnisteen etuliite erottaa yritykset`() {
        assertTrue(BusinessClient.isBusiness(BusinessClient.PREFIX + "0b6f4a2e-1c3d-4e5f-8a9b-0c1d2e3f4a5b"))
        assertFalse(BusinessClient.isBusiness("google:abc"))
        assertFalse(BusinessClient.isBusiness("0b6f4a2e-1c3d-4e5f-8a9b-0c1d2e3f4a5b"))
        assertEquals("overture:", BusinessClient.PREFIX)
        assertEquals("© Overture Maps Foundation", BusinessClient.SOURCE)
    }
}
