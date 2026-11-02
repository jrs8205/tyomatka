package fi.tyomatka.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppCurrencyTest {
    @Test
    fun `sovelluksen valuutta on euro`() {
        assertEquals("EUR", APP_CURRENCY)
        // Migraatio vaatii kelvollisen koodin.
        assertTrue(isCurrencyCode(APP_CURRENCY))
    }

    @Test
    fun `valuuttakoodi on kolme isoa kirjainta eikä tuntematon XXX`() {
        assertTrue(isCurrencyCode("EUR"))
        assertTrue(isCurrencyCode("SEK"))
        assertFalse(isCurrencyCode("XXX"))
        // Muoto ei riitä: koodin on oltava ISO 4217 -valuutta.
        assertFalse(isCurrencyCode("ZZZ"))
        assertFalse(isCurrencyCode("ABC"))
        assertTrue(isCurrencyCode("JPY"))
        assertFalse(isCurrencyCode("eur"))
        assertFalse(isCurrencyCode("EURO"))
        assertFalse(isCurrencyCode("EUR'; DROP TABLE stops; --"))
    }
}
