package fi.tyomatka.ui.plan

import fi.tyomatka.core.model.PlaceSuggestion
import fi.tyomatka.core.model.SuggestionKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Hakulistan osiot: otsikko jokaisen ei-tyhjän osion alkuun hakutulosten järjestyksessä. */
class SearchRowsTest {
    private fun business(id: String) = PlaceSuggestion("overture:$id", id, "", kind = SuggestionKind.BUSINESS)
    private fun address(id: String) = PlaceSuggestion("digitransit:$id", id, "")

    @Test
    fun `yritykset ensin ja osoitteet perään omien otsikoidensa alla`() {
        val b1 = business("b1")
        val b2 = business("b2")
        val a1 = address("a1")

        assertEquals(
            listOf(
                SearchRow.Header(SuggestionKind.BUSINESS), SearchRow.Item(b1), SearchRow.Item(b2),
                SearchRow.Header(SuggestionKind.ADDRESS), SearchRow.Item(a1),
            ),
            sectioned(listOf(b1, b2, a1)),
        )
    }

    @Test
    fun `osoitteet ensin, kun ne tulevat ensin`() {
        val a1 = address("a1")
        val b1 = business("b1")

        assertEquals(
            listOf(SearchRow.Header(SuggestionKind.ADDRESS), SearchRow.Item(a1), SearchRow.Header(SuggestionKind.BUSINESS), SearchRow.Item(b1)),
            sectioned(listOf(a1, b1)),
        )
    }

    @Test
    fun `pelkillä osoitteilla yksi otsikko`() {
        val a1 = address("a1")
        val a2 = address("a2")

        assertEquals(listOf(SearchRow.Header(SuggestionKind.ADDRESS), SearchRow.Item(a1), SearchRow.Item(a2)), sectioned(listOf(a1, a2)))
    }

    @Test
    fun `tyhjä lista ilman otsikoita`() {
        assertEquals(emptyList(), sectioned(emptyList()))
    }

    @Test
    fun `sekaisin tulleet osumat ryhmitellään ensimmäisen esiintymän järjestyksessä`() {
        val a1 = address("a1")
        val b1 = business("b1")
        val a2 = address("a2")

        assertEquals(
            listOf(
                SearchRow.Header(SuggestionKind.ADDRESS), SearchRow.Item(a1), SearchRow.Item(a2),
                SearchRow.Header(SuggestionKind.BUSINESS), SearchRow.Item(b1),
            ),
            sectioned(listOf(a1, b1, a2)),
        )
    }
}
