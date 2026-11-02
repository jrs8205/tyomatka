package fi.tyomatka.core.poi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoiGridTest {
    @Test
    fun `ikkuna pyöristyy ulospäin neljännesasteen ruudukkoon`() {
        assertEquals(PoiBox(24.50, 60.25, 25.00, 60.50), PoiGrid.snap(PoiBox(24.61, 60.31, 24.93, 60.44), zoom = 10.0))
    }

    @Test
    fun `ruudukkoon osuva ikkuna pysyy ennallaan`() {
        assertEquals(PoiBox(24.50, 60.25, 25.00, 60.50), PoiGrid.snap(PoiBox(24.50, 60.25, 25.00, 60.50), zoom = 10.0))
    }

    @Test
    fun `yli kahden asteen pyöristetty ikkuna hylätään`() {
        assertNull(PoiGrid.snap(PoiBox(24.0, 60.0, 26.1, 60.5), zoom = 10.0))
        assertNull(PoiGrid.snap(PoiBox(24.0, 60.0, 24.5, 62.1), zoom = 10.0))
        assertNull(PoiGrid.snap(PoiBox(-179.9, -89.9, 179.9, 89.9), zoom = 10.0))
    }

    @Test
    fun `pyöristys ei ylitä koordinaattien rajoja`() {
        assertEquals(PoiBox(179.75, 59.75, 180.0, 60.25), PoiGrid.snap(PoiBox(179.9, 59.9, 180.0, 60.1), zoom = 10.0))
        assertEquals(PoiBox(-180.0, 89.5, -179.5, 90.0), PoiGrid.snap(PoiBox(-180.0, 89.6, -179.6, 90.0), zoom = 10.0))
    }

    @Test
    fun `lähennetty kartta pyöristyy tiheämpään ruudukkoon, jottei 300 kohteen raja leikkaa kaupungissa`() {
        assertEquals(PoiBox(24.90, 60.15, 25.00, 60.20), PoiGrid.snap(PoiBox(24.931, 60.161, 24.958, 60.172), zoom = 14.0))
        assertEquals(PoiBox(24.90, 60.15, 25.00, 60.20), PoiGrid.snap(PoiBox(24.931, 60.161, 24.958, 60.172), zoom = 12.0))
        assertEquals(PoiBox(24.75, 60.00, 25.00, 60.25), PoiGrid.snap(PoiBox(24.931, 60.161, 24.958, 60.172), zoom = 11.9))
    }

    @Test
    fun `kadun tarkkuudella ruudukko on sadasosa-aste, jotta tiheinkin kaupungin solu mahtuu 300 kohteeseen`() {
        assertEquals(PoiBox(4.89, 52.37, 4.91, 52.38), PoiGrid.snap(PoiBox(4.8931, 52.3712, 4.9058, 52.3781), zoom = 15.0))
        assertEquals(PoiBox(4.85, 52.35, 4.95, 52.40), PoiGrid.snap(PoiBox(4.8931, 52.3712, 4.9058, 52.3781), zoom = 14.9))
        assertEquals("4.89,52.37,4.91,52.38|charging", PoiGrid.cacheKey(PoiGrid.snap(PoiBox(4.8931, 52.3712, 4.9058, 52.3781), zoom = 16.0)!!, PoiCategory.CHARGING))
    }

    @Test
    fun `tiheän ruudukon rajat ovat tasan kahden desimaalin lukuja`() {
        val snapped = PoiGrid.snap(PoiBox(24.351, 60.301, 24.449, 60.349), zoom = 13.0)!!
        assertEquals("24.35,60.30,24.45,60.35|fuel", PoiGrid.cacheKey(snapped, PoiCategory.FUEL))
        assertEquals(PoiBox(24.35, 60.30, 24.45, 60.35), snapped)
    }

    @Test
    fun `välimuistin avain on pyöristetty ikkuna ja luokka`() {
        assertEquals("24.50,60.25,25.00,60.50|fuel", PoiGrid.cacheKey(PoiBox(24.5, 60.25, 25.0, 60.5), PoiCategory.FUEL))
        assertEquals("-0.25,51.25,0.50,51.75|rest", PoiGrid.cacheKey(PoiBox(-0.25, 51.25, 0.5, 51.75), PoiCategory.REST))
    }
}
