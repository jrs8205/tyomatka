package fi.tyomatka.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class PolylineTest {

    @Test
    fun `purkaa googlen esimerkkipolylinen`() {
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 0.00001)
        assertEquals(-120.2, points[0].lng, 0.00001)
        assertEquals(40.7, points[1].lat, 0.00001)
        assertEquals(-120.95, points[1].lng, 0.00001)
        assertEquals(43.252, points[2].lat, 0.00001)
        assertEquals(-126.453, points[2].lng, 0.00001)
    }

    @Test
    fun `tyhja merkkijono palauttaa tyhjan listan`() {
        assertEquals(0, Polyline.decode("").size)
    }
}
