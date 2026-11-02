package fi.tyomatka.data.report

import java.time.LocalDate
import java.time.YearMonth

/** Raportin viennin jakso: kalenterikuukausi, kalenterivuosi, oma väli tai koko aineisto varmuuskopioksi. */
sealed interface ExportPeriod {
    data class Month(val month: YearMonth) : ExportPeriod
    data class Year(val year: Int) : ExportPeriod
    data class Range(val from: LocalDate, val to: LocalDate) : ExportPeriod
    data object Full : ExportPeriod
}

/** Vientidialogin valinnat siinä järjestyksessä kuin ne näytetään. */
enum class ExportChoice { THIS_MONTH, PREVIOUS_MONTH, THIS_YEAR, PREVIOUS_YEAR, CUSTOM, FULL_BACKUP }

val DEFAULT_EXPORT_CHOICE = ExportChoice.THIS_MONTH

/**
 * Muuntaa valinnan jaksoksi tämän päivän mukaan. [custom] on oman välin päivät; ilman niitä CUSTOM palauttaa null,
 * ja väärin päin annettu väli käännetään, jotta jakso ei koskaan jää tyhjäksi.
 */
fun exportPeriod(choice: ExportChoice, today: LocalDate, custom: Pair<LocalDate, LocalDate>?): ExportPeriod? {
    val thisMonth = YearMonth.from(today)
    return when (choice) {
        ExportChoice.THIS_MONTH -> ExportPeriod.Month(thisMonth)
        ExportChoice.PREVIOUS_MONTH -> ExportPeriod.Month(thisMonth.minusMonths(1))
        ExportChoice.THIS_YEAR -> ExportPeriod.Year(today.year)
        ExportChoice.PREVIOUS_YEAR -> ExportPeriod.Year(today.year - 1)
        ExportChoice.CUSTOM -> custom?.let { (a, b) -> if (a <= b) ExportPeriod.Range(a, b) else ExportPeriod.Range(b, a) }
        ExportChoice.FULL_BACKUP -> ExportPeriod.Full
    }
}

/** Jakson ensimmäinen ja viimeinen päivä; koko aineistolla ei ole rajoja. */
fun ExportPeriod.bounds(): Pair<LocalDate, LocalDate>? = when (this) {
    is ExportPeriod.Month -> month.atDay(1) to month.atEndOfMonth()
    is ExportPeriod.Year -> LocalDate.of(year, 1, 1) to LocalDate.of(year, 12, 31)
    is ExportPeriod.Range -> from to to
    ExportPeriod.Full -> null
}

/** Kuuluuko päivä jaksoon; molemmat päät kuuluvat. */
fun ExportPeriod.includes(date: LocalDate): Boolean {
    val (from, to) = bounds() ?: return true
    return date >= from && date <= to
}

/**
 * Viedyn tiedoston nimi. Vain ASCII-merkkejä, koska nimi kulkee jakovalikon ja muiden sovellusten läpi; ISO-päivät
 * järjestyvät tiedostoselaimessa aikajärjestykseen.
 */
fun exportFileName(period: ExportPeriod, today: LocalDate): String = when (period) {
    is ExportPeriod.Month -> "tyomatka-${period.month}.html"
    is ExportPeriod.Year -> "tyomatka-${period.year}.html"
    is ExportPeriod.Range -> "tyomatka-${period.from}_${period.to}.html"
    ExportPeriod.Full -> "tyomatka-varmuuskopio-$today.html"
}
