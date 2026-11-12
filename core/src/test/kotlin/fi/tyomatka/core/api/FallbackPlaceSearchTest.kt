package fi.tyomatka.core.api

import fi.tyomatka.core.model.PlaceSuggestion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallbackPlaceSearchTest {
    @Test
    fun `ensimmäinen palvelu riittää kun se löytää osumia`() = runTest {
        val second = FakeSearch(listOf(suggestion("b:1")))

        val result = FallbackPlaceSearch(listOf(FakeSearch(listOf(suggestion("a:1"))), second)).autocomplete("Kungsgatan", null)

        assertEquals(listOf("a:1"), result.map { it.placeId })
        assertTrue(second.queries.isEmpty())
    }

    @Test
    fun `tyhjä tulos tai virhe siirtää haun seuraavalle palvelulle`() = runTest {
        // Esim. osoitehaun kiintiö täynnä (HTTP 429) → seuraava palvelu.
        val search = FallbackPlaceSearch(
            listOf(FakeSearch(), FakeSearch(failure = ApiException("HTTP 429", 429)), FakeSearch(listOf(suggestion("c:1"))))
        )

        assertEquals(listOf("c:1"), search.autocomplete("Kungsgatan", null).map { it.placeId })
    }

    @Test
    fun `kaikkien virhe nousee viimeisen palvelun virheenä`() = runTest {
        val search = FallbackPlaceSearch(
            listOf(FakeSearch(failure = ApiException("HTTP 429", 429)), FakeSearch(failure = ApiException("HTTP 503", 503)))
        )

        val e = assertFailsWith<ApiException> { search.autocomplete("Kungsgatan", null) }
        assertEquals(503, e.httpCode)
    }

    @Test
    fun `tyhjä tulos ilman virheitä on tyhjä lista`() = runTest {
        assertTrue(FallbackPlaceSearch(listOf(FakeSearch(), FakeSearch())).autocomplete("xyzzy", null).isEmpty())
    }

    @Test
    fun `tiedot ja listatuonti kulkevat samaa ketjua`() = runTest {
        val search = FallbackPlaceSearch(
            listOf(FakeSearch(listOf(suggestion("a:1"))), FakeSearch(listOf(suggestion("b:1")), geocoded = details("b:9")))
        )

        assertEquals("a:1", search.placeDetails("a:1").placeId)
        assertEquals("b:1", search.placeDetails("b:1").placeId)
        assertFailsWith<ApiException> { search.placeDetails("x:1") }
        assertEquals("b:9", search.geocodeText("Kauppakatu 10 Oulu", null)?.placeId)
        assertNull(FallbackPlaceSearch(listOf(FakeSearch())).geocodeText("xyzzy", null))
    }

    @Test
    fun `lähdemaininnat kootaan näkyvistä ehdotuksista kertaalleen`() {
        val shown = listOf(
            PlaceSuggestion("a", "A", "", source = "© Digitransit"),
            PlaceSuggestion("b", "B", "", source = "Haku X · © OpenStreetMap"),
            PlaceSuggestion("c", "C", "", source = "© Digitransit"),
            PlaceSuggestion("d", "D", ""),
        )

        assertEquals("© Digitransit · Haku X · © OpenStreetMap", shown.sources())
        assertNull(emptyList<PlaceSuggestion>().sources())
        // Osat yhdistetään: "Haku X" ei toistu eri datalähteiden kanssa.
        val mixed = listOf(
            PlaceSuggestion("a", "A", "", source = "Haku X · © OpenStreetMap contributors"),
            PlaceSuggestion("b", "B", "", source = "Haku X · © OpenAddresses"),
        )
        assertEquals("Haku X · © OpenStreetMap contributors · © OpenAddresses", mixed.sources())
    }
}
