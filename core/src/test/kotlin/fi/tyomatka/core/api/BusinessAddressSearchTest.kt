package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.model.PlaceSuggestion
import fi.tyomatka.core.model.SuggestionKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BusinessAddressSearchTest {
    private val porvoo = GeoPoint(60.39, 25.66)
    private val stockholm = GeoPoint(59.33, 18.07)
    private val address = PlaceSuggestion("digitransit:1", "Esimerkkikatu 4", "Porvoo", "© Digitransit", GeoPoint(60.39, 25.65))
    private val askola = Business("abc", "Askolan Auto- ja Konekorjaamo", "Teollisuustie 3", "07230", "ASKOLA", GeoPoint(60.5291, 25.6002))

    /** Osoitehaku, jonka vastaus, viive ja virhe annetaan; kirjaa kutsut ja perumiset. */
    private class Addresses(
        private val delayMs: Long = 0,
        private val answer: List<PlaceSuggestion> = emptyList(),
        private val failure: Exception? = null,
    ) : PlaceSearch {
        val queries = mutableListOf<String>()
        val details = mutableListOf<String>()
        val biases = mutableListOf<GeoPoint?>()
        var cancelled = false

        override suspend fun autocomplete(input: String, biasLocation: GeoPoint?): List<PlaceSuggestion> {
            queries += input
            biases += biasLocation
            try {
                delay(delayMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            failure?.let { throw it }
            return answer
        }

        override suspend fun placeDetails(placeId: String): PlaceDetails {
            details += placeId
            return PlaceDetails(placeId, "osoite", "Esimerkkikatu 4, Porvoo", GeoPoint(60.39, 25.65))
        }

        override suspend fun geocodeText(addressLine: String, biasLocation: GeoPoint?): PlaceDetails? {
            queries += addressLine
            biases += biasLocation
            return PlaceDetails("digitransit:9", addressLine, addressLine, GeoPoint(60.0, 25.0))
        }
    }

    /** Yrityshaku, jonka vastaus riippuu hakusanasta; kirjaa kutsut ja perumiset. */
    private class Businesses(private val answer: suspend (String) -> List<Business>) : BusinessSource {
        val queries = mutableListOf<Pair<String, GeoPoint?>>()
        var cancelled = 0

        override suspend fun search(query: String, near: GeoPoint?): List<Business> {
            queries += query to near
            try {
                return answer(query)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
        }
    }

    private val geocoded = mutableListOf<String>()
    private val resolver = BusinessPlaceResolver({ line, _ -> geocoded += line; null }, object : ReverseGeocoder {
        override suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails? = null
    })

    private fun search(addresses: PlaceSearch, businesses: BusinessSource) = BusinessAddressSearch(addresses, businesses, resolver)

    private fun companies(delayMs: Long, vararg items: Business): suspend (String) -> List<Business> = {
        delay(delayMs)
        items.toList()
    }

    @Test
    fun `haut saavat vain noin kilometrin tarkkuudella pyöristetyn sijainnin`() = runTest {
        val addresses = Addresses()
        val businesses = Businesses { emptyList() }
        val tarkka = GeoPoint(60.275214, 24.837579)
        val pyoristetty = GeoPoint(60.28, 24.84)
        val haku = search(addresses, businesses)

        haku.autocomplete("Kaivokatu", tarkka)
        haku.geocodeText("Kaivokatu 1", tarkka)

        assertEquals(listOf<GeoPoint?>(pyoristetty, pyoristetty), addresses.biases)
        assertEquals(pyoristetty, businesses.queries.single().second)
    }

    @Test
    fun `tuntematon sijainti pysyy tuntemattomana`() = runTest {
        val addresses = Addresses()

        search(addresses, Businesses { emptyList() }).autocomplete("Kaivokatu", null)

        assertEquals(listOf<GeoPoint?>(null), addresses.biases)
    }

    @Test
    fun `nimellä yritykset tulevat ensin ja ehdotuksessa on yrityksen tiedot`() = runTest {
        val businesses = Businesses(companies(200, askola))
        val result = search(Addresses(100, listOf(address)), businesses).autocomplete("Askolan auto", porvoo)

        assertEquals(
            listOf(
                PlaceSuggestion(
                    "overture:abc", "Askolan Auto- ja Konekorjaamo", "Teollisuustie 3, Askola",
                    "© Overture Maps Foundation", askola.location, SuggestionKind.BUSINESS,
                ),
                address,
            ),
            result,
        )
        assertEquals(listOf<Pair<String, GeoPoint?>>("Askolan auto" to porvoo), businesses.queries)
    }

    @Test
    fun `numero hakusanassa tuo osoitteet ensin`() = runTest {
        val result = search(Addresses(100, listOf(address)), Businesses(companies(50, askola))).autocomplete("Esimerkkikatu 4", porvoo)

        assertEquals(listOf("digitransit:1", "overture:abc"), result.map { it.placeId })
        assertEquals(listOf(SuggestionKind.ADDRESS, SuggestionKind.BUSINESS), result.map { it.kind })
    }

    @Test
    fun `Suomen ulkopuolellakin yrityshaku kutsutaan, koska sovellus hakee vain Suomesta`() = runTest {
        val businesses = Businesses(companies(0, askola))
        val result = search(Addresses(0, listOf(address)), businesses).autocomplete("Askolan auto", stockholm)

        assertEquals(listOf<Pair<String, GeoPoint?>>("Askolan auto" to stockholm), businesses.queries)
        assertEquals(2, result.size)
    }

    @Test
    fun `tuntemattomalla sijainnilla yrityshaku kutsutaan`() = runTest {
        val businesses = Businesses(companies(0, askola))
        val result = search(Addresses(0, listOf(address)), businesses).autocomplete("Askolan auto", null)

        assertEquals(listOf<Pair<String, GeoPoint?>>("Askolan auto" to null), businesses.queries)
        assertEquals(2, result.size)
    }

    @Test
    fun `yrityshaun virhe jättää vain osoitteet`() = runTest {
        val result = search(Addresses(100, listOf(address)), Businesses { throw PoiUnavailableException(429) })
            .autocomplete("Askolan auto", porvoo)

        assertEquals(listOf(address), result)
    }

    @Test
    fun `osoitehaun virhe jättää vain yritykset`() = runTest {
        val result = search(Addresses(100, failure = ApiException("HTTP 500")), Businesses(companies(300, askola)))
            .autocomplete("Askolan auto", porvoo)

        assertEquals(listOf("overture:abc"), result.map { it.placeId })
    }

    @Test
    fun `molempien virhe nostaa osoitehaun virheen`() = runTest {
        val failure = ApiException("HTTP 500")
        val search = search(Addresses(100, failure = failure), Businesses { throw ApiException("HTTP 502") })

        assertSame(failure, assertFailsWith<ApiException> { search.autocomplete("Askolan auto", porvoo) })
    }

    @Test
    fun `osoitehaun virhe ja tyhjä yrityshaku nostavat osoitehaun virheen`() = runTest {
        val failure = ApiException("HTTP 500")
        val search = search(Addresses(100, failure = failure), Businesses(companies(50)))

        assertSame(failure, assertFailsWith<ApiException> { search.autocomplete("Askolan auto", porvoo) })
    }

    @Test
    fun `yritykset jäävät pois, kun ne myöhästyvät sekunnin osoitteiden jälkeen`() = runTest {
        val businesses = Businesses(companies(1_500, askola))
        val result = search(Addresses(100, listOf(address)), businesses).autocomplete("Askolan auto", porvoo)

        assertEquals(listOf(address), result)
        assertEquals(1_100, currentTime)
        assertEquals(1, businesses.cancelled)
    }

    @Test
    fun `yritykset ehtivät mukaan sekunnin sisällä osoitteista`() = runTest {
        val result = search(Addresses(100, listOf(address)), Businesses(companies(900, askola))).autocomplete("Askolan auto", porvoo)

        assertEquals(listOf("overture:abc", "digitransit:1"), result.map { it.placeId })
        assertEquals(900, currentTime)
    }

    @Test
    fun `tyhjä osoitehaku ei käynnistä odotusta`() = runTest {
        val result = search(Addresses(100), Businesses(companies(2_500, askola))).autocomplete("Askolan auto", porvoo)

        assertEquals(listOf("overture:abc"), result.map { it.placeId })
        assertEquals(2_500, currentTime)
    }

    @Test
    fun `tyhjä yrityshaku ei käynnistä odotusta`() = runTest {
        val result = search(Addresses(2_500, listOf(address)), Businesses(companies(100))).autocomplete("Askolan auto", porvoo)

        assertEquals(listOf(address), result)
        assertEquals(2_500, currentTime)
    }

    @Test
    fun `osoitteet jäävät pois, kun ne myöhästyvät sekunnin yritysten jälkeen`() = runTest {
        val addresses = Addresses(3_000, listOf(address))
        val result = search(addresses, Businesses(companies(100, askola))).autocomplete("Askolan auto", porvoo)

        assertEquals(listOf("overture:abc"), result.map { it.placeId })
        assertEquals(1_100, currentTime)
        assertTrue(addresses.cancelled)
    }

    @Test
    fun `vastaamaton yrityshaku katkaistaan viidessä sekunnissa`() = runTest {
        val businesses = Businesses { awaitCancellation() }
        val result = search(Addresses(100), businesses).autocomplete("Askolan auto", porvoo)

        assertEquals(emptyList(), result)
        assertEquals(5_000, currentTime)
        assertEquals(1, businesses.cancelled)
    }

    @Test
    fun `yrityksen tiedot haetaan stopin osoitepolulla`() = runTest {
        val addresses = Addresses(0, listOf(address))
        val search = search(addresses, Businesses(companies(0, askola)))
        search.autocomplete("Askolan auto", porvoo)

        val details = search.placeDetails("overture:abc")

        assertEquals(PlaceDetails("overture:abc", "Askolan Auto- ja Konekorjaamo", "Teollisuustie 3, Askola", askola.location), details)
        assertEquals(listOf("Teollisuustie 3, 07230 Askola"), geocoded)
        assertTrue(addresses.details.isEmpty())
    }

    @Test
    fun `osoitteen tiedot tulevat osoitehausta`() = runTest {
        val addresses = Addresses(0, listOf(address))
        val search = search(addresses, Businesses(companies(0)))

        assertEquals("Esimerkkikatu 4, Porvoo", search.placeDetails("digitransit:1").address)
        assertEquals(listOf("digitransit:1"), addresses.details)
    }

    @Test
    fun `tuntematon yritystunniste on rajapintavirhe`() = runTest {
        val search = search(Addresses(), Businesses(companies(0, askola)))

        assertFailsWith<ApiException> { search.placeDetails("overture:tuntematon") }
        assertTrue(geocoded.isEmpty())
    }

    @Test
    fun `muistissa pysyy 200 viimeisintä yritystä`() = runTest {
        val many = (0..200).map { askola.copy(id = "id$it", name = "Yritys $it") }
        val search = search(Addresses(), Businesses { many })
        search.autocomplete("yritys", porvoo)

        assertEquals("Yritys 200", search.placeDetails("overture:id200").name)
        assertEquals("Yritys 1", search.placeDetails("overture:id1").name)
        assertFailsWith<ApiException> { search.placeDetails("overture:id0") }
    }

    @Test
    fun `uudelleen näkynyt yritys siirtyy muistissa uusimmaksi`() = runTest {
        val first = listOf(askola) + (1..199).map { askola.copy(id = "x$it") }
        val answers = ArrayDeque(listOf(first, listOf(askola), listOf(askola.copy(id = "uusi"))))
        val search = search(Addresses(), Businesses { answers.removeFirst() })
        repeat(3) { search.autocomplete("yritys", porvoo) }

        assertEquals("Askolan Auto- ja Konekorjaamo", search.placeDetails("overture:abc").name)
        assertFailsWith<ApiException> { search.placeDetails("overture:x1") }
    }

    @Test
    fun `vapaa osoiterivi geokoodataan osoitehaulla`() = runTest {
        val addresses = Addresses()
        val result = search(addresses, Businesses(companies(0, askola))).geocodeText("Esimerkkikatu 4, Porvoo", porvoo)

        assertEquals("Esimerkkikatu 4, Porvoo", result?.address)
        assertEquals(listOf("Esimerkkikatu 4, Porvoo"), addresses.queries)
    }

    @Test
    fun `haun peruminen perii molemmat haut eikä peruttu vastaus näy seuraavassa haussa`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val old = askola.copy(id = "vanha", name = "Vanha osuma")
        val businesses = Businesses { query ->
            if (query == "Asko") {
                firstStarted.complete(Unit)
                delay(2_000)
                listOf(old)
            } else {
                delay(100)
                listOf(askola)
            }
        }
        val addresses = Addresses(3_000, listOf(address))
        val search = search(addresses, businesses)

        val job = launch { search.autocomplete("Asko", porvoo) }
        firstStarted.await()
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(1, businesses.cancelled)
        assertTrue(addresses.cancelled)

        // Sama haku uudella sanalla: vain uuden haun yritys, eikä perutun haun yritystä ole muistissa.
        val result = search.autocomplete("Askolan auto", porvoo)
        assertEquals(listOf("overture:abc"), result.map { it.placeId })
        assertFailsWith<ApiException> { search.placeDetails("overture:vanha") }
    }
}
