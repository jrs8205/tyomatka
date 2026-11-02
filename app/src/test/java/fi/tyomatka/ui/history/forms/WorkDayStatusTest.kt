package fi.tyomatka.ui.history.forms

import fi.tyomatka.data.db.WorkSegmentEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkDayStatusTest {
    private val day = 1_800_000_000L

    private fun segment(startHour: Int, endHour: Int?) = WorkSegmentEntity(
        id = startHour.toLong(),
        routeDayId = 1,
        startEpochSec = day + startHour * 3600L,
        endEpochSec = endHour?.let { day + it * 3600L },
    )

    @Test
    fun `ilman segmenttejä päivä on aloittamatta`() {
        val status = workDayStatus(emptyList(), nowEpochSec = day + 20 * 3600)
        assertNull(status.startedAtEpochSec)
        assertNull(status.endedAtEpochSec)
        assertEquals(0, status.workedSec)
        assertFalse(status.ongoing)
    }

    @Test
    fun `päättyneen päivän työaika on segmenttien summa ilman taukoa eikä kasva illalla`() {
        val status = workDayStatus(listOf(segment(7, 12), segment(13, 16)), nowEpochSec = day + 20 * 3600)
        assertEquals(day + 7 * 3600, status.startedAtEpochSec)
        assertEquals(day + 16 * 3600, status.endedAtEpochSec)
        assertEquals(8 * 3600L, status.workedSec)
        assertFalse(status.ongoing)
    }

    @Test
    fun `avoin segmentti lasketaan nykyhetkeen asti`() {
        val status = workDayStatus(listOf(segment(7, null)), nowEpochSec = day + 9 * 3600 + 1800)
        assertTrue(status.ongoing)
        assertNull(status.endedAtEpochSec)
        assertEquals(2 * 3600L + 1800, status.workedSec)
    }
}
