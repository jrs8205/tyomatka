package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessPlaceResolverTest {
    private val point = GeoPoint(60.5291, 25.6002)
    private val askola = Business("abc", "Askolan Auto- ja Konekorjaamo", "Teollisuustie 3", "07230", "ASKOLA", point)

    private class Reverse(private val answer: suspend () -> PlaceDetails?) : ReverseGeocoder {
        val calls = mutableListOf<Pair<GeoPoint, Double>>()

        override suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails? {
            calls += point to maxDistanceM
            return answer()
        }
    }

    private val official = PlaceDetails("digitransit:1", "Teollisuustie 3", "Teollisuustie 3, Askola", GeoPoint(60.5288, 25.6011))
    private val nearest = PlaceDetails("digitransit:2", "Teollisuustie 1", "Teollisuustie 1, Askola", GeoPoint(60.5290, 25.6004))

    @Test
    fun `katuosoite geokoodataan ja osuma antaa virallisen osoitteen ja sijainnin`() = runTest {
        val queries = mutableListOf<Pair<String, GeoPoint?>>()
        val reverse = Reverse { nearest }
        val resolver = BusinessPlaceResolver({ line, bias -> queries += line to bias; official }, reverse)

        val details = resolver.resolve(askola)

        assertEquals(listOf<Pair<String, GeoPoint?>>("Teollisuustie 3, 07230 Askola" to point), queries)
        assertEquals(PlaceDetails("overture:abc", "Askolan Auto- ja Konekorjaamo", "Teollisuustie 3, Askola", official.location), details)
        assertTrue(reverse.calls.isEmpty())
    }

    @Test
    fun `eri talonumero siirtää käänteishakuun`() = runTest {
        val reverse = Reverse { nearest }
        val other = official.copy(address = "Teollisuustie 30, Askola")
        val resolver = BusinessPlaceResolver({ _, _ -> other }, reverse)

        val details = resolver.resolve(askola)

        assertEquals(listOf(point to 300.0), reverse.calls)
        assertEquals(PlaceDetails("overture:abc", "Askolan Auto- ja Konekorjaamo", nearest.address, nearest.location), details)
    }

    @Test
    fun `ilman katua geokoodausta ei kysytä vaan käänteishaku 300 metrin säteellä`() = runTest {
        var geocoded = 0
        val reverse = Reverse { nearest }
        val resolver = BusinessPlaceResolver({ _, _ -> geocoded++; official }, reverse)

        val details = resolver.resolve(askola.copy(street = null))

        assertEquals(0, geocoded)
        assertEquals(listOf(point to 300.0), reverse.calls)
        assertEquals(nearest.address, details.address)
    }

    @Test
    fun `katu ilman numeroa ei kelpaa geokoodaukseen`() = runTest {
        var geocoded = 0
        val resolver = BusinessPlaceResolver({ _, _ -> geocoded++; official }, Reverse { nearest })

        resolver.resolve(askola.copy(street = "Teollisuustie"))

        assertEquals(0, geocoded)
    }

    @Test
    fun `ilman postinumeroa geokoodataan kadulla ja paikkakunnalla`() = runTest {
        val queries = mutableListOf<String>()
        val resolver = BusinessPlaceResolver({ line, _ -> queries += line; official }, Reverse { nearest })

        resolver.resolve(askola.copy(postcode = null))
        resolver.resolve(askola.copy(postcode = null, locality = null))

        assertEquals(listOf("Teollisuustie 3, Askola", "Teollisuustie 3"), queries)
    }

    @Test
    fun `geokoodauksen tyhjä tulos siirtää käänteishakuun`() = runTest {
        val reverse = Reverse { nearest }
        val resolver = BusinessPlaceResolver({ _, _ -> null }, reverse)

        assertEquals(nearest.address, resolver.resolve(askola).address)
        assertEquals(1, reverse.calls.size)
    }

    private val tammisaari = Business("sv", "Bokhandel", "Kungsgatan 10", "10600", "EKENÄS", GeoPoint(59.9740, 23.4370))

    // Digitransit antaa suomenkielisen nimen; noin 100 m Overturen pisteestä.
    private val finnish = PlaceDetails("digitransit:3", "Kuninkaankatu 10", "Kuninkaankatu 10, Tammisaari", GeoPoint(59.9749, 23.4370))

    // Overturen pisteen lähin osoite samalla kadulla vahvistaa kieliversion.
    private val sameStreetNearby = PlaceDetails("digitransit:4", "Kuninkaankatu 8", "Kuninkaankatu 8, Tammisaari", GeoPoint(59.9741, 23.4371))

    @Test
    fun `ruotsinkielinen katu kelpaa, kun pisteen lähin osoite on samalla kadulla`() = runTest {
        val reverse = Reverse { sameStreetNearby }
        val resolver = BusinessPlaceResolver({ _, _ -> finnish }, reverse)

        val details = resolver.resolve(tammisaari)

        assertEquals(PlaceDetails("overture:sv", "Bokhandel", "Kuninkaankatu 10, Tammisaari", finnish.location), details)
        assertEquals(listOf(tammisaari.location to 300.0), reverse.calls)
    }

    @Test
    fun `eri nimisen kadun osuma hylätään, kun pisteen lähin osoite on toisella kadulla`() = runTest {
        // Metsätie 4 haettu, geokoodaus antoi Lintutie 4:n noin 111 m päästä; lähin osoite on oikea Metsätie 4.
        val metsatie = Business("m", "Paja", "Metsätie 4", "06100", "Porvoo", GeoPoint(60.4000, 25.6600))
        val metsotie = PlaceDetails("digitransit:5", "Lintutie 4", "Lintutie 4, Porvoo", GeoPoint(60.4010, 25.6600))
        val right = PlaceDetails("digitransit:6", "Metsätie 4", "Metsätie 4, Porvoo", GeoPoint(60.4001, 25.6601))

        val details = BusinessPlaceResolver({ _, _ -> metsotie }, Reverse { right }).resolve(metsatie)

        assertEquals(PlaceDetails("overture:m", "Paja", "Metsätie 4, Porvoo", right.location), details)
    }

    @Test
    fun `eri nimisen kadun osuma ilman käänteishaun vahvistusta antaa Overturen osoitteen`() = runTest {
        val details = BusinessPlaceResolver({ _, _ -> finnish }, Reverse { null }).resolve(tammisaari)

        assertEquals(PlaceDetails("overture:sv", "Bokhandel", "Kungsgatan 10, Ekenäs", tammisaari.location), details)
    }

    @Test
    fun `eri nimisen kadun osuma kaukana tai eri numerolla siirtää käänteishakuun`() = runTest {
        // Noin 400 m pohjoiseen: yli 300 m:n rajan.
        val far = finnish.copy(location = GeoPoint(59.9776, 23.4370))
        val otherNumber = finnish.copy(address = "Kuninkaankatu 12, Tammisaari", location = GeoPoint(59.9741, 23.4370))

        assertEquals(sameStreetNearby.address, BusinessPlaceResolver({ _, _ -> far }, Reverse { sameStreetNearby }).resolve(tammisaari).address)
        assertEquals(sameStreetNearby.address, BusinessPlaceResolver({ _, _ -> otherNumber }, Reverse { sameStreetNearby }).resolve(tammisaari).address)
    }

    @Test
    fun `sama katu kelpaa kaukanakin Overturen pisteestä`() = runTest {
        // Overturen piste voi olla satoja metrejä sivussa; virallinen osoite noin 1,1 km päässä.
        val distant = official.copy(location = GeoPoint(60.5391, 25.6002))
        val reverse = Reverse { nearest }

        val details = BusinessPlaceResolver({ _, _ -> distant }, reverse).resolve(askola)

        assertEquals(distant.location, details.location)
        assertTrue(reverse.calls.isEmpty())
    }

    @Test
    fun `ilman osoitetietoja osoitteeksi tulevat koordinaatit`() = runTest {
        val bare = askola.copy(street = null, postcode = "07230", locality = null)
        val resolver = BusinessPlaceResolver({ _, _ -> null }, Reverse { null })

        assertEquals("60.5291, 25.6002", resolver.resolve(bare).address)
    }

    @Test
    fun `käänteishaun tyhjä osoite ohitetaan`() = runTest {
        val resolver = BusinessPlaceResolver({ _, _ -> null }, Reverse { nearest.copy(address = " ") })

        assertEquals(PlaceDetails("overture:abc", "Askolan Auto- ja Konekorjaamo", "Teollisuustie 3, Askola", point), resolver.resolve(askola))
    }

    @Test
    fun `käänteishaun tyhjä tulos antaa Overturen osoitteen ja pisteen`() = runTest {
        val resolver = BusinessPlaceResolver({ _, _ -> null }, Reverse { null })

        val details = resolver.resolve(askola)

        assertEquals(PlaceDetails("overture:abc", "Askolan Auto- ja Konekorjaamo", "Teollisuustie 3, Askola", point), details)
    }

    @Test
    fun `geokoodauksen virhe siirtää käänteishakuun`() = runTest {
        val reverse = Reverse { nearest }
        val resolver = BusinessPlaceResolver({ _, _ -> throw ApiException("HTTP 500") }, reverse)

        assertEquals(nearest.address, resolver.resolve(askola).address)
    }

    @Test
    fun `viipyvä geokoodaus katkaistaan kolmessa sekunnissa`() = runTest {
        val reverse = Reverse { nearest }
        val resolver = BusinessPlaceResolver({ _, _ -> delay(4_000); official }, reverse)

        val details = resolver.resolve(askola)

        assertEquals(nearest.address, details.address)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `käänteishaun virhe ja viive antavat Overturen osoitteen`() = runTest {
        val failing = BusinessPlaceResolver({ _, _ -> null }, Reverse { throw ApiException("HTTP 503") })
        assertEquals("Teollisuustie 3, Askola", failing.resolve(askola).address)

        val slow = BusinessPlaceResolver({ _, _ -> null }, Reverse { delay(10_000); nearest })
        val start = currentTime
        assertEquals(point, slow.resolve(askola).location)
        assertEquals(3_000, currentTime - start)
    }

    @Test
    fun `haun peruminen ei jatku seuraavaan vaiheeseen`() = runTest {
        val started = CompletableDeferred<Unit>()
        val reverse = Reverse { nearest }
        val resolver = BusinessPlaceResolver({ _, _ -> started.complete(Unit); awaitCancellation() }, reverse)

        var result: PlaceDetails? = null
        val job = launch { result = resolver.resolve(askola) }
        started.await()
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(null, result)
        assertTrue(reverse.calls.isEmpty())
    }

    @Test
    fun `sama katu ja numero hyväksytään, väli ja porras ohitetaan`() {
        assertTrue(sameStreetNumber("Teollisuustie 3", "Teollisuustie 3, Askola"))
        assertTrue(sameStreetNumber("Teollisuustie 2-6", "Teollisuustie 2, Porvoo"))
        assertTrue(sameStreetNumber("Teollisuustie 2", "Teollisuustie 2-6, Porvoo"))
        assertTrue(sameStreetNumber("Mannerheimintie 12 B 5", "mannerheimintie 12, Helsinki"))
        assertTrue(sameStreetNumber("Mallikaari 4A", "Mallikaari 4a, Espoo"))
        assertTrue(sameStreetNumber("Sankt  Mårtens gata 7", "Sankt Mårtens gata 7"))
        assertTrue(sameStreetNumber("Teollisuustie 2 - 6", "Teollisuustie 2, Porvoo"))
        assertTrue(sameStreetNumber("Mallikaari 4 A", "Mallikaari 4, Espoo"))
    }

    @Test
    fun `huoneisto-, liiketila- ja kerrosmerkinnät ohitetaan`() {
        assertTrue(sameStreetNumber("Teollisuustie 3 as 5", "Teollisuustie 3, Askola"))
        assertTrue(sameStreetNumber("Kauppakatu 12 LH 3", "Kauppakatu 12, Porvoo"))
        assertTrue(sameStreetNumber("Storgatan 5 lgh 1102", "Storgatan 5, Jakobstad"))
        assertTrue(sameStreetNumber("Kauppakatu 10 3. krs", "Kauppakatu 10, Porvoo"))
        assertTrue(sameStreetNumber("Kauppakatu 12 as. 3", "Kauppakatu 12, Porvoo"))
        // Lyhyt sana kadun nimessä ei ole merkintä, kun sitä ei edellä talonumero.
        assertTrue(sameStreetNumber("Kustaa III 5", "Kustaa III 5, Pori"))
        assertFalse(sameStreetNumber("Kustaa III 5", "Kustaa 5, Pori"))
        assertTrue(sameStreetNumber("Tie A 5", "Tie A 5, Porvoo"))
        assertTrue(sameStreetNumber("Sankt Eriks väg 5", "Sankt Eriks väg 5, Mariehamn"))
        assertFalse(sameStreetNumber("Sankt Eriks väg 5", "Sankt Eriks väg 7, Mariehamn"))
    }

    @Test
    fun `yhteen kirjoitettu porras ja huoneisto ohitetaan`() {
        assertTrue(sameStreetNumber("Teollisuustie 3 A5", "Teollisuustie 3, Askola"))
        assertTrue(sameStreetNumber("Teollisuustie 3 B12", "Teollisuustie 3, Askola"))
        assertTrue(sameStreetNumber("Teollisuustie 3 as5", "Teollisuustie 3, Askola"))
        assertTrue(sameStreetNumber("Teollisuustie 3A 5", "Teollisuustie 3a, Askola"))
        assertFalse(sameStreetNumber("Teollisuustie 3A 5", "Teollisuustie 5, Askola"))
    }

    @Test
    fun `yhteen kirjoitetun huoneiston osoite geokoodataan`() = runTest {
        val queries = mutableListOf<String>()
        val resolver = BusinessPlaceResolver({ line, _ -> queries += line; official }, Reverse { nearest })

        val details = resolver.resolve(askola.copy(street = "Teollisuustie 3 A5"))

        assertEquals(listOf("Teollisuustie 3 A5, 07230 Askola"), queries)
        assertEquals(official.address, details.address)
    }

    @Test
    fun `kadun nimen numero ei ole talonumero`() {
        assertTrue(sameStreetNumber("Linja 3 4", "Linja 3 4, Porvoo"))
        assertFalse(sameStreetNumber("Linja 3 4", "Linja 3 14, Porvoo"))
        assertFalse(sameStreetNumber("Linja 3 4", "Linja 4, Porvoo"))
        assertTrue(sameStreetNumber("3. linja 4", "3. linja 4, Helsinki"))
        assertFalse(sameStreetNumber("3. linja 4", "4. linja 4, Helsinki"))
        assertTrue(sameStreetNumber("Linja 3 4 B 2", "Linja 3 4, Porvoo"))
    }

    @Test
    fun `eri katu, numero tai kirjain hylätään`() {
        assertFalse(sameStreetNumber("Teollisuustie 3", "Teollisuustie 30, Porvoo"))
        assertFalse(sameStreetNumber("Teollisuustie 3", "Teollisuuskatu 3, Porvoo"))
        assertFalse(sameStreetNumber("Mallikaari 4a", "Mallikaari 4, Espoo"))
        assertFalse(sameStreetNumber("Mallikaari 4", "Mallikaari 4b, Espoo"))
        assertFalse(sameStreetNumber("Teollisuustie 3", "Teollisuustie, Porvoo"))
        assertFalse(sameStreetNumber("Teollisuustie", "Teollisuustie, Porvoo"))
        assertFalse(sameStreetNumber("Teollisuustie 3", ""))
        assertFalse(sameStreetNumber("", "Teollisuustie 3"))
        assertFalse(sameStreetNumber("3", "3, Porvoo"))
    }
}
