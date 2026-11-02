package fi.tyomatka.ui.drive

import fi.tyomatka.core.model.GeoPoint
import uniffi.ferrostar.GeographicCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DriveRoutePointsTest {
    private fun c(lat: Double, lng: Double) = GeographicCoordinate(lat, lng)

    /** Jäljellä olevat askeleet: nykyinen ensin, peräkkäiset askeleet jakavat liitospisteen. */
    private val steps = listOf(
        listOf(c(60.1, 24.1), c(60.2, 24.2)),
        listOf(c(60.2, 24.2), c(60.3, 24.3), c(60.4, 24.4)),
    )

    @Test
    fun `reitin varrella -suodatin saa jäljellä olevat askeleet nykyisen askeleen alusta ilman toistuvia liitospisteitä`() {
        assertEquals(
            listOf(GeoPoint(60.1, 24.1), GeoPoint(60.2, 24.2), GeoPoint(60.3, 24.3), GeoPoint(60.4, 24.4)),
            driveRoutePoints(steps),
        )
    }

    @Test
    fun `ilman askelia tai yhdellä pisteellä tyhjä, jolloin suodatin ei rajaa`() {
        assertEquals(emptyList(), driveRoutePoints(null))
        assertEquals(emptyList(), driveRoutePoints(emptyList()))
        assertEquals(emptyList(), driveRoutePoints(listOf(listOf(c(60.1, 24.1)))))
    }

    @Test
    fun `avain pysyy samana askeleen sisällä ja vaihtuu askeleen tai reitin vaihtuessa`() {
        assertEquals(driveRouteKey(steps), driveRouteKey(steps.map { it.toList() }))
        assertNotEquals(driveRouteKey(steps), driveRouteKey(steps.drop(1)))
        assertNotEquals(driveRouteKey(steps), driveRouteKey(listOf(listOf(c(60.0, 24.0), c(60.2, 24.2))) + steps.drop(1)))
    }
}
