package fi.tyomatka.core.geo

import fi.tyomatka.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DistanceTest {
    @Test
    fun `isoympyräetäisyys kilometreinä`() {
        val helsinki = GeoPoint(60.1699, 24.9384)
        val tallinn = GeoPoint(59.4370, 24.7536)

        val km = distanceKm(helsinki, tallinn)

        assertTrue(km in 81.0..83.0, "Helsinki–Tallinna $km km")
        assertEquals(0.0, distanceKm(helsinki, helsinki))
    }
}
