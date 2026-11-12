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
        // location_bias_scale=0 + zoom=14: kaupungissa haettu katu löytyy omasta kaupungista ennen naapurikuntaa.
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
        val halli = feature(osmId = 52, name = "Halli 1000", street = "Rantakatu", housenumber = "5", postcode = "90100", city = "Oulu", lat = 65.013, lon = 25.46)
        listOf(rKioski, halli, rKioski, rKioski).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()
        val oulu = GeoPoint(65.0121, 25.4651)

        assertNull(photon.geocodeText("R-kioski, Kauppakatu 5, Oulu", oulu))
        assertNull(photon.geocodeText("Halli 1000, Kauppakatu 10, 90100 Oulu", null))
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
        val house12 = collection(feature(osmId = 7, street = "3. linja", housenumber = "12", postcode = "00530", city = "Helsinki"))
        val house14 = collection(feature(osmId = 8, street = "3. linja", housenumber = "14", postcode = "00530", city = "Helsinki"))
        listOf(house12, house14).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertEquals("osm:N:7", photon.geocodeText("3. linja 12, Helsinki", null)?.placeId)
        assertNull(photon.geocodeText("3. linja 12, Helsinki", null))
    }

    @Test
    fun `tuloksen postinumero ei ole rivin talonumero`() = runTest {
        // Rivi kelpaa ilman pilkkuakin: tuloksen postinumero tunnistetaan rivistä.
        val house10 = collection(feature(osmId = 9, street = "Mannerheimintie", housenumber = "10", postcode = "00100", city = "Helsinki"))
        // Photonin postinumero voi olla eri kuin rivin (naapurialue) tai puuttua kokonaan.
        val otherPostcode = collection(feature(osmId = 10, street = "Mannerheimintie", housenumber = "10", postcode = "00101", city = "Helsinki"))
        val noPostcode = collection(feature(osmId = 11, street = "Mannerheimintie", housenumber = "10", city = "Helsinki"))
        listOf(house10, house10, otherPostcode, otherPostcode, noPostcode).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertEquals("osm:N:9", photon.geocodeText("Mannerheimintie 10 00100 Helsinki", null)?.placeId)
        assertNull(photon.geocodeText("Mannerheimintie 12 00100 Helsinki", null))
        // Tuntematon viisinumeroinen luku kadun perässä voi olla talonumero: epäselvä rivi hylätään.
        assertNull(photon.geocodeText("Mannerheimintie 00100 Helsinki", null))
        assertEquals("osm:N:10", photon.geocodeText("Mannerheimintie 10, 00100 Helsinki", null)?.placeId)
        assertEquals("osm:N:11", photon.geocodeText("Mannerheimintie 10 00100 Helsinki", null)?.placeId)
    }

    @Test
    fun `talonumero on kadun perässä, vaikka edellä on listan tai kohteen nimen numero`() = runTest {
        // Maaseudun etäisyysperusteinen talonumero on usein nelinumeroinen.
        val iijoentie = collection(feature(osmId = 21, street = "Iijoentie", housenumber = "1234", postcode = "91100", city = "Ii", lat = 65.3, lon = 25.6))
        val wrongHouse = collection(feature(osmId = 22, street = "Iijoentie", housenumber = "3", postcode = "91100", city = "Ii", lat = 65.32, lon = 25.37))
        val kemintie = collection(feature(osmId = 23, street = "Kemintie", housenumber = "1150", postcode = "95700", city = "Pello", lat = 66.77, lon = 23.96))
        listOf(iijoentie, wrongHouse, iijoentie, kemintie, wrongHouse).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertEquals("osm:N:21", photon.geocodeText("3. Iijoentie 1234, 91100 Ii", null)?.placeId)
        assertNull(photon.geocodeText("3. Iijoentie 1234, 91100 Ii", null))
        // Ilman kadun perässä olevaa numeroa edellä oleva numero tarkistetaan: väärä talo ei kelpaa.
        assertNull(photon.geocodeText("12 Iijoentie, 91100 Ii", null))
        assertEquals("osm:N:23", photon.geocodeText("Huolto 24 Kemintie 1150 95700 Pello", null)?.placeId)
        // Nelinumeroinen luku on talonumero myös ilman postinumeroa.
        assertNull(photon.geocodeText("Iijoentie 1234, Ii", null))
    }

    @Test
    fun `talonumeron merkintä ja numeroväli eivät hylkää oikeaa taloa`() = runTest {
        val house5 = collection(feature(osmId = 31, street = "Kauppakatu", housenumber = "5", postcode = "90100", city = "Oulu", lat = 65.01, lon = 25.47))
        val house7 = collection(feature(osmId = 32, street = "Kauppakatu", housenumber = "7", postcode = "90100", city = "Oulu", lat = 65.01, lon = 25.47))
        listOf(house5, house5, house5, house7).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertEquals("osm:N:31", photon.geocodeText("Kauppakatu nro 5, 90100 Oulu", null)?.placeId)
        // Numeroväli alkunsa mukaan.
        assertEquals("osm:N:31", photon.geocodeText("Kauppakatu 5-7, 90100 Oulu", null)?.placeId)
        assertEquals("osm:N:31", photon.geocodeText("2. Kauppakatu 5–7 90100 Oulu", null)?.placeId)
        assertNull(photon.geocodeText("Kauppakatu 5-7, 90100 Oulu", null))
    }

    @Test
    fun `kauempana kadun perässä oleva numero on talonumero`() = runTest {
        val house7 = collection(feature(osmId = 33, street = "Kauppakatu", housenumber = "7", postcode = "90100", city = "Oulu", lat = 65.01, lon = 25.47))
        val house5 = collection(feature(osmId = 34, street = "Kauppakatu", housenumber = "5", postcode = "90100", city = "Oulu", lat = 65.01, lon = 25.47))
        listOf(house7, house5).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        // Kadun ja numeron välissä on muu sana kuin talonumeron merkintä: numero ratkaisee silti talon.
        assertNull(photon.geocodeText("Kauppakatu rakennus 5, 90100 Oulu", null))
        assertEquals("osm:N:34", photon.geocodeText("Kauppakatu rakennus 5, 90100 Oulu", null)?.placeId)
    }

    @Test
    fun `rivin alun listan numero ei tee nimirivistä katuosoitetta`() = runTest {
        // Photon tuntee kohteen vain nimellä: listan numero ja sen perässä oleva sana eivät ole toinen katuosoite.
        val rKioski = collection(feature(osmId = 35, name = "R-kioski", street = "Isokatu", housenumber = "20", postcode = "90100", city = "Oulu", lat = 65.012, lon = 25.47))
        listOf(rKioski, rKioski, rKioski).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertEquals("osm:N:35", photon.geocodeText("3. Nouto R-kioski, Oulu", null)?.placeId)
        // Ilman listan numeroa sama luku olisi katuosoitteen talonumero.
        assertNull(photon.geocodeText("Noutokatu 3 R-kioski, Oulu", null))
        // Nimen edellä oleva numero tarkistetaan talonumerona: väärä talo ei kelpaa.
        assertNull(photon.geocodeText("5 R-kioski, Oulu", null))
    }

    @Test
    fun `viisinumeroinen talonumero ei ole postinumero`() = runTest {
        // Käsivarrentien talonumerot ovat viisinumeroisia: väärä talo ei kelpaa, vaikka rivillä on myös postinumero.
        val wrong = collection(feature(osmId = 41, street = "Käsivarrentie", housenumber = "14206", postcode = "99490", city = "Enontekiö", lat = 69.04, lon = 20.80))
        val right = collection(feature(osmId = 42, street = "Käsivarrentie", housenumber = "14622", postcode = "99490", city = "Enontekiö", lat = 69.05, lon = 20.79))
        // Photonin tuloksesta voi puuttua postinumero: kadun perässä oleva luku on silti talonumero.
        val rightNoPostcode = collection(feature(osmId = 40, street = "Käsivarrentie", housenumber = "14622", city = "Enontekiö", lat = 69.05, lon = 20.79))
        listOf(wrong, wrong, wrong, right, right, wrong, rightNoPostcode).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertNull(photon.geocodeText("Käsivarrentie 14622, 99490 Kilpisjärvi", null))
        assertNull(photon.geocodeText("Käsivarrentie 14622 99490 Kilpisjärvi", null))
        // Ilman postinumeroa ainoa viisinumeroinen on talonumero, kun tuloksen talonumero on viisinumeroinen.
        assertNull(photon.geocodeText("Käsivarrentie 14622, Enontekiö", null))
        assertEquals("osm:N:42", photon.geocodeText("Käsivarrentie 14622, 99490 Kilpisjärvi", null)?.placeId)
        assertEquals("osm:N:42", photon.geocodeText("Käsivarrentie 14622, Enontekiö", null)?.placeId)
        // Pelkkä postinumero ilman talonumeroa: kadun talo kelpaa.
        assertEquals("osm:N:41", photon.geocodeText("Käsivarrentie 99490 Kilpisjärvi", null)?.placeId)
        assertEquals("osm:N:40", photon.geocodeText("Käsivarrentie 14622 99490 Enontekiö", null)?.placeId)
    }

    @Test
    fun `postinumero ennen katuosoitetta ei vie viisinumeroista talonumeroa`() = runTest {
        // Photonin postinumero puuttuu tai on toinen: kadun perässä oleva luku on silti talonumero, koska rivillä on
        // toinen viisinumeroinen luku postinumerona.
        val wrongNoPostcode = collection(feature(osmId = 36, street = "Käsivarrentie", housenumber = "14624", city = "Enontekiö", lat = 69.05, lon = 20.79))
        val wrongOtherPostcode = collection(feature(osmId = 37, street = "Käsivarrentie", housenumber = "14624", postcode = "99400", city = "Enontekiö", lat = 69.05, lon = 20.79))
        val rightNoPostcode = collection(feature(osmId = 38, street = "Käsivarrentie", housenumber = "14622", city = "Enontekiö", lat = 69.05, lon = 20.79))
        // Väärä talo voi olla myös lyhyempi numero.
        val shortHouse = collection(feature(osmId = 30, street = "Käsivarrentie", housenumber = "146", postcode = "99490", city = "Enontekiö", lat = 69.05, lon = 20.79))
        listOf(wrongNoPostcode, wrongOtherPostcode, rightNoPostcode, wrongNoPostcode, shortHouse).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertNull(photon.geocodeText("99490, Enontekiö, Käsivarrentie 14622", null))
        assertNull(photon.geocodeText("99490, Enontekiö, Käsivarrentie 14622", null))
        assertEquals("osm:N:38", photon.geocodeText("99490, Enontekiö, Käsivarrentie 14622", null)?.placeId)
        assertNull(photon.geocodeText("99490 Enontekiö Käsivarrentie 14622", null))
        assertNull(photon.geocodeText("Käsivarrentie 14622, 99490 Kilpisjärvi", null))
    }

    @Test
    fun `viisinumeroinen talonumero ei kelpaa lyhyemmälle talolle eikä asunnon numerolle`() = runTest {
        val shorter = collection(feature(osmId = 27, street = "Käsivarrentie", housenumber = "1462", postcode = "99490", city = "Enontekiö", lat = 69.05, lon = 20.79))
        val shorterNoPostcode = collection(feature(osmId = 28, street = "Käsivarrentie", housenumber = "1462", city = "Enontekiö", lat = 69.05, lon = 20.79))
        val house3 = collection(feature(osmId = 29, street = "Käsivarrentie", housenumber = "3", postcode = "99490", city = "Enontekiö", lat = 69.05, lon = 20.79))
        listOf(shorter, shorterNoPostcode, house3, house3).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertNull(photon.geocodeText("Käsivarrentie 14622, Enontekiö", null))
        assertNull(photon.geocodeText("Käsivarrentie 14622, Enontekiö", null))
        assertNull(photon.geocodeText("Käsivarrentie 14622 A 3, Enontekiö", null))
        assertNull(photon.geocodeText("Käsivarrentie 14620-14622, Enontekiö", null))
    }

    @Test
    fun `nimirivin vieras postinumero hylkää ketjun toisen toimipisteen`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature(osmId = 26, name = "K-Market", street = "Kaukovainiontie", housenumber = "12", postcode = "90460", city = "Oulu", lat = 64.99, lon = 25.53))))

        assertNull(client().geocodeText("K-Market 90100 Oulu", null))
    }

    @Test
    fun `sana numero on talonumeron merkintä`() = runTest {
        // Photon tuntee kadun vain nimellä: "numero" kadun ja talonumeron välissä ei tee rivistä toista katuosoitetta.
        val street = collection(feature(osmId = 39, name = "Kauppakatu", postcode = "90100", city = "Oulu", type = "street", lat = 65.01, lon = 25.47))
        listOf(street, street).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertEquals("osm:N:39", photon.geocodeText("Kauppakatu numero 5, Oulu", null)?.placeId)
        assertEquals("osm:N:39", photon.geocodeText("Kauppakatu num. 5, Oulu", null)?.placeId)
    }

    @Test
    fun `kadun nimen viisinumeroinen numero ei ole postinumero`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature(osmId = 43, street = "Tie 18751", housenumber = "15", postcode = "91100", city = "Ii", lat = 65.3, lon = 25.6))))

        assertEquals("osm:N:43", client().geocodeText("Tie 18751 15, Ii", null)?.placeId)
    }

    @Test
    fun `kadun nimen numeron pitää täsmätä tarkasti`() = runTest {
        // Kirjoitusvirhe sallitaan nimen kirjaimissa, ei numeroissa: viereinen katu on eri katu.
        val fourth = collection(feature(osmId = 44, street = "4. linja", housenumber = "12", postcode = "00530", city = "Helsinki"))
        val road867 = collection(feature(osmId = 45, street = "Tie 867", housenumber = "15", postcode = "91100", city = "Ii", lat = 65.3, lon = 25.6))
        listOf(fourth, road867).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        assertNull(photon.geocodeText("3. linja 12, Helsinki", null))
        assertNull(photon.geocodeText("Tie 866 15, Ii", null))
    }

    @Test
    fun `porras, asunto ja talonumeron merkintä eivät vie talonumeroa`() = runTest {
        val kauppakatu3 = collection(feature(osmId = 46, street = "Kauppakatu", housenumber = "3", postcode = "90100", city = "Oulu", lat = 65.01, lon = 25.47))
        val storgatan12 = collection(feature(osmId = 47, street = "Storgatan", housenumber = "12", postcode = "65100", city = "Vasa", lat = 63.09, lon = 21.61))
        val kauppakatu5 = collection(feature(osmId = 48, street = "Kauppakatu", housenumber = "5", postcode = "90100", city = "Oulu", lat = 65.01, lon = 25.47))
        listOf(kauppakatu3, storgatan12, kauppakatu5, kauppakatu3).forEach { server.enqueue(MockResponse().setBody(it)) }
        val photon = client()

        // Asunnon numero ei ole talonumero.
        assertNull(photon.geocodeText("Kauppakatu 5 A 3, 90100 Oulu", null))
        assertNull(photon.geocodeText("Storgatan 10 bst 12, 65100 Vasa", null))
        assertEquals("osm:N:48", photon.geocodeText("Kauppakatu n:o 5, 90100 Oulu", null)?.placeId)
        assertNull(photon.geocodeText("Kauppakatu n:o 5, 90100 Oulu", null))
    }

    @Test
    fun `puhelinnumero nimirivillä ei ole katuosoite`() = runTest {
        server.enqueue(MockResponse().setBody(collection(feature(osmId = 49, name = "R-kioski", street = "Isokatu", housenumber = "20", postcode = "90100", city = "Oulu", lat = 65.012, lon = 25.47))))

        assertEquals("osm:N:49", client().geocodeText("R-kioski, puh. 0401234567, Oulu", null)?.placeId)
    }

    @Test
    fun `postinumeron perässä saa olla kylä, kaupunginosa, maakunta tai maa`() = runTest {
        // Photonin city on kunta; rivillä postinumeron perässä voi olla muu paikan nimi.
        val kuivaniemi = feature(
            osmId = 63, street = "Kirkkotie", housenumber = "2", postcode = "95100", city = "Ii", countrycode = "FI", lat = 65.58, lon = 25.18,
        )
        val kluuvi = feature(
            osmId = 64, street = "Mannerheimintie", housenumber = "10", postcode = "00100", city = "Helsinki", lat = 60.17, lon = 24.94,
        )
        listOf(kuivaniemi, kuivaniemi, kuivaniemi, kluuvi, kluuvi).forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        // Postinumero poistuu, eikä rivillä ole talonumeroa: kadun talo kelpaa.
        assertEquals("osm:N:63", photon.geocodeText("Kirkkotie 95100 Kuivaniemi", null)?.placeId)
        assertEquals("osm:N:63", photon.geocodeText("Kirkkotie 95100 Pohjois-Pohjanmaa", null)?.placeId)
        // Maan nimi postinumeron perässä: rivi kelpaa kuten kylän kanssa.
        assertEquals("osm:N:63", photon.geocodeText("Kirkkotie 95100 Suomi", null)?.placeId)
        assertEquals("osm:N:64", photon.geocodeText("Mannerheimintie 00100 Kluuvi", null)?.placeId)
        assertEquals("osm:N:64", photon.geocodeText("Mannerheimintie 00100 Helsingin seutukunta", null)?.placeId)
    }

    @Test
    fun `maan nimi postinumeron perässä ei vie talonumeroa`() = runTest {
        val torggatan = feature(
            osmId = 65, street = "Torggatan", housenumber = "10", postcode = "22100", city = "Mariehamn", countrycode = "FI", lat = 60.10, lon = 19.94,
        )
        val torggatan12 = feature(
            osmId = 66, street = "Torggatan", housenumber = "12", postcode = "22100", city = "Mariehamn", countrycode = "FI", lat = 60.10, lon = 19.94,
        )
        listOf(torggatan, torggatan12, torggatan)
            .forEach { server.enqueue(MockResponse().setBody(collection(it))) }
        val photon = client()

        assertEquals("osm:N:65", photon.geocodeText("Torggatan 10 22100 Finland", null)?.placeId)
        assertNull(photon.geocodeText("Torggatan 10 22100 Finland", null))
        assertEquals("osm:N:65", photon.geocodeText("Torggatan 10 22100 Mariehamn, Suomi", null)?.placeId)
    }

    @Test
    fun `geocodeText painottaa sijaintia`() = runTest {
        server.enqueue(MockResponse().setBody(collection()))

        client().geocodeText("Storgatan 1", GeoPoint(63.1, 21.62))

        val url = server.takeRequest().requestUrl!!
        assertEquals("63.1", url.queryParameter("lat"))
        assertEquals("21.62", url.queryParameter("lon"))
    }

    @Test
    fun `palvelinvirhe nousee ApiExceptionina koodin kanssa`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))

        val e = assertFailsWith<ApiException> { client().autocomplete("abc") }
        assertEquals(503, e.httpCode)
    }
}
