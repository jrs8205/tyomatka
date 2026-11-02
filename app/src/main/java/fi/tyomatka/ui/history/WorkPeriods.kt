package fi.tyomatka.ui.history

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.CarPeriodFuel
import fi.tyomatka.data.repo.FuelInterval
import fi.tyomatka.data.repo.PeriodFuels
import fi.tyomatka.data.repo.WorkHistoryDay
import fi.tyomatka.data.repo.carPeriodFuel
import fi.tyomatka.data.repo.hasCompleteWorkReadings
import fi.tyomatka.data.repo.periodFuels
import fi.tyomatka.data.repo.periodKmByKind
import fi.tyomatka.ui.formatMonth
import fi.tyomatka.ui.formatShortDate
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields

enum class WorkPeriod { DAY, WEEK, MONTH, YEAR }

data class WorkPeriodRow(
    val key: String,
    val label: String,
    val km: Long,
    val durationSec: Long,
    val ongoing: Boolean,
    val missingReadings: Boolean,
    val fuel: PeriodFuels? = null,
    val hasWork: Boolean = true,
    val fillups: List<FuelFillupEntity> = emptyList(),
    val carFuel: List<CarPeriodFuel> = emptyList(),
    val date: Long? = null,
    /** Työpäivien määrä jaksolla (pelkät tankkauspäivät eivät kuulu). */
    val workDays: Int = 0,
    /** Päiväkortin Lisätiedot-merkki: vain päivärivillä, jolla on teksti (tekstiä ei näytetä kortissa). */
    val hasNote: Boolean = false,
)

/**
 * Kalenterijaksot; ISO-viikon vuosi voi erota päivämäärän vuodesta. [fuelOf] antaa auton polttoaineen, kun jakson
 * kilometrit jaetaan lajeittain ([periodKmByKind]).
 */
fun workPeriodRows(
    days: List<WorkHistoryDay>,
    period: WorkPeriod,
    intervalsByFillup: Map<Long, FuelInterval>,
    fuelOf: (String) -> FuelKind?,
    weekLabel: (week: Int, year: Int) -> String,
): List<WorkPeriodRow> {
    fun key(date: LocalDate): String = when (period) {
        WorkPeriod.DAY -> "d${date.toEpochDay()}"
        WorkPeriod.WEEK -> "w${date.get(WeekFields.ISO.weekBasedYear())}-${date.get(WeekFields.ISO.weekOfWeekBasedYear())}"
        WorkPeriod.MONTH -> "m${YearMonth.from(date)}"
        WorkPeriod.YEAR -> "y${date.year}"
    }
    fun label(date: LocalDate): String = when (period) {
        WorkPeriod.DAY -> formatShortDate(date.toEpochDay())
        WorkPeriod.WEEK -> weekLabel(date.get(WeekFields.ISO.weekOfWeekBasedYear()), date.get(WeekFields.ISO.weekBasedYear()))
        WorkPeriod.MONTH -> formatMonth(YearMonth.from(date), capitalize = true)
        WorkPeriod.YEAR -> date.year.toString()
    }
    return days.sortedByDescending { it.date }.groupBy { key(LocalDate.ofEpochDay(it.date)) }
        .map { (key, group) ->
            val totals = group.mapNotNull { it.totals }
            val km = totals.sumOf { it.totalKm }
            val fillups = group.flatMap { it.fillups }
            val isDay = period == WorkPeriod.DAY
            WorkPeriodRow(
                key = key,
                label = label(LocalDate.ofEpochDay(group.first().date)),
                km = km,
                durationSec = totals.sumOf { it.durationSec ?: 0 },
                ongoing = totals.any { it.endEpochSec == null },
                missingReadings = totals.any { it.missingReadings },
                fuel = periodFuels(
                    fillups,
                    periodKmByKind(fillups, group.flatMap { it.segments }, fuelOf),
                    includeConsumption = !isDay && hasCompleteWorkReadings(group),
                ).takeUnless { it.isEmpty },
                hasWork = totals.isNotEmpty(),
                fillups = if (isDay) fillups else emptyList(),
                carFuel = if (isDay) emptyList() else carPeriodFuel(fillups, intervalsByFillup),
                date = if (isDay) group.single().date else null,
                workDays = totals.size,
                hasNote = isDay && group.single().note.isNotBlank(),
            )
        }
}
