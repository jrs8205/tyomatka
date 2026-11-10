package fi.tyomatka.ui.history

import fi.tyomatka.data.db.WorkSegmentEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodaySummaryTest {
    private val day = 1_800_000_000L

    private fun segment(
        id: Long,
        startHour: Int,
        endHour: Int?,
        startKm: Long?,
        endKm: Long?,
        plate: String? = "ABC-123",
    ) = WorkSegmentEntity(
        id = id,
        routeDayId = 1,
        startEpochSec = day + startHour * 3600L,
        endEpochSec = endHour?.let { day + it * 3600L },
        odometerStartKm = startKm,
        odometerEndKm = endKm,
        licensePlate = plate,
    )

    @Test
    fun `tyhjä päivä on aloittamatta ilman lukuja`() {
        val summary = todaySummary(emptyList(), nowEpochSec = day + 8 * 3600)
        assertNull(summary.status.startedAtEpochSec)
        assertEquals(0, summary.km)
        assertEquals(emptyList(), summary.plates)
        assertEquals(emptyList(), summary.readings)
        assertFalse(summary.missingReadings)
    }

    @Test
    fun `kilometrit summataan päättyneistä segmenteistä ja autot listataan kerran`() {
        val segments = listOf(
            segment(1, 7, 12, 1000, 1100, "ABC-123"),
            segment(2, 12, 16, 5000, 5050, "XYZ-987"),
            segment(3, 16, null, 5050, null, "ABC-123"),
        )
        val summary = todaySummary(segments, nowEpochSec = day + 17 * 3600)
        assertEquals(150, summary.km)
        assertEquals(listOf("ABC-123", "XYZ-987"), summary.plates)
        // Lukemat autoittain: ensimmäisen auton avoin jakso ei anna loppulukemaa, eikä toisen auton lukema sekoitu.
        assertEquals(
            listOf(CarReadings("ABC-123", 1000, 1100), CarReadings("XYZ-987", 5000, 5050)),
            summary.readings,
        )
        assertTrue(summary.status.ongoing)
        assertFalse(summary.missingReadings)
    }

    @Test
    fun `päättynyt segmentti ilman loppulukemaa merkitään puuttuvaksi`() {
        val summary = todaySummary(listOf(segment(1, 7, 12, 1000, null)), nowEpochSec = day + 13 * 3600)
        assertTrue(summary.missingReadings)
        assertEquals(0, summary.km)
        assertEquals(listOf(CarReadings("ABC-123", 1000, null)), summary.readings)
    }

    @Test
    fun `yhden auton päivä antaa yhden lukemarivin`() {
        val closed = todaySummary(listOf(segment(1, 7, 12, 12_300, 12_350)), nowEpochSec = day + 13 * 3600)
        assertEquals(listOf(CarReadings("ABC-123", 12_300, 12_350)), closed.readings)

        val open = todaySummary(listOf(segment(1, 7, null, 12_300, null)), nowEpochSec = day + 8 * 3600)
        assertEquals(listOf(CarReadings("ABC-123", 12_300, null)), open.readings)
    }

    @Test
    fun `autonvaihdon jälkeen lukemat ovat autoittain eikä avoin jakso anna loppulukemaa`() {
        val segments = listOf(
            segment(1, 7, 12, 12_300, 12_350, "ABC-123"),
            segment(2, 12, null, 45_000, null, "XYZ-789"),
        )
        val summary = todaySummary(segments, nowEpochSec = day + 13 * 3600)
        assertEquals(
            listOf(CarReadings("ABC-123", 12_300, 12_350), CarReadings("XYZ-789", 45_000, null)),
            summary.readings,
        )
        assertEquals(50, summary.km)
    }

    @Test
    fun `saman auton kaksi jaksoa yhdistyvät ensimmäisestä alkulukemasta viimeiseen loppulukemaan`() {
        val segments = listOf(
            segment(1, 7, 10, 1000, 1040, "ABC-123"),
            segment(2, 11, 15, 1040, 1100, "ABC-123"),
        )
        val summary = todaySummary(segments, nowEpochSec = day + 16 * 3600)
        assertEquals(listOf(CarReadings("ABC-123", 1000, 1100)), summary.readings)
        assertEquals(listOf("ABC-123"), summary.plates)
    }

    @Test
    fun `jakso ilman autoa on oma rivinsä`() {
        val segments = listOf(
            segment(1, 7, 12, 1000, 1100, plate = null),
            segment(2, 12, null, 5000, null, "ABC-123"),
        )
        val summary = todaySummary(segments, nowEpochSec = day + 13 * 3600)
        assertEquals(listOf(CarReadings(null, 1000, 1100), CarReadings("ABC-123", 5000, null)), summary.readings)
        assertEquals(listOf("ABC-123"), summary.plates)

        // Pelkkä auto ilman rekisterinumeroa on yhden auton päivä.
        val single = todaySummary(listOf(segment(3, 7, 12, 1000, 1100, plate = null)), nowEpochSec = day + 13 * 3600)
        assertEquals(listOf(CarReadings(null, 1000, 1100)), single.readings)
    }

    @Test
    fun `tilarivin auto on käynnissä avoimen jakson auto ja päättyneenä päivän viimeisen jakson auto`() {
        val now = day + 13 * 3600
        // Käynnissä: avoimen jakson auto, vaikka seuraavan ajon auto olisi toinen.
        val ongoing = todaySummary(listOf(segment(1, 7, null, 1000, null, "ABC-123")), now, nextPlate = "EVO-456")
        assertEquals("ABC-123", ongoing.statusPlate)
        // Päättynyt: päivän viimeisen jakson auto.
        val ended = todaySummary(
            listOf(segment(1, 7, 10, 1000, 1040, "ABC-123"), segment(2, 11, 12, 5000, 5050, "XYZ-789")),
            now,
            nextPlate = "EVO-456",
        )
        assertEquals("XYZ-789", ended.statusPlate)
        // Aloittamatta: seuraavan ajon auto (Autot-näkymän valinta tai johdettu oletus).
        assertEquals("EVO-456", todaySummary(emptyList(), now, nextPlate = "EVO-456").statusPlate)
    }

    @Test
    fun `tilarivillä ei ole autoa, kun autoa ei tiedetä tai jaksolla ei ole rekisterinumeroa`() {
        val now = day + 13 * 3600
        assertNull(todaySummary(emptyList(), now, nextPlate = null).statusPlate)
        assertNull(todaySummary(listOf(segment(1, 7, null, 1000, null, plate = null)), now, nextPlate = "EVO-456").statusPlate)
        // Päivän viimeinen jakso ilman rekisterinumeroa, vaikka aiemmalla oli.
        val lastWithout = listOf(segment(1, 7, 10, 1000, 1040, "ABC-123"), segment(2, 11, 12, 1040, 1100, plate = null))
        assertNull(todaySummary(lastWithout, now, nextPlate = "EVO-456").statusPlate)
    }

    @Test
    fun `autonvaihto ja rekisterinumeron korjaus vaihtavat tilarivin auton heti`() {
        val now = day + 13 * 3600
        val switched = listOf(segment(1, 7, 12, 1000, 1100, "ABC-123"), segment(2, 12, null, 5000, null, "XYZ-789"))
        assertEquals("XYZ-789", todaySummary(switched, now).statusPlate)
        val fixed = listOf(segment(1, 7, null, 1000, null, "ABC-123").copy(licensePlate = "XYZ-789"))
        assertEquals("XYZ-789", todaySummary(fixed, now).statusPlate)
    }

    @Test
    fun `tilarivi vaihtuu vain erottimen kohdalta eikä rekisterinumero katkea`() {
        // Osien välilyönnit ovat sitovia ja rekisterinumeron yhdysmerkki sitova (U+2011).
        assertEquals(
            "Käynnissä\u00A0alkaen\u00A07.12\u00A0· 2\u00A0h\u00A010\u00A0min\u00A0· ABC\u2011123",
            todayStatusLine("Käynnissä alkaen 7.12 · 2 h 10 min", "ABC-123"),
        )
        assertEquals(
            "Päättynyt\u00A015.03\u00A0· työaika\u00A07\u00A0h\u00A051\u00A0min\u00A0· ABC\u2011123",
            todayStatusLine("Päättynyt 15.03 · työaika 7 h 51 min", "ABC-123"),
        )
        assertEquals("Työpäivä\u00A0aloittamatta\u00A0· ABC\u2011123", todayStatusLine("Työpäivä aloittamatta", "ABC-123"))
        // Ilman autoa rivi on pelkkä tila.
        assertEquals("Työpäivä\u00A0aloittamatta", todayStatusLine("Työpäivä aloittamatta", null))
        // Ulkomainen rekisterinumero välilyönnein pysyy myös yhdessä.
        assertEquals("Päättynyt\u00A015.03\u00A0· AB\u00A0123\u00A0CD", todayStatusLine("Päättynyt 15.03", "AB 123 CD"))
    }
}
