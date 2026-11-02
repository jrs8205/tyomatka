package fi.tyomatka.ui.poi

import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PoiStopDayTest {
    private var todayCalls = 0
    private val today: suspend () -> Long = {
        todayCalls++
        69L
    }

    @Test
    fun `ajossa kohde lisätään ajossa olevaan päivään, vaikka kalenteripäivä on jo vaihtunut`() = runTest {
        assertEquals(68L, poiStopDayId(DriveState(phase = DrivePhase.NAVIGATING, dayId = 68L), today))
        assertEquals(68L, poiStopDayId(DriveState(phase = DrivePhase.AT_STOP, dayId = 68L), today))
        assertEquals(0, todayCalls)
    }

    @Test
    fun `ajon ulkopuolella kohde lisätään tähän päivään`() = runTest {
        assertEquals(69L, poiStopDayId(DriveState(), today))
        assertEquals(69L, poiStopDayId(DriveState(phase = DrivePhase.FINISHED, dayId = 68L), today))
        assertEquals(69L, poiStopDayId(DriveState(phase = DrivePhase.NAVIGATING, dayId = null), today))
    }
}
