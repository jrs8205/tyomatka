package fi.tyomatka.ui.history

import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkFixTest {

    private val zone: ZoneId = ZoneId.of("Europe/Helsinki")
    private val thursday: LocalDate = LocalDate.of(2026, 9, 3)

    private fun at(hour: Int, minute: Int): Long =
        thursday.atTime(hour, minute).atZone(zone).toEpochSecond()

    /** Torstain 3.9. unohtunut päivä: aloitettu, lopetusta ei kirjattu. */
    private val openSegment = WorkSegmentEntity(
        id = 30,
        routeDayId = 1,
        startEpochSec = at(9, 53),
        endEpochSec = null,
        odometerStartKm = 467_849,
        odometerEndKm = null,
    )

    private val closedSegment = openSegment.copy(
        endEpochSec = at(17, 26),
        odometerEndKm = 468_065,
    )

    private fun edit(
        start: String = "9.53",
        end: String = "",
        startKm: String = "467849",
        endKm: String = "468065",
    ) = FixEdit(startTime = start, endTime = end, startKm = startKm, endKm = endKm)

    @Test
    fun `korjauslomakkeen alkuarvot vastaavat segmenttia ja muutos tunnistetaan`() {
        val initial = FixEdit.of(closedSegment)
        assertEquals(FixEdit(startTime = "09.53", endTime = "17.26", startKm = "467849", endKm = "468065"), initial)
        assertEquals(FixEdit(startTime = "09.53", endTime = "", startKm = "467849", endKm = ""), FixEdit.of(openSegment))
        assertFalse(fixesChanged(listOf(closedSegment), mapOf(30L to initial)))
        assertTrue(fixesChanged(listOf(closedSegment), mapOf(30L to initial.copy(endKm = "468100"))))
        assertTrue(fixesChanged(listOf(closedSegment), mapOf(30L to initial.copy(plate = "ABC-123"))))
    }

    @Test
    fun `avoimen segmentin tyhja lopetus pitaa paivan kesken`() {
        val fix = resolveSegmentFix(openSegment, edit(end = ""), zone)
        assertNotNull(fix)
        assertNull(fix.endEpochSec)
        assertEquals(468_065, fix.endKm)
    }

    @Test
    fun `avoimen segmentin lopetusaika paattaa paivan segmentin omana paivana`() {
        val fix = resolveSegmentFix(openSegment, edit(end = "17.30"), zone)
        assertNotNull(fix)
        assertEquals(at(9, 53), fix.startEpochSec)
        assertEquals(at(17, 30), fix.endEpochSec)
    }

    @Test
    fun `avoimen segmentin lopetus ennen aloitusta ei kelpaa`() {
        assertNull(resolveSegmentFix(openSegment, edit(end = "9.00"), zone))
    }

    @Test
    fun `avoimen segmentin virheellinen lopetusteksti ei kelpaa`() {
        assertNull(resolveSegmentFix(openSegment, edit(end = "17"), zone))
    }

    @Test
    fun `paatetyn segmentin tyhja lopetus ei kelpaa`() {
        assertNull(resolveSegmentFix(closedSegment, edit(end = ""), zone))
    }

    @Test
    fun `paatetyn segmentin ajat ja lukemat jasentyvat`() {
        val fix = resolveSegmentFix(
            closedSegment,
            edit(start = "10:05", end = "1740", startKm = "467850", endKm = ""),
            zone,
        )
        assertNotNull(fix)
        assertEquals(at(10, 5), fix.startEpochSec)
        assertEquals(at(17, 40), fix.endEpochSec)
        assertEquals(467_850, fix.startKm)
        assertNull(fix.endKm)
    }

    @Test
    fun `virheellinen aloitusaika ei kelpaa`() {
        assertNull(resolveSegmentFix(closedSegment, edit(start = "abc", end = "17.26"), zone))
    }

    @Test
    fun `rekkari normalisoidaan korjauksessa`() {
        val fix = resolveSegmentFix(closedSegment, edit(end = "17.26").copy(plate = "abc 123"), zone)
        assertNotNull(fix)
        assertEquals("ABC-123", fix.licensePlate)
        assertNull(resolveSegmentFix(closedSegment, edit(end = "17.26"), zone)?.licensePlate)
    }

    @Test
    fun `decreasing negative or unparseable readings cannot silently erase kilometres`() {
        for (reading in listOf("467848", "-1", "99999999999999999999999", "oops")) {
            assertNull(resolveSegmentFix(closedSegment, edit(end = "17.26", endKm = reading), zone))
        }
        assertNull(resolveSegmentFix(closedSegment, edit(end = "17.26", startKm = "oops"), zone))
    }

    @Test
    fun `editing plate preserves seconds and next day end of overnight shift`() {
        val overnight = closedSegment.copy(
            startEpochSec = at(22, 0) + 37,
            endEpochSec = thursday.plusDays(1).atTime(6, 0).atZone(zone).toEpochSecond() + 17,
        )
        val fix = resolveSegmentFix(overnight, edit(start = "22.00", end = "6.00").copy(plate = "abc123"), zone)!!
        assertEquals(overnight.startEpochSec, fix.startEpochSec)
        assertEquals(overnight.endEpochSec, fix.endEpochSec)
        assertEquals("ABC-123", fix.licensePlate)
    }

    @Test
    fun `nonexistent local clock during spring transition is rejected`() {
        val spring = LocalDate.of(2026, 3, 29)
        val segment = closedSegment.copy(
            startEpochSec = spring.atTime(1, 0).atZone(zone).toEpochSecond(),
            endEpochSec = spring.atTime(6, 0).atZone(zone).toEpochSecond(),
        )
        assertNull(resolveSegmentFix(segment, edit(start = "3.30", end = "6.00"), zone))
    }

    @Test
    fun `editing plate preserves the later repeated Finnish winter clock hour`() {
        val date = LocalDate.of(2026, 10, 25)
        val start = date.atTime(3, 15).atZone(zone).withLaterOffsetAtOverlap().toEpochSecond()
        val end = date.atTime(3, 45).atZone(zone).withLaterOffsetAtOverlap().toEpochSecond()
        val segment = closedSegment.copy(startEpochSec = start, endEpochSec = end)
        val fix = resolveSegmentFix(segment, edit(start = "3.15", end = "3.45").copy(plate = "ABC-123"), zone)!!
        assertEquals(start, fix.startEpochSec)
        assertEquals(end, fix.endEpochSec)
    }
}
