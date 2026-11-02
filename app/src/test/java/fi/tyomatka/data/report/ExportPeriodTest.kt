package fi.tyomatka.data.report

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportPeriodTest {
    private val today = LocalDate.of(2026, 10, 18)

    @Test
    fun `tämä ja edellinen kuukausi lasketaan tämän päivän mukaan`() {
        assertEquals(ExportPeriod.Month(YearMonth.of(2026, 10)), exportPeriod(ExportChoice.THIS_MONTH, today, null))
        assertEquals(ExportPeriod.Month(YearMonth.of(2026, 9)), exportPeriod(ExportChoice.PREVIOUS_MONTH, today, null))
        // Vuodenvaihde: tammikuun edellinen kuukausi on edellisen vuoden joulukuu.
        val january = LocalDate.of(2026, 1, 5)
        assertEquals(ExportPeriod.Month(YearMonth.of(2025, 12)), exportPeriod(ExportChoice.PREVIOUS_MONTH, january, null))
    }

    @Test
    fun `tämä ja edellinen vuosi lasketaan tämän päivän mukaan`() {
        assertEquals(ExportPeriod.Year(2026), exportPeriod(ExportChoice.THIS_YEAR, today, null))
        assertEquals(ExportPeriod.Year(2025), exportPeriod(ExportChoice.PREVIOUS_YEAR, today, null))
    }

    @Test
    fun `varmuuskopio on koko aineisto`() {
        assertEquals(ExportPeriod.Full, exportPeriod(ExportChoice.FULL_BACKUP, today, null))
    }

    @Test
    fun `oma väli vaatii päivät ja kääntyy oikein päin`() {
        assertNull(exportPeriod(ExportChoice.CUSTOM, today, null))
        val reversed = LocalDate.of(2026, 10, 16) to LocalDate.of(2026, 9, 1)
        assertEquals(
            ExportPeriod.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 16)),
            exportPeriod(ExportChoice.CUSTOM, today, reversed),
        )
    }

    @Test
    fun `jakson päät ovat kuukauden ja vuoden ensimmäinen ja viimeinen päivä`() {
        assertEquals(
            LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28),
            ExportPeriod.Month(YearMonth.of(2026, 2)).bounds(),
        )
        assertEquals(LocalDate.of(2024, 1, 1) to LocalDate.of(2024, 12, 31), ExportPeriod.Year(2024).bounds())
        assertEquals(
            LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 10, 16),
            ExportPeriod.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 16)).bounds(),
        )
        assertNull(ExportPeriod.Full.bounds())
    }

    @Test
    fun `jakso sisältää molemmat päänsä ja koko aineisto kaiken`() {
        val october = ExportPeriod.Month(YearMonth.of(2026, 10))
        assertTrue(october.includes(LocalDate.of(2026, 10, 1)))
        assertTrue(october.includes(LocalDate.of(2026, 10, 31)))
        assertFalse(october.includes(LocalDate.of(2026, 9, 30)))
        assertFalse(october.includes(LocalDate.of(2026, 11, 1)))
        assertTrue(ExportPeriod.Full.includes(LocalDate.of(1970, 1, 1)))
        assertTrue(ExportPeriod.Full.includes(LocalDate.of(2099, 12, 31)))
    }

    @Test
    fun `tiedostonimi kertoo jakson ja varmuuskopio päivän`() {
        assertEquals("tyomatka-2026-10.html", exportFileName(ExportPeriod.Month(YearMonth.of(2026, 10)), today))
        assertEquals("tyomatka-2026.html", exportFileName(ExportPeriod.Year(2026), today))
        assertEquals(
            "tyomatka-2026-09-01_2026-10-16.html",
            exportFileName(ExportPeriod.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 16)), today),
        )
        assertEquals("tyomatka-varmuuskopio-2026-10-18.html", exportFileName(ExportPeriod.Full, today))
    }

    @Test
    fun `oletusvalinta on tämä kuukausi`() {
        assertEquals(ExportChoice.THIS_MONTH, DEFAULT_EXPORT_CHOICE)
    }
}
