package fi.tyomatka.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUrlTest {
    @Test
    fun `kelvollinen https-osoite säilyy polkuineen`() {
        assertEquals("https://routing.example.fi/routed-car", normalizeServerUrl("https://routing.example.fi/routed-car"))
    }

    @Test
    fun `välilyönnit ja loppukauttaviivat pois`() {
        assertEquals("https://palvelin.example.fi", normalizeServerUrl("  https://palvelin.example.fi//  "))
    }

    @Test
    fun `portti sallitaan`() {
        assertEquals("https://palvelin.example.fi:8443", normalizeServerUrl("https://palvelin.example.fi:8443"))
    }

    @Test
    fun `vain https kelpaa`() {
        assertNull(normalizeServerUrl("http://palvelin.example.fi"))
        assertNull(normalizeServerUrl("ftp://palvelin.example.fi"))
        assertNull(normalizeServerUrl("palvelin.example.fi"))
    }

    @Test
    fun `isäntä on pakollinen`() {
        assertNull(normalizeServerUrl("https://"))
        assertNull(normalizeServerUrl("https:///polku"))
    }

    @Test
    fun `kysely fragmentti ja tunnukset hylätään`() {
        assertNull(normalizeServerUrl("https://palvelin.example.fi?avain=1"))
        assertNull(normalizeServerUrl("https://palvelin.example.fi#kohta"))
        assertNull(normalizeServerUrl("https://kayttaja:salasana@palvelin.example.fi"))
    }

    @Test
    fun `tyhjä ja rikkinäinen hylätään`() {
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("   "))
        assertNull(normalizeServerUrl("https://väli lyönti.fi"))
    }

    @Test
    fun `portin pitää olla kelvollinen`() {
        assertEquals("https://palvelin.example.fi:65535", normalizeServerUrl("https://palvelin.example.fi:65535"))
        assertNull(normalizeServerUrl("https://palvelin.example.fi:0"))
        assertNull(normalizeServerUrl("https://palvelin.example.fi:65536"))
        assertNull(normalizeServerUrl("https://palvelin.example.fi:70000"))
    }
}
