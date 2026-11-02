package fi.tyomatka.data.repo

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.Instant
import java.time.ZoneId

/** Sama päiväjoukko sovelluksen korteille ja HTML-raportille. */
data class WorkHistoryDay(
    val date: Long,
    val segments: List<WorkSegmentEntity>,
    val fillups: List<FuelFillupEntity>,
    /** Päivän lisätiedot ([RouteDayEntity.note]); pelkän tankkauksen päivällä ilman päiväriviä tyhjä. */
    val note: String = "",
) {
    val totals: WorkDayTotals? = workDayTotals(segments)
}

fun workHistoryDays(
    routeDays: List<RouteDayEntity>,
    segments: List<WorkSegmentEntity>,
    fillups: List<FuelFillupEntity>,
    zone: ZoneId,
): List<WorkHistoryDay> {
    val dateById = routeDays.associate { it.id to it.dateEpochDay }
    val noteByDate = routeDays.associate { it.dateEpochDay to it.note }
    val workByDate = segments.groupBy { dateById[it.routeDayId] }.filterKeys { it != null }
    val fuelByDate = fillups.groupBy {
        Instant.ofEpochSecond(it.epochSec).atZone(zone).toLocalDate().toEpochDay()
    }
    return (workByDate.keys.filterNotNull() + fuelByDate.keys).distinct().sortedDescending().map { date ->
        WorkHistoryDay(
            date,
            workByDate[date].orEmpty().sortedWith(compareBy({ it.startEpochSec }, { it.id })),
            fuelByDate[date].orEmpty().sortedWith(fillupOrder),
            note = noteByDate[date].orEmpty(),
        )
    }
}

/**
 * Puutteellinen kilometrimäärä ei kelpaa jakson kulutussuhteen nimittäjäksi.
 * Kesken oleva työpäivä ei estä: sen avoin segmentti ei ole kilometreissä
 * mukana, ja suhde on muutenkin arvio — muuten rivi puuttuisi joka työpäivä.
 */
fun hasCompleteWorkReadings(days: List<WorkHistoryDay>): Boolean {
    val totals = days.mapNotNull { it.totals }
    return totals.isNotEmpty() && totals.none { it.missingReadings }
}
