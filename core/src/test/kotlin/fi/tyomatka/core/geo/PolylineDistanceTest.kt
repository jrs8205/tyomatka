package fi.tyomatka.core.geo

import fi.tyomatka.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolylineDistanceTest {
    private val line = listOf(GeoPoint(60.0, 24.0), GeoPoint(60.0, 24.1), GeoPoint(60.0, 24.2))

    @Test
    fun `piste kilometrin päässä janan sivulla`() {
        val km = distanceToPolylineKm(GeoPoint(60.009, 24.05), line)
        assertTrue(km in 0.98..1.02, "etäisyys $km")
    }

    @Test
    fun `janan jatkeella etäisyys mitataan päätepisteeseen`() {
        val km = distanceToPolylineKm(GeoPoint(60.0, 24.3), line)
        assertTrue(km in 5.4..5.7, "etäisyys $km")
    }

    @Test
    fun `reitillä oleva piste on nollan päässä`() {
        assertTrue(distanceToPolylineKm(GeoPoint(60.0, 24.15), line) < 0.001)
    }

    @Test
    fun `tyhjä reitti on äärettömän kaukana ja yhden pisteen reitti mitataan pisteeseen`() {
        assertEquals(Double.POSITIVE_INFINITY, distanceToPolylineKm(GeoPoint(60.0, 24.0), emptyList()))
        val km = distanceToPolylineKm(GeoPoint(60.009, 24.0), listOf(GeoPoint(60.0, 24.0)))
        assertTrue(km in 0.98..1.02, "etäisyys $km")
    }
}
