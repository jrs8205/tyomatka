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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotonClientTest {
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

    private fun client() = PhotonClient(
        OkHttpClient(),
        baseUrl = server.url("/").toString().removeSuffix("/"),
        bbox = Defaults.FINLAND_BBOX,
    )

    private fun feature(
        osmType: String = "N",
        osmId: Long = 1,
        name: String? = null,
        street: String? = null,
        housenumber: String? = null,
        postcode: String? = null,
        city: String? = null,
        district: String? = null,
        locality: String? = null,
        county: String? = null,
        state: String? = null,
        country: String? = null,
        countrycode: String? = null,
        type: String = "house",
        lon: Double = 24.94,
        lat: Double = 60.17,
    ): String {
        val props = listOfNotNull(
            "\"osm_type\":\"$osmType\"",
            "\"osm_id\":$osmId",
            "\"type\":\"$type\"",
            name?.let { "\"name\":\"$it\"" },
            street?.let { "\"street\":\"$it\"" },
            housenumber?.let { "\"housenumber\":\"$it\"" },
            postcode?.let { "\"postcode\":\"$it\"" },
            city?.let { "\"city\":\"$it\"" },
            district?.let { "\"district\":\"$it\"" },
            locality?.let { "\"locality\":\"$it\"" },
            county?.let { "\"county\":\"$it\"" },
            state?.let { "\"state\":\"$it\"" },
            country?.let { "\"country\":\"$it\"" },
            countrycode?.let { "\"countrycode\":\"$it\"" },
        ).joinToString(",")
        return """{"type":"Feature","properties":{$props},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""
    }

    private fun collection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    /** Odottaa pyyntöä enintään 5 s, jottei puuttuva pyyntö jumita testiä. */
    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")

    private fun finland() = PhotonClient(
        OkHttpClient(),
        baseUrl = server.url("/").toString().removeSuffix("/"),
        bbox = Defaults.FINLAND_BBOX,
        countries = Finland.COUNTRIES,
    )

    @Test
    fun `Suomeen rajattu haku pudottaa muiden maiden tulokset myös käänteishaussa`() = runTest {
        val haparanda = feature(osmId = 1, street = "Storgatan", housenumber = "1", city = "Haparanda", countrycode = "SE", lat = 65.83, lon = 24.13)
        val tornio = feature(osmId = 2, street = "Hallituskatu", housenumber = "1", city = "Tornio", countrycode = "FI", lat = 65.85, lon = 24.15)
        val mariehamn = feature(osmId = 3, street = "Torggatan", housenumber = "1", city = "Mariehamn", countrycode = "ax", lat = 60.10, lon = 19.94)
        val stockholm = GeoPoint(59.33, 18.07)
        server.enqueue(MockResponse().setBody(collection(haparanda, tornio, mariehamn)))

        // Sijainti Suomen ulkopuolella ei poista rajausta. Maarajaus myös palvelimelle: pelkkä bbox kattaa Pohjois-Ruotsin
        // rannikon, jolloin ruotsalaiset osumat täyttäisivät sivun ja Suomen ruotsinkieliset kadut jäisivät pois.
        assertEquals(listOf("osm:N:2", "osm:N:3"), finland().autocomplete("katu 1", stockholm).map { it.placeId })
        take().requestUrl!!.let {
            assertEquals(Defaults.FINLAND_BBOX, it.queryParameter("bbox"))
            assertEquals(listOf("FI", "AX"), it.queryParameterValues("countrycode"))
        }

        server.enqueue(MockResponse().setBody(collection(haparanda)))
        assertNull(finland().geocodeText("Storgatan 1, Haparanda", null))
        take().requestUrl!!.let {
            assertEquals(Defaults.FINLAND_BBOX, it.queryParameter("bbox"))
            assertEquals(listOf("FI", "AX"), it.queryParameterValues("countrycode"))
        }

        // Käänteishaku ei tunne countrycode-parametria (400), joten se rajataan vain tuloksista.
        server.enqueue(MockResponse().setBody(collection(haparanda)))
        assertNull(finland().reverse(GeoPoint(65.83, 24.13), maxDistanceM = 300.0))
        assertEquals(emptyList(), take().requestUrl!!.queryParameterValues("countrycode"))

        // Tulos ilman maakoodia ei kelpaa.
        server.enqueue(MockResponse().setBody(collection(feature(osmId = 4, street = "Kauppakatu", housenumber = "1", city = "Kemi", lat = 65.73, lon = 24.56))))
        assertEquals(emptyList(), finland().autocomplete("Kauppakatu 1", null))
        // Pienillä kirjaimilla annettu maajoukko toimii samoin.
        server.enqueue(MockResponse().setBody(collection(tornio)))
        val lowercase = PhotonClient(OkHttpClient(), baseUrl = server.url("/").toString().removeSuffix("/"), countries = setOf("fi"))
        assertEquals(listOf("osm:N:2"), lowercase.autocomplete("Hallituskatu 1", null).map { it.placeId })
    }

    @Test
    fun `poistuneen palvelun tunniste ei kaada tietojen hakua`() = runTest {
        val search = FallbackPlaceSearch(listOf(finland(), finland()))

        assertFailsWith<ApiException> { search.placeDetails("no:0301-12345") }
        assertFailsWith<ApiException> { search.placeDetails("ee:2280361") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ehdotus kantaa sijaintinsa ja reverse hakee lähimmän osoitteen`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature(street = "Karakaari", housenumber = "7", city = "Espoo", lon = 24.7561, lat = 60.2217))))
        assertEquals(GeoPoint(60.2217, 24.7561), client().autocomplete("Karakaari 7", null).single().location)
        take()

        server.enqueue(MockResponse().setBody(collection(feature(street = "Karakaari", housenumber = "7", city = "Espoo", lon = 24.7561, lat = 60.2217))))
        val details = client().reverse(GeoPoint(60.2218, 24.7569), maxDistanceM = 300.0)

        val url = take().requestUrl!!
        assertEquals("/reverse", url.encodedPath)
        assertEquals("60.2218", url.queryParameter("lat"))
        assertEquals("24.7569", url.queryParameter("lon"))
        assertEquals("1", url.queryParameter("limit"))
        // Vain talotaso: ilman rajausta Photon voi antaa kohteen, kadun tai kaupungin "osoitteena".
        assertEquals("house", url.queryParameter("layer"))
        assertEquals(PlaceDetails("osm:N:1", "Karakaari 7", "Karakaari 7, Espoo", GeoPoint(60.2217, 24.7561)), details)
    }

    @Test
    fun `reverse hylkää rajaa kauempana olevan osoitteen`() = runTest {
        // 60.18 on noin 1,1 km pohjoiseen pisteestä 60.17.
        server.enqueue(MockResponse().setBody(collection(feature(street = "Kauppakatu", housenumber = "1", city = "Espoo", lon = 24.94, lat = 60.18))))
        assertNull(client().reverse(GeoPoint(60.17, 24.94), maxDistanceM = 300.0))
    }

    @Test
    fun `autocomplete muotoilee katuosoitteen ja painottaa sijaintia voimakkaasti yhdellä pyynnöllä`() = runTest {
        // location_bias_scale=0 + zoom=14: Göteborgissa "Kungsgatan 10" antaa Göteborgin ennen Marstrandia.
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(osmId = 7, street = "Kaivokatu", housenumber = "1", postcode = "00100", city = "Helsinki", lon = 24.9412, lat = 60.1721),
                )
            )
        )

        val result = client().autocomplete("kaivok", GeoPoint(60.17, 24.94))

        assertEquals(1, result.size)
        assertEquals("osm:N:7", result[0].placeId)
        assertEquals("Kaivokatu 1", result[0].primaryText)
        assertEquals("00100 Helsinki", result[0].secondaryText)
        assertEquals("Photon · © OpenStreetMap", result[0].source)
        val path = take().path!!
        assertTrue(path.startsWith("/api/?q=kaivok&limit=8&bbox=19.0,59.7,31.6,70.1&lat=60.17&lon=24.94"), path)
        assertTrue(path.contains("location_bias_scale=0") && path.contains("zoom=14"), path)
        assertFalse(path.contains("lang="), path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `nimetty paikka saa kadun toiselle riville ja tiedot tulevat välimuistista`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(
                        osmType = "W", osmId = 9, name = "Päärautatieasema", street = "Kaivokatu", housenumber = "1",
                        postcode = "00100", city = "Helsinki", lon = 24.9406, lat = 60.1715,
                    ),
                )
            )
        )
        val photon = client()

        val suggestion = photon.autocomplete("päärauta").single()
        val details = photon.placeDetails(suggestion.placeId)

        assertEquals("Päärautatieasema", suggestion.primaryText)
        assertEquals("Kaivokatu 1, 00100 Helsinki", suggestion.secondaryText)
        assertEquals("osm:W:9", details.placeId)
        assertEquals("Päärautatieasema", details.name)
        assertEquals("Kaivokatu 1, 00100 Helsinki", details.address)
        assertEquals(GeoPoint(60.1715, 24.9406), details.location)
    }

    @Test
    fun `pelkkä kaupunki ei tuota tyhjää riviä ja tuplat karsitaan`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(osmId = 1, city = "Helsinki"),
                    feature(osmId = 1, city = "Helsinki"),
                    feature(osmId = 2),
                )
            )
        )

        val result = client().autocomplete("hel")

        assertEquals(1, result.size)
        assertEquals("Helsinki", result[0].primaryText)
    }

    @Test
    fun `tyhjä haku ei lähde verkkoon`() = runTest {
        assertEquals(emptyList(), client().autocomplete("   "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tuntematon placeId heittää ApiExceptionin`() = runTest {
        assertFailsWith<ApiException> { client().placeDetails("osm:N:404") }
    }

    @Test
    fun `geocodeText palauttaa parhaan osuman tai nullin`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(osmId = 3, street = "Mannerheimintie", housenumber = "10", postcode = "00100", city = "Helsinki"),
                )
            )
        )
        server.enqueue(MockResponse().setBody(collection()))
        val photon = client()

        val hit = photon.geocodeText("Mannerheimintie 10, Helsinki")
        val miss = photon.geocodeText("xyzzy")

        assertEquals("Mannerheimintie 10, 00100 Helsinki", hit!!.address)
        assertTrue(server.takeRequest().path!!.contains("limit=1"))
        assertNull(miss)
    }

    @Test
    fun `geocodeText hylkää pelkän alueen ja kaukaisen kadun, jota rivi ei mainitse`() = runTest {
        // Photon: "Olematon katu 5, Oulu" → 5th Cross, Ozhukarai (Intia); "Oulu" → kaupungin keskipiste.
        server.enqueue(MockResponse().setBody(collection(feature(osmId = 1, name = "Oulu", type = "city", lat = 65.01, lon = 25.47))))
        server.enqueue(MockResponse().setBody(collection(feature(osmId = 2, name = "5th Cross", city = "Ozhukarai", type = "street", lat = 11.95, lon = 79.77))))
        val photon = client()
        val oulu = GeoPoint(65.01, 25.47)

        assertNull(photon.geocodeText("Oulu", oulu))
        assertNull(photon.geocodeText("Olematon katu 5, Oulu", oulu))
    }

    @Test
    fun `geocodeText hyväksyy lähellä olevan osuman tai rivin mainitseman paikkakunnan`() = runTest {
        val hallituskatu = collection(feature(osmId = 3, street = "Hallituskatu", housenumber = "5", postcode = "90100", city = "Oulu", lat = 65.014, lon = 25.469))
        server.enqueue(MockResponse().setBody(hallituskatu))
        server.enqueue(MockResponse().setBody(hallituskatu))
        server.enqueue(MockResponse().setBody(hallituskatu))
        val photon = client()

        assertEquals("osm:N:3", photon.geocodeText("Hallituskatu 5", GeoPoint(65.01, 25.47))?.placeId)
        assertEquals("osm:N:3", photon.geocodeText("Hallituskatu 5, 90100", null)?.placeId)
        // Ei sijaintia eikä paikkakuntaa: osumaa ei voi varmistaa.
        assertNull(photon.geocodeText("Hallituskatu 5", null))
    }

    @Test
    fun `geocodeText vertaa paikkakuntaa kokonaisina sanoina ilman tarkkeita`() = runTest {
        // Iin Kirkkotie ei kelpaa Piikkiön riville, vaikka "ii" on sanan osa; "Jyvaskyla" on Jyväskylä.
        server.enqueue(MockResponse().setBody(collection(feature(osmId = 4, street = "Kirkkotie", housenumber = "2", city = "Ii", lat = 65.32, lon = 25.37))))
        server.enqueue(
            MockResponse().setBody(
                collection(feature(osmId = 5, street = "Kauppakatu", housenumber = "5", postcode = "40100", city = "Jyväskylä", lat = 62.24, lon = 25.75))
            )
        )
        server.enqueue(
            MockResponse().setBody(
                collection(feature(osmId = 6, street = "Asemakatu", housenumber = "3", city = "Mänttä-Vilppula", lat = 62.03, lon = 24.62))
            )
        )
        val photon = client()

        assertNull(photon.geocodeText("Kirkkotie 2, Piikkiö", null))
        assertEquals("osm:N:5", photon.geocodeText("Kauppakatu 5, Jyvaskyla", null)?.placeId)
        assertEquals("osm:N:6", photon.geocodeText("Asemakatu 3, 35800 Mänttä-Vilppula", null)?.placeId)
    }

    @Test
    fun `geocodeText vaatii rivin kadun ja talonumeron`() = runTest {
        // Photon: paikkakunta täsmää, mutta katu tai talo on väärä.
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(
                        osmType = "W", osmId = 326041078, name = "Piikkiön rautatieasema", street = "Asematie", housenumber = "1",
                        postcode = "21500", city = "Piikkiö", lat = 60.4232904, lon = 22.5157345,
                    )
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(
                        osmType = "R", osmId = 11640160, name = "Kauppakeskus Valkea", street = "Kauppurienkatu", postcode = "90100",
                        city = "Oulu", lat = 65.0115206, lon = 25.472619,
                    )
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(
                        osmType = "W", osmId = 866390786, name = "Oulun jalkapallohalli", street = "Isokatu", housenumber = "102",
                        postcode = "90120", city = "Oulu", lat = 65.0007184, lon = 25.4541289,
                    )
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(
                        osmId = 8652105479, name = "Byströmin Ohjaamo Oulu", street = "Hallituskatu", housenumber = "5",
                        postcode = "90100", city = "Oulu", lat = 65.0145634, lon = 25.4691955,
                    )
                )
            )
        )
        val photon = client()
        val oulu = GeoPoint(65.0121, 25.4651)

        assertNull(photon.geocodeText("Hakatie 2, 21500 Piikkiö", GeoPoint(60.27, 24.86)))
        assertNull(photon.geocodeText("Kauppak. 5, Oulu", oulu))
        assertNull(photon.geocodeText("Isok. 10, Oulu", oulu))
        // Yritys ensin: katu löytyy rivin toisesta osasta.
        assertEquals("osm:N:8652105479", photon.geocodeText("Yritys Oy, Hallituskatu 5, 90100 Oulu", oulu)?.placeId)
    }

    @Test
    fun `samanniminen ketjukohde ei kelpaa väärältä kadulta, vaikka nimi osuu`() = runTest {
        // Photon antaa ketjun toisen toimipisteen, vaikka rivillä on toinen katuosoite.
        val rKioski = feature(osmId = 51, name = "R-kioski", street = "Isokatu", housenumber = "20", postcode = "90100", city = "Oulu", lat = 65.012, lon = 25.47)
        val rema = feature(osmId = 52, name = "Rema 1000", street = "Sjøgata", housenumber = "5", postcode = "9008", city = "Tromsø", lat = 69.65, lon = 18.96)
        listOf(rKioski, rema, rKioski, rKioski).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()
        val oulu = GeoPoint(65.0121, 25.4651)

        assertNull(photon.geocodeText("R-kioski, Kauppakatu 5, Oulu", oulu))
        assertNull(photon.geocodeText("Rema 1000, Storgata 10, 9008 Tromsø", null))
        // Ilman pilkkuja katuosoite on silti rivillä.
        assertNull(photon.geocodeText("R-kioski Kauppakatu 5 Oulu", oulu))
        // Sama ketjukohde oikealla kadulla kelpaa, ja talonumero tarkistetaan kuten ennen.
        assertEquals("osm:N:51", photon.geocodeText("R-kioski, Isokatu 20, Oulu", oulu)?.placeId)
    }

    @Test
    fun `pelkkä nimi ilman katuosoitetta kelpaa yhä`() = runTest {
        val rKioski = feature(osmId = 53, name = "R-kioski", street = "Mannerheimintie", housenumber = "1", postcode = "00100", city = "Helsinki")
        val ohjaamo = feature(osmId = 54, name = "Byströmin Ohjaamo Oulu", street = "Hallituskatu", housenumber = "5", postcode = "90100", city = "Oulu", lat = 65.0146, lon = 25.4692)
        // Pelkkä katu ilman taloa: Photon antaa kadun nimen name-kentässä.
        val hallituskatu = feature(osmId = 55, name = "Hallituskatu", postcode = "90100", city = "Oulu", type = "street", lat = 65.0146, lon = 25.4692)
        listOf(rKioski, rKioski, ohjaamo, hallituskatu).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        assertEquals("osm:N:53", photon.geocodeText("R-kioski, 00100 Helsinki", null)?.placeId)
        // Rivin postinumero on eri kuin tuloksen: viisinumeroinen ei ole talonumero.
        assertEquals("osm:N:53", photon.geocodeText("R-kioski, 00120 Helsinki", null)?.placeId)
        assertEquals("osm:N:54", photon.geocodeText("Byströmin Ohjaamo Oulu", null)?.placeId)
        assertEquals("osm:N:55", photon.geocodeText("Hallituskatu 5, Oulu", null)?.placeId)
    }

    @Test
    fun `kadun nimessä oleva numero ei ole rivin talonumero`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(osmId = 7, street = "Straße des 17. Juni", housenumber = "135", postcode = "10623", city = "Berlin", lat = 52.5127, lon = 13.3266)
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                collection(
                    feature(osmId = 8, street = "Avenue du 8 Mai 1945", housenumber = "10", postcode = "93270", city = "Sevran", lat = 48.9386, lon = 2.5277)
                )
            )
        )
        val photon = client()

        assertEquals("osm:N:7", photon.geocodeText("Straße des 17. Juni 135, Berlin", null)?.placeId)
        assertEquals("osm:N:8", photon.geocodeText("Avenue du 8 Mai 1945 10, Sevran", null)?.placeId)
    }

    @Test
    fun `postinumero ei ole talonumero ja talonumero voi olla kadun edellä`() = runTest {
        // Photon löytää oikean talon, vaikka rivillä ei ole pilkkua ennen postinumeroa.
        val rivoli = collection(
            feature(osmId = 9, street = "Rue de Rivoli", housenumber = "10", postcode = "75004", city = "Paris", lat = 48.8557, lon = 2.3591)
        )
        val kungsgatan = collection(
            feature(osmId = 10, street = "Kungsgatan", housenumber = "10", postcode = "411 19", city = "Göteborg", lat = 57.7032, lon = 11.9593)
        )
        repeat(4) { server.enqueue(MockResponse().setBody(rivoli)) }
        repeat(2) { server.enqueue(MockResponse().setBody(kungsgatan)) }
        val photon = client()

        assertEquals("osm:N:9", photon.geocodeText("10 Rue de Rivoli 75004 Paris", null)?.placeId)
        assertEquals("osm:N:9", photon.geocodeText("Rue de Rivoli 10 75004 Paris", null)?.placeId)
        // Kadun perässä oleva asunto ei ole talonumero, kun numero on kadun edellä.
        assertEquals("osm:N:9", photon.geocodeText("10 Rue de Rivoli Apt 2 75004 Paris", null)?.placeId)
        assertNull(photon.geocodeText("12 Rue de Rivoli 75004 Paris", null))
        // Kaksiosainen postinumero ei ole talonumero; numeroidun listarivin talonumero on kadun perässä.
        assertEquals("osm:N:10", photon.geocodeText("Kungsgatan 411 19 Göteborg", null)?.placeId)
        assertEquals("osm:N:10", photon.geocodeText("3. Kungsgatan 10 411 19 Göteborg", null)?.placeId)
    }

    @Test
    fun `talonumeron merkintä, puuttuva postinumero ja numeroväli eivät hylkää oikeaa taloa`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                collection(feature(osmId = 11, street = "Hauptstraße", housenumber = "5", postcode = "10115", city = "Berlin", lat = 52.53, lon = 13.38))
            )
        )
        server.enqueue(
            MockResponse().setBody(
                collection(feature(osmId = 12, street = "Calle Mayor", housenumber = "10", postcode = "28013", city = "Madrid", lat = 40.4155, lon = -3.7074))
            )
        )
        // Photonin tuloksesta puuttuu postinumero: kadun perässä oleva viisinumeroinen on silti postinumero.
        val noPostcode = collection(feature(osmId = 13, street = "Rue de Rivoli", housenumber = "10", city = "Paris", lat = 48.8557, lon = 2.3591))
        repeat(2) { server.enqueue(MockResponse().setBody(noPostcode)) }
        val photon = client()

        assertEquals("osm:N:11", photon.geocodeText("3. Hauptstraße Nr. 5 10115 Berlin", null)?.placeId)
        assertEquals("osm:N:12", photon.geocodeText("1. Calle Mayor nº 10 28013 Madrid", null)?.placeId)
        assertEquals("osm:N:13", photon.geocodeText("10 Rue de Rivoli 75004 Paris", null)?.placeId)
        assertEquals("osm:N:13", photon.geocodeText("10-12 Rue de Rivoli 75004 Paris", null)?.placeId)
    }

    @Test
    fun `nelinumeroinen talonumero ja väliviivallinen postinumero eivät sekoita talonumeroa`() = runTest {
        // Maaseudun etäisyysperusteinen talonumero on usein nelinumeroinen: listanumero ei ole talonumero.
        val iijoentie = collection(feature(osmId = 21, street = "Iijoentie", housenumber = "1234", postcode = "91100", city = "Ii", lat = 65.3, lon = 25.6))
        val wrongHouse = collection(feature(osmId = 22, street = "Iijoentie", housenumber = "3", postcode = "91100", city = "Ii", lat = 65.32, lon = 25.37))
        val kemintie = collection(feature(osmId = 23, street = "Kemintie", housenumber = "1150", postcode = "95700", city = "Pello", lat = 66.77, lon = 23.96))
        val warszawa = collection(feature(osmId = 24, street = "Marszałkowska", housenumber = "10", postcode = "00-950", city = "Warszawa", lat = 52.23, lon = 21.01))
        val lisboa = collection(feature(osmId = 25, street = "Rua Augusta", housenumber = "10", postcode = "1100-048", city = "Lisboa", lat = 38.71, lon = -9.14))
        listOf(iijoentie, wrongHouse, kemintie, warszawa, lisboa).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertEquals("osm:N:21", photon.geocodeText("3. Iijoentie 1234, 91100 Ii", null)?.placeId)
        assertNull(photon.geocodeText("3. Iijoentie 1234, 91100 Ii", null))
        assertEquals("osm:N:23", photon.geocodeText("Huolto 24 Kemintie 1150 95700 Pello", null)?.placeId)
        // Väliviivallinen postinumero ilman talonumeroa ei ole talonumero; "n.º" on talonumeron merkintä.
        assertEquals("osm:N:24", photon.geocodeText("ul. Marszałkowska 00-950 Warszawa", null)?.placeId)
        assertEquals("osm:N:25", photon.geocodeText("1. Rua Augusta n.º 10 1100-048 Lisboa", null)?.placeId)
    }

    @Test
    fun `postinumeron kanssa samanarvoinen talonumero säilyy pilkuin erotetussa rivissä`() = runTest {
        // Reykjavíkissa talonumero voi olla sama kuin postinumero: vain postinumeron osa poistetaan.
        val house102 = collection(feature(osmId = 31, street = "Hverfisgata", housenumber = "102", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93))
        val house101 = collection(feature(osmId = 32, street = "Hverfisgata", housenumber = "101", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93))
        server.enqueue(MockResponse().setBody(house102))
        server.enqueue(MockResponse().setBody(house101))
        val photon = client()

        assertNull(photon.geocodeText("Hverfisgata 101, 101 Reykjavík", null))
        assertEquals("osm:N:32", photon.geocodeText("Hverfisgata 101, 101 Reykjavík", null)?.placeId)
    }

    @Test
    fun `postinumeron kanssa samanarvoinen talonumero säilyy, kun rivillä ei ole postinumeroa`() = runTest {
        // Ainoa esiintymä on postinumero vain paikkakunnan edellä; kadun vieressä se on talonumero.
        val house102 = feature(osmId = 33, street = "Hverfisgata", housenumber = "102", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        val house150 = feature(osmId = 34, street = "Laugavegur", housenumber = "150", postcode = "105", city = "Reykjavík", lat = 64.14, lon = -21.92)
        val house101 = feature(osmId = 35, street = "Hverfisgata", housenumber = "101", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        listOf(house102, house150, house102, house101, house101).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        assertNull(photon.geocodeText("Hverfisgata 101, Reykjavík", null))
        assertNull(photon.geocodeText("Laugavegur 105, Reykjavík", null))
        // Talonumero kadun edellä: perässä oleva sana on katu eikä paikkakunta.
        assertNull(photon.geocodeText("101 Hverfisgata, Reykjavík", null))
        assertEquals("osm:N:35", photon.geocodeText("Hverfisgata 101, Reykjavík", null)?.placeId)
        // Kaksi esiintymää ilman pilkkua: jälkimmäinen on postinumero.
        assertEquals("osm:N:35", photon.geocodeText("Hverfisgata 101 101 Reykjavík", null)?.placeId)
    }

    @Test
    fun `kaksiosainen postinumero ei ole talonumero eikä porraskirjain paikkakunta`() = runTest {
        val kungsgatan = feature(osmId = 36, street = "Kungsgatan", housenumber = "10", postcode = "411 19", city = "Göteborg", lat = 57.70, lon = 11.96)
        val house102 = feature(osmId = 37, street = "Hverfisgata", housenumber = "102", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        listOf(kungsgatan, house102).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        // Pelkkä katu ja postinumero: kaksiosainen luku on postinumero eikä talonumero ja asunto.
        assertEquals("osm:N:36", photon.geocodeText("Kungsgatan 411 19", null)?.placeId)
        // Porraskirjain talonumeron perässä ei ole paikkakunta: 101 on talonumero.
        assertNull(photon.geocodeText("Hverfisgata 101 A, Reykjavík", null))
    }

    @Test
    fun `osan lopussa oleva postinumero ei ole talonumero, kun talonumero on kadun edellä`() = runTest {
        // Listarivi ilman pilkkua: talonumero kadun edellä ja nelinumeroinen postinumero perässä.
        val storgata = feature(osmId = 41, street = "Storgata", housenumber = "10", postcode = "9008", city = "Tromsø", lat = 69.65, lon = 18.96)
        val house101 = feature(osmId = 42, street = "Hverfisgata", housenumber = "101", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        listOf(storgata, storgata, house101).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        assertEquals("osm:N:41", photon.geocodeText("10 Storgata 9008", null)?.placeId)
        assertNull(photon.geocodeText("12 Storgata 9008", null))
        // Kolminumeroinen luku kadun perässä on talonumero (Islanti), myös listan numeron jälkeen.
        assertEquals("osm:N:42", photon.geocodeText("3. Hverfisgata 101", null)?.placeId)
    }

    @Test
    fun `paikkakunnan edellä oleva postinumero on postinumero, vaikka sama luku on myöhemmin talonumerona`() = runTest {
        val house102 = feature(osmId = 43, street = "Hverfisgata", housenumber = "102", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        val house101 = feature(osmId = 44, street = "Hverfisgata", housenumber = "101", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        listOf(house102, house101).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        assertNull(photon.geocodeText("101 Reykjavík, Hverfisgata 101", null))
        assertEquals("osm:N:44", photon.geocodeText("101 Reykjavík, Hverfisgata 101", null)?.placeId)
    }

    @Test
    fun `kadun edellä on talonumero vain numerona, ja pelkkä postinumero-osa on postinumero`() = runTest {
        val house102 = feature(osmId = 45, street = "Hverfisgata", housenumber = "102", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        val house101 = feature(osmId = 46, street = "Hverfisgata", housenumber = "101", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        val storgata = feature(osmId = 47, street = "Storgata", housenumber = "10", postcode = "9008", city = "Tromsø", lat = 69.65, lon = 18.96)
        val house24 = feature(osmId = 48, street = "Hverfisgata", housenumber = "24", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        val storgata3 = feature(osmId = 49, street = "Storgata", housenumber = "3", postcode = "9008", city = "Tromsø", lat = 69.65, lon = 18.96)
        val storgata12 = feature(osmId = 50, street = "Storgata", housenumber = "12", postcode = "9008", city = "Tromsø", lat = 69.65, lon = 18.96)
        listOf(house102, house102, storgata, storgata, house102, house101, house102, house102, house24, house101, storgata3, storgata12)
            .forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        // Kadun edellä ei ole numeroa: perässä oleva luku on talonumero.
        assertNull(photon.geocodeText("Bónus Hverfisgata 101", null))
        // Kellarimerkintä ("kjallari") talonumeron perässä ei ole paikkakunta: postinumero on vain paikkakunnan edellä.
        assertNull(photon.geocodeText("Hverfisgata 101 kjallari, 101 Reykjavík", null))
        // Numero kadun edellä on talonumero myös listan numeron tai kohteen nimen jälkeen.
        assertEquals("osm:N:47", photon.geocodeText("3. 10 Storgata 9008", null)?.placeId)
        assertEquals("osm:N:47", photon.geocodeText("Bónus 10 Storgata 9008", null)?.placeId)
        // Pelkkä postinumero omana osanaan ei vie kadun talonumeroa.
        assertNull(photon.geocodeText("101, Hverfisgata 101", null))
        assertEquals("osm:N:46", photon.geocodeText("101, Hverfisgata 101", null)?.placeId)
        assertNull(photon.geocodeText("Hverfisgata 101 Reykjavík, 101", null))
        // Jos mikään esiintymä ei ole postinumeron paikalla, kadun vieressä oleva luku on talonumero.
        assertNull(photon.geocodeText("Reykjavík 101, Hverfisgata 101", null))
        // Kolminumeroinen luku kadun perässä on talonumero, vaikka kadun edellä on kohteen nimen numero.
        assertNull(photon.geocodeText("Studio 24 Hverfisgata 101", null))
        assertEquals("osm:N:46", photon.geocodeText("Studio 24 Hverfisgata 101", null)?.placeId)
        // Rivin alun listan numero ei ole talonumero, vaikka Photon tulkitsee sen sellaiseksi.
        assertNull(photon.geocodeText("3. Storgata 9008", null))
        // Ilman numeroa kadun edellä perässä oleva luku on talonumero myös nelinumeroisena.
        assertNull(photon.geocodeText("Bónus Storgata 9008", null))
    }

    @Test
    fun `kerros- tai rakennusmerkintä talonumeron perässä ei ole paikkakunta`() = runTest {
        // "kjallari" (kellari) ja "bakhús" (takarakennus) eivät ole paikkakuntia, joten 101 on
        // talonumero; rivin lopun 101 kaupungin perässä ei ole postinumeron paikalla, eikä se vie talonumeroa.
        val house102 = feature(osmId = 61, street = "Hverfisgata", housenumber = "102", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        val house101 = feature(osmId = 62, street = "Hverfisgata", housenumber = "101", postcode = "101", city = "Reykjavík", lat = 64.14, lon = -21.93)
        listOf(house102, house101, house102, house101, house102).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        assertNull(photon.geocodeText("Hverfisgata 101 kjallari, Reykjavík 101", null))
        assertEquals("osm:N:62", photon.geocodeText("Hverfisgata 101 kjallari, Reykjavík 101", null)?.placeId)
        assertNull(photon.geocodeText("Hverfisgata 101 bakhús, Reykjavík 101", null))
        assertEquals("osm:N:62", photon.geocodeText("Hverfisgata 101 bakhús, Reykjavík 101", null)?.placeId)
        // Asunnon merkintä samoin.
        assertNull(photon.geocodeText("Hverfisgata 101 apt 2, Reykjavík 101", null))
    }

    @Test
    fun `postinumeron perässä oleva kylä, kaupunginosa tai maakunta on tuloksen paikkakunta`() = runTest {
        // Photonin city on kunta; rivillä voi olla kylä (locality), kaupunginosa (district), seutukunta (county),
        // maakunta (state) tai maa (country). Tuotannossa Photon antaa maan aina, joten testin tulos kertoo sen.
        val kuivaniemi = feature(
            osmId = 63, street = "Kirkkotie", housenumber = "2", postcode = "95100", city = "Ii", locality = "Kuivaniemi",
            state = "Pohjois-Pohjanmaa", country = "Suomi", countrycode = "FI", lat = 65.58, lon = 25.18,
        )
        val kluuvi = feature(
            osmId = 64, street = "Mannerheimintie", housenumber = "10", postcode = "00100", city = "Helsinki", district = "Kluuvi",
            county = "Helsingin seutukunta", lat = 60.17, lon = 24.94,
        )
        listOf(kuivaniemi, kuivaniemi, kuivaniemi, kluuvi, kluuvi).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        // Postinumero poistuu, eikä rivillä ole talonumeroa: kadun talo kelpaa.
        assertEquals("osm:N:63", photon.geocodeText("Kirkkotie 95100 Kuivaniemi", null)?.placeId)
        assertEquals("osm:N:63", photon.geocodeText("Kirkkotie 95100 Pohjois-Pohjanmaa", null)?.placeId)
        // Maan nimi viisinumeroisen postinumeron perässä on paikkakuntatieto: rivi kelpaa kuten kylän kanssa.
        assertEquals("osm:N:63", photon.geocodeText("Kirkkotie 95100 Suomi", null)?.placeId)
        assertEquals("osm:N:64", photon.geocodeText("Mannerheimintie 00100 Kluuvi", null)?.placeId)
        assertEquals("osm:N:64", photon.geocodeText("Mannerheimintie 00100 Helsingin seutukunta", null)?.placeId)
    }

    @Test
    fun `postinumeron perässä oleva maan nimi on tuloksen paikkakuntatieto`() = runTest {
        // Photon antaa maan nimen pyydetyllä kielellä (country), mutta rivillä se voi olla toisella kielellä (Suomi,
        // Finland): maakoodin taulukko kattaa kielet.
        val torggatan = feature(
            osmId = 65, street = "Torggatan", housenumber = "10", postcode = "22100", city = "Mariehamn", country = "Suomi",
            countrycode = "FI", lat = 60.10, lon = 19.94,
        )
        val torggatan12 = feature(
            osmId = 66, street = "Torggatan", housenumber = "12", postcode = "22100", city = "Mariehamn", country = "Suomi",
            countrycode = "FI", lat = 60.10, lon = 19.94,
        )
        listOf(torggatan, torggatan12, torggatan)
            .forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        // Maan nimi vain englanniksi, vaikka Photon antoi sen suomeksi.
        assertEquals("osm:N:65", photon.geocodeText("Torggatan 10 22100 Finland", null)?.placeId)
        assertNull(photon.geocodeText("Torggatan 10 22100 Finland", null))
        assertEquals("osm:N:65", photon.geocodeText("Torggatan 10 22100 Mariehamn, Suomi", null)?.placeId)
    }

    @Test
    fun `maan nimi kelpaa paikkakuntatiedoksi postinumeron perässä`() = runTest {
        // Rivillä on vain postinumero ja maan nimi englanniksi: paikkakunnan tieto tulee maakoodin taulukosta.
        server.enqueue(MockResponse().setBody(collection(feature(
            osmId = 70, street = "Kirkkotie", housenumber = "5", postcode = "95100", city = "Kuivaniemi", country = "Suomi",
            countrycode = "FI", lat = 65.58, lon = 25.18,
        ))))

        assertEquals("osm:N:70", client().geocodeText("Kirkkotie 95100 Finland", null)?.placeId)
    }

    @Test
    fun `geocodeText painottaa sijaintia`() = runTest {
        server.enqueue(MockResponse().setBody(collection()))

        client().geocodeText("Storgatan 1", GeoPoint(63.83, 20.26))

        val url = server.takeRequest().requestUrl!!
        assertEquals("63.83", url.queryParameter("lat"))
        assertEquals("20.26", url.queryParameter("lon"))
    }

    @Test
    fun `palvelinvirhe nousee ApiExceptionina koodin kanssa`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))

        val e = assertFailsWith<ApiException> { client().autocomplete("abc") }
        assertEquals(503, e.httpCode)
    }
}
