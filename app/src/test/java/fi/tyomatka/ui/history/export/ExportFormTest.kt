package fi.tyomatka.ui.history.export

import fi.tyomatka.R
import fi.tyomatka.data.report.ExportChoice
import fi.tyomatka.data.report.ExportPeriod
import fi.tyomatka.data.report.ExportSummary
import fi.tyomatka.data.report.FillGroup
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportFormTest {
    private val today = LocalDate.of(2026, 10, 18)
    private val withEntries = ExportSummary(workDays = 12, counts = mapOf(FillGroup.FILL to 3), photos = 5, estimatedBytes = 1_000_000)
    private val empty = ExportSummary(workDays = 0, counts = emptyMap(), photos = 0, estimatedBytes = 200_000)

    @Test
    fun `oletuksena on tämä kuukausi`() {
        val form = ExportForm()
        assertEquals(ExportChoice.THIS_MONTH, form.choice)
        assertEquals(ExportPeriod.Month(YearMonth.of(2026, 10)), form.period(today))
        assertTrue(form.canExport(withEntries))
    }

    @Test
    fun `oma aikaväli ilman päiviä ei anna jaksoa eikä vientiä`() {
        val form = ExportForm(ExportChoice.CUSTOM)
        assertNull(form.period(today))
        assertFalse(form.canExport(null))
        val chosen = form.copy(custom = LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 10, 16))
        assertEquals(ExportPeriod.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 16)), chosen.period(today))
    }

    @Test
    fun `valmistumisviesti kertoo, kun tiedosto sisältää vain jakson`() {
        assertEquals(R.string.work_export_done to null, exportDoneMessage(ExportPeriod.Full))
        assertEquals(
            R.string.work_export_done_period to "1.–31.10.2026",
            exportDoneMessage(ExportPeriod.Month(YearMonth.of(2026, 10))),
        )
    }

    @Test
    fun `aikavälin otsikko on lyhyt ja katkeaa vain ajatusviivan jälkeen`() {
        val nb = '\u00A0'
        val oct1 = LocalDate.of(2026, 10, 1).toEpochDay()
        val oct16 = LocalDate.of(2026, 10, 16).toEpochDay()
        assertEquals("Alkupäivä${nb}– Loppupäivä", rangeHeadline(null, null, "Alkupäivä", "Loppupäivä"))
        assertEquals("1.10.2026${nb}– Loppupäivä", rangeHeadline(oct1, null, "Alkupäivä", "Loppupäivä"))
        assertEquals("1.–16.10.2026", rangeHeadline(oct1, oct16, "Alkupäivä", "Loppupäivä"))
    }

    @Test
    fun `tyhjä jakso estää viennin, koko varmuuskopio sallii pelkät autot`() {
        assertFalse(ExportForm().canExport(empty))
        val full = ExportForm(ExportChoice.FULL_BACKUP)
        assertEquals(ExportPeriod.Full, full.period(today))
        assertFalse(full.canExport(empty))
        assertTrue(full.canExport(empty.copy(cars = 1)))
        assertFalse(ExportForm().canExport(empty.copy(cars = 1)))
        // Tallennetut reitit kulkevat vain koko varmuuskopiossa.
        assertTrue(full.canExport(empty.copy(templates = 1)))
        assertFalse(ExportForm().canExport(empty.copy(templates = 1)))
    }
}
