package fi.tyomatka.ui.history

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Muiden jaksojen päiväriveillä näkyy, onko päivällä lisätietoja tai tankkauskuvia, kuten 1.0.0:n päiväkorteissa. */
class RowMarkersTest {

    private fun fillup(id: Long, note: String = "") = FuelFillupEntity(
        id = id,
        epochSec = 1_794_304_800,
        licensePlate = "ABC-123",
        odometerKm = 125_000,
        liters = 40.0,
        totalCost = 72.0,
        pricePerLiter = 1.8,
        kind = FuelKind.DIESEL,
        currency = "EUR",
        note = note,
    )

    private fun day(hasNote: Boolean = false, fillups: List<FuelFillupEntity> = emptyList()) = WorkPeriodRow(
        key = "d20403", label = "Ke 11.11.2026", km = 50, durationSec = 3_600, ongoing = false,
        missingReadings = false, fillups = fillups, date = 20_403, hasNote = hasNote,
    )

    @Test
    fun `paivan lisatiedot nakyvat merkkina`() {
        assertEquals(RowMarkers(note = true, photos = 0), rowMarkers(day(hasNote = true), WorkPeriod.DAY) { 0 })
    }

    @Test
    fun `tankkauksen lisatiedot ja kuvat nakyvat`() {
        val row = day(fillups = listOf(fillup(1, note = "Kuitti hukassa"), fillup(2)))
        val photos = mapOf(1L to 2, 2L to 1)
        assertEquals(RowMarkers(note = true, photos = 3), rowMarkers(row, WorkPeriod.DAY) { photos[it] ?: 0 })
    }

    @Test
    fun `tyhja lisatieto ei ole merkki`() {
        val row = day(fillups = listOf(fillup(1, note = "   ")))
        assertEquals(RowMarkers(note = false, photos = 0), rowMarkers(row, WorkPeriod.DAY) { 0 })
    }

    @Test
    fun `viikko kuukausi ja vuosi eivat nayta merkkeja`() {
        val row = day(hasNote = true, fillups = listOf(fillup(1, note = "x")))
        for (period in listOf(WorkPeriod.WEEK, WorkPeriod.MONTH, WorkPeriod.YEAR)) {
            assertEquals(RowMarkers(note = false, photos = 0), rowMarkers(row, period) { 5 })
        }
    }
}
