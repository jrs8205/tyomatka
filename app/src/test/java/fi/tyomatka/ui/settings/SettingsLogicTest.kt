package fi.tyomatka.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsLogicTest {
    private val voices = listOf(VoiceOption("fi-b", usable = true), VoiceOption("fi-a", usable = true), VoiceOption("fi-verkko", usable = false))

    @Test
    fun `tietosuojaseloste avautuu sovelluksen kielellä`() {
        assertEquals("https://poi.jrs8205.com/privacy?lang=fi", privacyUrl("fi"))
        assertEquals("https://poi.jrs8205.com/privacy?lang=sv", privacyUrl("sv"))
        assertEquals("https://poi.jrs8205.com/privacy?lang=en", privacyUrl("en"))
        assertEquals("https://poi.jrs8205.com/privacy?lang=en", privacyUrl("de"))
    }

    @Test
    fun `yhteystieto on julkinen osoite`() {
        assertEquals("jrs8205@pm.me", CONTACT_EMAIL)
    }

    @Test
    fun `tallennettu ääni löytyy`() {
        assertEquals("fi-a", pickVoice("fi-a", voices)?.name)
    }

    @Test
    fun `puuttuva ääni palautuu oletukseen`() {
        assertNull(pickVoice("poistettu", voices))
        assertNull(pickVoice(null, voices))
    }

    @Test
    fun `käyttökelvotonta ääntä ei valita`() {
        assertNull(pickVoice("fi-verkko", voices))
    }

    @Test
    fun `valittavat äänet ovat vakaassa järjestyksessä ilman käyttökelvottomia`() {
        assertEquals(listOf("fi-a", "fi-b"), selectableVoices(voices).map { it.name })
    }

    @Test
    fun `alapalkki piiloutuu vain asetuksella ja ajon aikana Ajo-sivulla`() {
        assertFalse(bottomBarVisible(hide = true, route = "drive", driving = true))
        assertTrue(bottomBarVisible(hide = false, route = "drive", driving = true))
        assertTrue(bottomBarVisible(hide = true, route = "map", driving = true))
        assertTrue(bottomBarVisible(hide = true, route = "drive", driving = false))
    }
}
