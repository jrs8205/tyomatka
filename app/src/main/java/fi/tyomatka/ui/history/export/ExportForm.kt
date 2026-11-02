package fi.tyomatka.ui.history.export

import fi.tyomatka.R
import fi.tyomatka.data.report.DEFAULT_EXPORT_CHOICE
import fi.tyomatka.data.report.ExportChoice
import fi.tyomatka.data.report.ExportPeriod
import fi.tyomatka.data.report.ExportSummary
import fi.tyomatka.data.report.bounds
import fi.tyomatka.data.report.exportPeriod
import fi.tyomatka.ui.formatDayRange
import java.time.LocalDate

/** Viennin paneelin valinta: jakso tai koko varmuuskopio; oma aikaväli valitaan kalenterista. */
data class ExportForm(
    val choice: ExportChoice = DEFAULT_EXPORT_CHOICE,
    val custom: Pair<LocalDate, LocalDate>? = null,
) {
    /** Valittu jakso; oma aikaväli ilman päiviä → null. */
    fun period(today: LocalDate): ExportPeriod? = exportPeriod(choice, today, custom)

    /**
     * Vienti on sallittu, kun jaksolla on työpäiviä tai kirjauksia. Koko varmuuskopio sallitaan myös pelkistä
     * autoprofiileista (esittelyssä annettu auto on varmuuskopioitavaa ennen ensimmäistä kirjausta) tai pelkistä
     * tallennetuista reiteistä.
     */
    fun canExport(summary: ExportSummary?): Boolean =
        summary != null && (!summary.isEmpty || choice == ExportChoice.FULL_BACKUP && (summary.cars > 0 || summary.templates > 0))
}

/**
 * Viennin valmistumisviesti ja sen jakso: koko varmuuskopio käy palautukseen, mutta jakson tiedosto sisältää vain
 * jakson tiedot, eikä sitä saa luulla koko varmuuskopioksi.
 */
fun exportDoneMessage(period: ExportPeriod): Pair<Int, String?> {
    val (from, to) = period.bounds() ?: return R.string.work_export_done to null
    return R.string.work_export_done_period to formatDayRange(from.toEpochDay(), to.toEpochDay())
}

/**
 * Aikavälivalitsimen otsikko: valitut päivät sovelluksen muodossa ("1.–16.10.2026") tai paikkamerkit. Sitova väli
 * ennen ajatusviivaa, joten kapealla näytöllä rivi vaihtuu vain viivan jälkeen eikä kesken sanan.
 */
fun rangeHeadline(startDay: Long?, endDay: Long?, startLabel: String, endLabel: String): String = when {
    startDay != null && endDay != null -> formatDayRange(startDay, endDay)
    startDay != null -> "${formatDayRange(startDay, startDay)}\u00A0– $endLabel"
    else -> "$startLabel\u00A0– $endLabel"
}
