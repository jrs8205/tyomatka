package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class NavLanguageTest {

    @Test
    fun `suomi ja ruotsi saavat oman kielensa`() {
        assertEquals("fi-FI", navLanguageTag("fi"))
        assertEquals("sv-SE", navLanguageTag("sv"))
    }

    @Test
    fun `muut kielet saavat englannin`() {
        assertEquals("en-US", navLanguageTag("en"))
        assertEquals("en-US", navLanguageTag("de"))
        assertEquals("en-US", navLanguageTag(""))
    }

    @Test
    fun `kielikoodin kirjainkoko ei vaikuta`() {
        assertEquals("fi-FI", navLanguageTag("FI"))
    }

    @Test
    fun `alueellinen tunniste kelpaa`() {
        assertEquals("fi-FI", navLanguageTag("fi-FI"))
        assertEquals("sv-SE", navLanguageTag("sv_FI"))
        assertEquals("en-US", navLanguageTag("en-GB"))
    }
}
