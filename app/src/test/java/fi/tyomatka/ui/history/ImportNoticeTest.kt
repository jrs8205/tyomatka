package fi.tyomatka.ui.history

import fi.tyomatka.R
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.WorkImportResult
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportNoticeTest {

    @Test
    fun `ilman kuvia ilmoitus on entinen`() {
        val notice = importNotice(WorkImportResult(added = 3, skipped = 1, fuelAdded = 2, fuelSkipped = 4))

        assertEquals(R.string.work_import_done, notice.textRes)
        assertEquals(listOf(3, 2, 5), notice.counts)
    }

    @Test
    fun `kuvien kanssa ilmoitus kertoo kuvamäärän`() {
        val notice = importNotice(WorkImportResult(added = 0, skipped = 2, fuelAdded = 1, fuelSkipped = 0, photosAdded = 7))

        assertEquals(R.string.work_import_done_photos, notice.textRes)
        assertEquals(listOf(0, 1, 7, 2), notice.counts)
    }

    @Test
    fun `tallennetut reitit kulkevat ilmoitukseen`() {
        assertEquals(2, importNotice(WorkImportResult(1, 0, 0, 0, templates = 2)).templates)
        assertEquals(0, importNotice(WorkImportResult(1, 0, 0, 0)).templates)
    }

    @Test
    fun `jakson varmuuskopiosta ilmoitus kertoo jakson`() {
        val period = LocalDate.of(2026, 10, 1).toEpochDay() to LocalDate.of(2026, 10, 18).toEpochDay()
        assertEquals("1.–18.10.2026", importNotice(WorkImportResult(1, 0, 2, 0, period = period)).period)
        assertNull(importNotice(WorkImportResult(1, 0, 2, 0)).period)
    }

    @Test
    fun `kirjausten sana seuraa varmuuskopion kirjausten lajeja`() {
        // Tuodut ja ohitetut yhdessä; ilman kirjauksia sana on tankkaus.
        fun word(vararg kinds: FuelKind) = importNotice(WorkImportResult(0, 0, 0, 0, fuelKinds = kinds.toSet())).wordRes

        assertEquals(R.string.work_import_kinds_fill, word())
        assertEquals(R.string.work_import_kinds_fill, word(FuelKind.DIESEL, FuelKind.ADBLUE))
        assertEquals(R.string.work_import_kinds_charge, word(FuelKind.ELECTRIC))
        assertEquals(R.string.work_import_kinds_gas, word(FuelKind.GAS))
        assertEquals(R.string.work_import_kinds_fill_charge, word(FuelKind.PETROL, FuelKind.ELECTRIC))
        assertEquals(R.string.work_import_kinds_all, word(FuelKind.DIESEL, FuelKind.ELECTRIC, FuelKind.GAS))
    }
}
