package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class ManeuverDistanceTest {

    @Test
    fun `alle sadan metrin matka pyoristetaan alas kymmeniin`() {
        assertEquals(0L, roundManeuverDistanceM(7.0))
        assertEquals(40L, roundManeuverDistanceM(47.0))
        assertEquals(90L, roundManeuverDistanceM(99.9))
    }

    @Test
    fun `alle kilometrin matka pyoristetaan lahimpaan viiteenkymmeneen`() {
        assertEquals(100L, roundManeuverDistanceM(120.0))
        assertEquals(150L, roundManeuverDistanceM(130.0))
        assertEquals(950L, roundManeuverDistanceM(949.0))
    }

    @Test
    fun `kilometrista ylospain pyoristetaan lahimpaan sataan`() {
        assertEquals(1_200L, roundManeuverDistanceM(1_234.0))
        assertEquals(12_300L, roundManeuverDistanceM(12_340.0))
    }

    @Test
    fun `epakelpo tai negatiivinen matka on nolla`() {
        assertEquals(0L, roundManeuverDistanceM(Double.NaN))
        assertEquals(0L, roundManeuverDistanceM(Double.POSITIVE_INFINITY))
        assertEquals(0L, roundManeuverDistanceM(Double.NEGATIVE_INFINITY))
        assertEquals(0L, roundManeuverDistanceM(-5.0))
    }
}
