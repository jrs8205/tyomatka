package fi.tyomatka.ui.history

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.PeriodFuel
import fi.tyomatka.data.repo.PeriodFuels
import fi.tyomatka.ui.history.forms.FillWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Päivänäkymän lajiyhteenvedot ja nuolirivin alarivi. */
class DayDetailTest {

    private fun fillup(kind: FuelKind, liters: Double) = FuelFillupEntity(
        epochSec = 1_794_304_800,
        licensePlate = "ABC-123",
        odometerKm = 125_000,
        liters = liters,
        totalCost = liters * 1.8,
        pricePerLiter = 1.8,
        kind = kind,
        currency = "EUR",
    )

    private fun fuel(kind: FuelKind, liters: Double) =
        PeriodFuel(kind, liters, mapOf("EUR" to liters * 1.8), 1.8, null, null, currency = "EUR")

    @Test
    fun `yksi tankkaus ei tee yhteenvetoa`() {
        val fuels = PeriodFuels(listOf(fuel(FuelKind.DIESEL, 32.0)))
        assertEquals(emptyList(), kindSummaries(listOf(fillup(FuelKind.DIESEL, 32.0)), fuels))
    }

    @Test
    fun `kaksi dieselia ja yksi AdBlue tekee diesel-yhteenvedon`() {
        val diesel = fuel(FuelKind.DIESEL, 52.0)
        val adBlue = fuel(FuelKind.ADBLUE, 10.0)
        val fillups = listOf(fillup(FuelKind.DIESEL, 32.0), fillup(FuelKind.DIESEL, 20.0), fillup(FuelKind.ADBLUE, 10.0))
        assertEquals(listOf(diesel), kindSummaries(fillups, PeriodFuels(listOf(diesel, adBlue))))
    }

    @Test
    fun `kaksi kumpaakin tekee molemmat yhteenvedot`() {
        val diesel = fuel(FuelKind.DIESEL, 52.0)
        val adBlue = fuel(FuelKind.ADBLUE, 12.0)
        val fillups = listOf(
            fillup(FuelKind.DIESEL, 32.0), fillup(FuelKind.ADBLUE, 10.0),
            fillup(FuelKind.DIESEL, 20.0), fillup(FuelKind.ADBLUE, 2.0),
        )
        assertEquals(listOf(diesel, adBlue), kindSummaries(fillups, PeriodFuels(listOf(diesel, adBlue))))
    }

    @Test
    fun `ilman polttoainetietoja ei ole yhteenvetoja`() {
        assertEquals(emptyList(), kindSummaries(listOf(fillup(FuelKind.DIESEL, 1.0), fillup(FuelKind.DIESEL, 2.0)), null))
    }

    @Test
    fun `tankkausten otsikko lajin mukaan`() {
        assertEquals(FillWord.CHARGE, fillupsTitleWord(listOf(fillup(FuelKind.ELECTRIC, 30.0), fillup(FuelKind.ELECTRIC, 12.0))))
        assertEquals(FillWord.GAS, fillupsTitleWord(listOf(fillup(FuelKind.GAS, 12.0))))
        assertEquals(FillWord.FILL, fillupsTitleWord(listOf(fillup(FuelKind.DIESEL, 32.0), fillup(FuelKind.ADBLUE, 2.0))))
        assertEquals(FillWord.FILL, fillupsTitleWord(listOf(fillup(FuelKind.DIESEL, 32.0), fillup(FuelKind.ELECTRIC, 20.0))))
        assertEquals(FillWord.FILL, fillupsTitleWord(listOf(fillup(FuelKind.ELECTRIC, 20.0), fillup(FuelKind.DIESEL, 32.0))))
    }

    private fun day(hasWork: Boolean = true, missing: Boolean = false, note: Boolean = false) = WorkPeriodRow(
        key = "d20402", label = "Ti 10.11.2026", km = 86, durationSec = 3600, ongoing = false,
        missingReadings = missing, hasWork = hasWork, date = 20_402, hasNote = note,
    )

    private fun segment(start: Long?, end: Long?, startSec: Long = 1_000, endSec: Long? = 2_000) =
        WorkSegmentEntity(routeDayId = 1, startEpochSec = startSec, endEpochSec = endSec, odometerStartKm = start, odometerEndKm = end)

    @Test
    fun `pelkalla tankkauspaivalla ei ole korjausta`() {
        assertEquals(emptyList(), dayActions(day(hasWork = false)))
    }

    @Test
    fun `tyopaivalla korjaus ja puuttuvista lukemista oma chip`() {
        assertEquals(listOf(DayAction.FIX), dayActions(day()))
        assertEquals(listOf(DayAction.MISSING_READINGS, DayAction.FIX), dayActions(day(missing = true)))
        assertEquals(listOf(DayAction.FIX, DayAction.NOTE), dayActions(day(note = true)))
    }

    @Test
    fun `lisatiedot nakyvat myos ilman tyota`() {
        assertEquals(listOf(DayAction.NOTE), dayActions(day(hasWork = false, note = true)))
    }

    @Test
    fun `valmis ajo kertoo kilometrit`() {
        assertEquals(SegmentDisplay(retro = false, km = 86, problem = false), segmentDisplay(segment(475_282, 475_368)))
    }

    @Test
    fun `kaynnissa oleva ajo ei ole virhe`() {
        assertEquals(SegmentDisplay(retro = false, km = null, problem = false), segmentDisplay(segment(528_700, null, endSec = null)))
    }

    @Test
    fun `takautuva kirjaus ilman loppulukemaa on puute`() {
        assertEquals(SegmentDisplay(retro = true, km = null, problem = true), segmentDisplay(segment(88_210, null, startSec = 5_000, endSec = 5_000)))
    }

    @Test
    fun `ristiriitainen tai puuttuva alkulukema on puute`() {
        assertEquals(SegmentDisplay(retro = false, km = null, problem = true), segmentDisplay(segment(475_368, 475_282)))
        assertEquals(SegmentDisplay(retro = false, km = null, problem = true), segmentDisplay(segment(null, 475_282)))
    }

    @Test
    fun `alarivi kertoo lahipaivien etaisyyden`() {
        val today = 20_403L
        assertEquals(0, daysAgo(today, today))
        assertEquals(1, daysAgo(today - 1, today))
        assertEquals(6, daysAgo(today - 6, today))
    }

    @Test
    fun `vanhalla tai tulevalla paivalla ei ole alarivia`() {
        val today = 20_403L
        assertNull(daysAgo(today - 7, today))
        assertNull(daysAgo(today + 1, today))
    }
}
