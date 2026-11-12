package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeliasClientTest {
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

    private fun client(
        layers: String = "address,street,venue",
    ) = PeliasClient(OkHttpClient(), baseUrl = server.url("/geocoding/v1").toString(), layers = layers, source = "© Digitransit")

    private fun feature(
        gid: String,
        name: String,
        street: String? = null,
        housenumber: String? = null,
        postalcode: String? = null,
        locality: String? = null,
        region: String? = null,
        localadmin: String? = null,
        label: String? = null,
        confidence: Double? = null,
        layer: String = "address",
        lon: Double = 24.86,
        lat: Double = 60.27,
        distance: Double? = null,
    ): String {
        val props = listOfNotNull(
            "\"gid\":\"$gid\"",
            "\"layer\":\"$layer\"",
            distance?.let { "\"distance\":$it" },
            "\"name\":\"$name\"",
            street?.let { "\"street\":\"$it\"" },
            housenumber?.let { "\"housenumber\":\"$it\"" },
            postalcode?.let { "\"postalcode\":\"$it\"" },
            locality?.let { "\"locality\":\"$it\"" },
            region?.let { "\"region\":\"$it\"" },
            localadmin?.let { "\"localadmin\":\"$it\"" },
            label?.let { "\"label\":\"$it\"" },
            confidence?.let { "\"confidence\":$it" },
        ).joinToString(",")
        return """{"type":"Feature","properties":{$props},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""
    }

    /** Vastaus, jossa Pelias kertoo tulkitsemansa kaupungin (`geocoding.query.parsed_text.city`). */
    private fun searchResult(parsedCity: String?, feature: String, parsedStreet: String? = null): String {
        val parsed = listOfNotNull(parsedCity?.let { "\"city\":\"$it\"" }, parsedStreet?.let { "\"street\":\"$it\"" }).joinToString(",")
        return """{"geocoding":{"query":{"parsed_text":{$parsed}}},"type":"FeatureCollection","features":[$feature]}"""
    }

    private fun collection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    /** Odottaa pyyntöä enintään 5 s, jottei puuttuva pyyntö jumita testiä. */
    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")

    @Test
    fun `ehdotus kantaa sijaintinsa lähiosuman päättelyyn`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature("gid1", "Karakaari 7", locality = "Espoo", lon = 24.7561, lat = 60.2217))))
        val suggestion = client().autocomplete("Karakaari 7", null).single()
        assertEquals(GeoPoint(60.2217, 24.7561), suggestion.location)
    }

    @Test
    fun `reverse hakee lähimmän osoitteen etäisyysrajan sisältä`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                collection(feature("osm:address:1", "Karakaari 7", postalcode = "02610", locality = "Espoo", lon = 24.7561, lat = 60.2217, distance = 0.061)),
            ),
        )

        val details = client().reverse(GeoPoint(60.2218, 24.7569), maxDistanceM = 300.0)

        val url = take().requestUrl!!
        assertEquals("/geocoding/v1/reverse", url.encodedPath)
        assertEquals("60.2218", url.queryParameter("point.lat"))
        assertEquals("24.7569", url.queryParameter("point.lon"))
        assertEquals("address", url.queryParameter("layers"))
        assertEquals("1", url.queryParameter("size"))
        assertEquals(PlaceDetails("osm:address:1", "Karakaari 7", "Karakaari 7, 02610 Espoo", GeoPoint(60.2217, 24.7561)), details)
    }

    @Test
    fun `reverse ei kelpuuta rajaa kauempaa olevaa osoitetta`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature("osm:address:2", "Kauppakatu 1", locality = "Espoo", distance = 0.4))))
        assertNull(client().reverse(GeoPoint(60.2218, 24.7569), maxDistanceM = 300.0))
        server.enqueue(MockResponse().setBody(collection()))
        assertNull(client().reverse(GeoPoint(60.2218, 24.7569), maxDistanceM = 300.0))
    }

    /** Lähialueen haku (boundary.rect) ja koko alueen haku lähtevät rinnakkain. */
    private fun respond(nearby: String, wide: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.requestUrl!!.queryParameter("boundary.rect.min_lat") != null) nearby else wide
            )
        }
    }

    @Test
    fun `autocomplete ei lähetä avainta ja lähettää sijaintipainotuksen ja kerrokset`() = runTest {
        val body = collection(feature("openstreetmap:address:way/1", "Mallireuna 2", "Mallireuna", "2", "01620", "Vantaa"))
        respond(nearby = body, wide = body)

        val result = client().autocomplete("Mallireuna 2", GeoPoint(60.27, 24.86))

        val requests = listOf(take(), take())
        val request = requests.single { it.requestUrl!!.queryParameter("boundary.rect.min_lat") == null }
        assertNull(request.getHeader("digitransit-subscription-key"), "avain on palvelimella")
        val url = request.requestUrl!!
        assertEquals("/geocoding/v1/autocomplete", url.encodedPath)
        assertEquals("Mallireuna 2", url.queryParameter("text"))
        assertEquals("60.27", url.queryParameter("focus.point.lat"))
        assertEquals("24.86", url.queryParameter("focus.point.lon"))
        assertEquals("address,street,venue", url.queryParameter("layers"))
        assertEquals(1, result.size)
        assertEquals("openstreetmap:address:way/1", result[0].placeId)
        assertEquals("Mallireuna 2", result[0].primaryText)
        assertEquals("01620 Vantaa", result[0].secondaryText)
        // OpenStreetMapista peräisin oleva osoite saa myös OSM-maininnan.
        assertEquals("© Digitransit · © OpenStreetMap", result[0].source)
    }

    @Test
    fun `autocomplete näyttää ensin lähialueen osumat ja sitten muun alueen`() = runTest {
        // Digitransit painottaa suuria kaupunkeja: Oulussa "Hallituskatu 5" antaa ensin Hämeenlinnan.
        respond(
            nearby = collection(feature("gid:oulu", "Hallituskatu 5", locality = "Oulu")),
            wide = collection(
                feature("gid:hml", "Hallituskatu 5", locality = "Hämeenlinna"),
                feature("gid:oulu", "Hallituskatu 5", locality = "Oulu"),
                feature("gid:hki", "Hallituskatu 5", locality = "Helsinki"),
            ),
        )

        val result = client().autocomplete("Hallituskatu 5", GeoPoint(65.01, 25.47))

        assertEquals(listOf("gid:oulu", "gid:hml", "gid:hki"), result.map { it.placeId })
        val nearby = listOf(take(), take())
            .single { it.requestUrl!!.queryParameter("boundary.rect.min_lat") != null }.requestUrl!!
        val minLat = nearby.queryParameter("boundary.rect.min_lat")!!.toDouble()
        val maxLat = nearby.queryParameter("boundary.rect.max_lat")!!.toDouble()
        val minLon = nearby.queryParameter("boundary.rect.min_lon")!!.toDouble()
        val maxLon = nearby.queryParameter("boundary.rect.max_lon")!!.toDouble()
        // Noin 30 km joka suuntaan: leveysaste ~111 km, pituusaste kapenee pohjoisessa.
        assertTrue(minLat in 64.7..64.8 && maxLat in 65.2..65.3, "lat $minLat..$maxLat")
        assertTrue(minLon in 24.8..24.9 && maxLon in 26.0..26.2, "lon $minLon..$maxLon")
    }

    @Test
    fun `lähialueen haun virhe ei kaada hakua`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.requestUrl!!.queryParameter("boundary.rect.min_lat") != null) MockResponse().setResponseCode(500)
                else MockResponse().setBody(collection(feature("gid:hml", "Hallituskatu 5")))
        }

        val result = client().autocomplete("Hallituskatu 5", GeoPoint(65.01, 25.47))

        assertEquals(listOf("gid:hml"), result.map { it.placeId })
    }

    @Test
    fun `tyhjä autocomplete kokeilee search-hakua, joka tuntee ruotsinkieliset nimet`() = runTest {
        // Digitransitin autocomplete ei löydä "Mannerheimvägen 10 Helsingfors", search löytää.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.requestUrl!!.encodedPath.endsWith("/autocomplete")) collection()
                else searchResult(
                    "helsingfors",
                    feature("gid:mh10", "Mannerheimvägen 10", locality = "Helsinki", label = "Mannerheimvägen 10, Helsingfors", confidence = 1.0),
                )
            )
        }

        val result = client().autocomplete("Mannerheimvägen 10 Helsingfors", null)

        assertEquals(listOf("gid:mh10"), result.map { it.placeId })
        assertEquals("Mannerheimvägen 10", result[0].primaryText)
        val search = listOf(take(), take()).single { it.requestUrl!!.encodedPath.endsWith("/search") }.requestUrl!!
        assertEquals("Mannerheimvägen 10 Helsingfors", search.queryParameter("text"))
        // Varahaku ei saa tarjota kokonaisia kaupunkeja.
        assertEquals("address,street,venue", search.queryParameter("layers"))
    }

    @Test
    fun `search-varahaku ei tarjoa ulkomaiselle osoitteelle samannimistä suomalaista katua`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.requestUrl!!.encodedPath.endsWith("/autocomplete")) collection()
                else searchResult(
                    "göteborg",
                    feature(
                        "gid:1", "Kuninkaankatu 10", locality = "Tammisaari", localadmin = "Raasepori",
                        label = "Kuninkaankatu 10 (Kungsgatan 10), Raasepori", confidence = 0.875,
                    ),
                )
            )
        }

        assertTrue(client().autocomplete("Kungsgatan 10 Göteborg", null).isEmpty())
    }

    @Test
    fun `tiedot tulevat välimuistista ja tuntematon tunniste heittää`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature("gid:1", "Kaivokatu 1", "Kaivokatu", "1", "00100", "Helsinki"))))
        val c = client()
        c.autocomplete("Kaivokatu 1", null)

        val details = c.placeDetails("gid:1")
        assertEquals("Kaivokatu 1, 00100 Helsinki", details.address)
        assertEquals(GeoPoint(60.27, 24.86), details.location)
        assertFailsWith<ApiException> { c.placeDetails("gid:999") }
    }

    @Test
    fun `tuplat karsitaan tunnisteen mukaan ja tyhjä haku ei lähde verkkoon`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature("gid:1", "A"), feature("gid:1", "A"))))
        val c = client()
        assertEquals(1, c.autocomplete("A", null).size)
        assertTrue(c.autocomplete("  ", null).isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `geocodeText hakee yhden osuman search-polusta tai palauttaa nullin`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature("gid:7", "Mannerheimintie 10", locality = "Helsinki"))))
        server.enqueue(MockResponse().setBody(collection()))
        val c = client()

        val hit = c.geocodeText("Mannerheimintie 10, Helsinki")
        val miss = c.geocodeText("Olematon katu 999")

        val first = server.takeRequest()
        assertEquals("/geocoding/v1/search", first.requestUrl!!.encodedPath)
        assertEquals("1", first.requestUrl!!.queryParameter("size"))
        assertEquals("gid:7", hit?.placeId)
        assertNull(miss)
    }

    @Test
    fun `geocodeText hylkää epävarman osuman, jonka kaupunki ei vastaa haettua`() = runTest {
        // Digitransit sovittaa Göteborgin Kungsgatanin Raaseporin Kuninkaankadulle.
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "göteborg",
                    feature(
                        "gid:1", "Kuninkaankatu 10", locality = "Tammisaari", localadmin = "Raasepori", region = "Uusimaa",
                        label = "Kuninkaankatu 10 (Kungsgatan 10), Raasepori", confidence = 0.875,
                    ),
                )
            )
        )

        assertNull(client().geocodeText("Kungsgatan 10 Göteborg"))
    }

    @Test
    fun `geocodeText hyväksyy osuman, kun kaupunki täsmää nimeltä, kieliversiolta tai kirjoitusvirheellä`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                // Talonumero voi poiketa (lähin numero samalla kadulla), katu ja kunta täsmäävät.
                searchResult(
                    "jokela",
                    feature("gid:1", "Keskustie 2", street = "Keskustie", housenumber = "2", locality = "Jokela", confidence = 0.987),
                    parsedStreet = "keskustie",
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "uleåborg",
                    feature("gid:2", "Storgatan 1", locality = "Oulu", label = "Storgatan 1, Uleåborg", confidence = 0.99),
                    parsedStreet = "storgatan",
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "ouli",
                    feature("gid:3", "Hallituskatu 5", street = "Hallituskatu", housenumber = "5", locality = "Oulu", confidence = 0.94),
                    parsedStreet = "halituskatu",
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResult("helsingfors", feature("gid:4", "Mannerheimintie 10", locality = "Helsinki", confidence = 1.0))
            )
        )
        val c = client()

        assertEquals("gid:1", c.geocodeText("Keskustie 1, 05400 Jokela")?.placeId)
        assertEquals("gid:2", c.geocodeText("Storgatan 1 Uleåborg")?.placeId)
        assertEquals("gid:3", c.geocodeText("Halituskatu 5 Ouli")?.placeId)
        // Täysi luotettavuus kertoo, että Pelias sovitti myös kaupungin (tässä ruotsinkielisen nimen).
        assertEquals("gid:4", c.geocodeText("Mannerheimintie 10 Helsingfors")?.placeId)
    }

    @Test
    fun `geocodeText hylkää epävarman osuman toiselta kadulta`() = runTest {
        // Digitransit: "Hakatie 2, Piikkiö" → Hietatie 2, "Olematon katu 5, Oulu" → Leevi Madetojan katu 3.
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "piikkiö",
                    feature("gid:1", "Hietatie 2", street = "Hietatie", housenumber = "2", locality = "Piikkiö", confidence = 0.885),
                    parsedStreet = "hyvättyläntie",
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "oulu",
                    feature("gid:2", "Leevi Madetojan katu 3", street = "Leevi Madetojan katu", locality = "Oulu", confidence = 0.919),
                    parsedStreet = "olematon katu",
                )
            )
        )
        val c = client()

        assertNull(c.geocodeText("Hakatie 2, 21500 Piikkiö"))
        assertNull(c.geocodeText("Olematon katu 5, Oulu"))
    }

    @Test
    fun `geocodeText hyväksyy kadun, jonka nimessä on numero`() = runTest {
        // Talonumero poistetaan vain nimestä ja labelista; street-kenttä on kadun koko nimi.
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "helsinki",
                    feature(
                        "gid:1", "3. linja 12", street = "3. linja", housenumber = "12",
                        locality = "Helsinki", label = "3. linja 12, Helsinki", confidence = 0.9,
                    ),
                    parsedStreet = "3. linja",
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "ii",
                    feature(
                        "gid:2", "Tie 866 15", street = "Tie 866", housenumber = "15",
                        locality = "Ii", label = "Tie 866 15, Ii", confidence = 0.9,
                    ),
                    parsedStreet = "tie 866",
                )
            )
        )
        val c = client()

        assertEquals("gid:1", c.geocodeText("3. linja 12, Helsinki")?.placeId)
        assertEquals("gid:2", c.geocodeText("Tie 866 15, Ii")?.placeId)
    }

    @Test
    fun `geocodeText tunnistaa sulkeissa olevan kieliversion ja pisteeseen päättyvän lyhenteen`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "tammisaari",
                    feature(
                        "gid:1", "Kuninkaankatu 10", street = "Kuninkaankatu", housenumber = "10", locality = "Tammisaari",
                        localadmin = "Raasepori", label = "Kuninkaankatu 10 (Kungsgatan 10), Raasepori", confidence = 0.9,
                    ),
                    parsedStreet = "kungsgatan",
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "oulu",
                    feature("gid:2", "Kauppakatu 5", street = "Kauppakatu", housenumber = "5", locality = "Oulu", confidence = 0.8),
                    parsedStreet = "kauppak.",
                )
            )
        )
        val c = client()

        assertEquals("gid:1", c.geocodeText("Kungsgatan 10, Tammisaari")?.placeId)
        assertEquals("gid:2", c.geocodeText("Kauppak. 5, Oulu")?.placeId)
    }

    @Test
    fun `lyhenne avataan kadun päätteeksi eikä hyväksy muuta samalla alulla alkavaa katua`() = runTest {
        // Digitransit: "Kauppak. 5, Oulu" → Haukiputaan Kauppakuja 2 (18 km), "Isok. 10, Oulu" → Isokatu 10.
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "oulu",
                    feature(
                        "openstreetmap:address:kauppakuja2", "Kauppakuja 2", street = "Kauppakuja", housenumber = "2",
                        locality = "Haukipudas", localadmin = "Oulu", label = "Kauppakuja 2, Oulu", confidence = 0.9387025957239584,
                    ),
                    parsedStreet = "kauppak.",
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "oulu",
                    feature("gid:iso10", "Isokatu 10", street = "Isokatu", housenumber = "10", locality = "Oulu", confidence = 0.95),
                    parsedStreet = "isok.",
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResult(
                    "helsinki",
                    feature("gid:mh10", "Mannerheimintie 10", street = "Mannerheimintie", housenumber = "10", locality = "Helsinki", confidence = 0.95),
                    parsedStreet = "mannerheimint.",
                )
            )
        )
        val c = client()
        val oulu = GeoPoint(65.0121, 25.4651)

        assertNull(c.geocodeText("Kauppak. 5, Oulu", oulu))
        assertEquals("gid:iso10", c.geocodeText("Isok. 10, Oulu", oulu)?.placeId)
        assertEquals("gid:mh10", c.geocodeText("Mannerheimint. 10, Helsinki", null)?.placeId)
    }

    @Test
    fun `autocompleten search-varahaku hyväksyy kesken kirjoitetun kaupungin`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.requestUrl!!.encodedPath.endsWith("/autocomplete")) collection()
                else searchResult(
                    "helsingf",
                    feature(
                        "gid:mh10", "Mannerheimvägen 10", street = "Mannerheimvägen", housenumber = "10", locality = "Helsinki",
                        label = "Mannerheimvägen 10, Helsingfors", confidence = 0.8,
                    ),
                    parsedStreet = "mannerheimväg",
                )
            )
        }

        val result = client().autocomplete("Mannerheimväg 10 Helsingf", null)

        assertEquals(listOf("gid:mh10"), result.map { it.placeId })
    }

    @Test
    fun `geocodeText rajaa haun konfiguroituihin kerroksiin`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature("gid:1", "Kauppakatu 5", locality = "Oulu"))))
        server.enqueue(
            MockResponse().setBody(
                searchResult("oulu", feature("gid:2", "Pysäkki Kauppatori", locality = "Oulu", confidence = 1.0, layer = "venue"))
            )
        )

        client().geocodeText("Kauppakatu 5, Oulu")
        // Pelkät osoitteet: kohde ei kelpaa, vaikka palvelin antaisi sen.
        val venue = client(layers = "address").geocodeText("Kauppatori, Oulu")

        assertEquals("address,street,venue", take().requestUrl!!.queryParameter("layers"))
        assertEquals("address", take().requestUrl!!.queryParameter("layers"))
        assertNull(venue)
    }

    @Test
    fun `geocodeText hylkää pelkän alueen, joka olisi kaupungin keskipiste`() = runTest {
        server.enqueue(
            MockResponse().setBody(searchResult("oulu", feature("whosonfirst:locality:1", "Oulu", locality = "Oulu", confidence = 0.6, layer = "locality")))
        )

        assertNull(client().geocodeText("Tuntematon 5, Oulu"))
    }

    @Test
    fun `geocodeText painottaa sijaintia`() = runTest {
        server.enqueue(MockResponse().setBody(collection()))

        client().geocodeText("Hallituskatu 5", GeoPoint(65.01, 25.47))

        val url = server.takeRequest().requestUrl!!
        assertEquals("65.01", url.queryParameter("focus.point.lat"))
        assertEquals("25.47", url.queryParameter("focus.point.lon"))
    }

    @Test
    fun `palvelinvirhe nousee ApiExceptionina koodin kanssa`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"message\":\"Access denied\"}"))

        val e = assertFailsWith<ApiException> { client().autocomplete("Kaivokatu", null) }
        assertEquals(401, e.httpCode)
    }

    @Test
    fun `palvelimen virhe siirtää haun varahakuun`() = runTest {
        for (code in listOf(404, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            server.enqueue(MockResponse().setBody(collection(feature("gid:1", "Kaivokatu 1", "Kaivokatu", "1", "00100", "Helsinki"))))
            val search = FallbackPlaceSearch(listOf(client(), client()))

            val found = search.autocomplete("Kaivokatu 1", null)

            assertEquals(listOf("gid:1"), found.map { it.placeId }, "koodi $code")
        }
    }
}
