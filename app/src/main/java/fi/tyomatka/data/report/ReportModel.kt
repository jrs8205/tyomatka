package fi.tyomatka.data.report

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.FuelInterval
import fi.tyomatka.data.repo.PeriodFuels
import fi.tyomatka.data.repo.WorkHistoryDay
import fi.tyomatka.data.repo.carFuel
import fi.tyomatka.data.repo.carPeriodFuel
import fi.tyomatka.data.repo.costsByCurrency
import fi.tyomatka.data.repo.fillupOrder
import fi.tyomatka.data.repo.fuelIntervals
import fi.tyomatka.data.repo.hasCompleteWorkReadings
import fi.tyomatka.data.repo.periodFuels
import fi.tyomatka.data.repo.periodKmByKind
import fi.tyomatka.data.repo.workHistoryDays
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields

/** Raportin sisältö valitulta jaksolta. Luvut tulevat sovelluksen nykyisistä laskentafunktioista. */
data class ReportModel(
    val period: ExportPeriod,
    /** Jakson päivät uusin ensin: työpäivät ja päivät, joilla on vain kirjauksia. */
    val days: List<WorkHistoryDay>,
    val weeks: List<ReportPeriodGroup>,
    val months: List<ReportPeriodGroup>,
    val years: List<ReportPeriodGroup>,
    /** Koko jakson luvut yhteenvetoon; null, kun jaksolla ei ole päiviä. */
    val total: ReportPeriodGroup?,
    /** Jakson kirjaukset uusin ensin. */
    val entries: List<ReportEntry>,
    val cars: List<ReportCar>,
    /** Kuvat kirjausten järjestyksessä. */
    val photos: List<ReportPhoto>,
    /** Varmuuskopion autoprofiilit: jakson työjaksojen ja kirjausten autot, koko varmuuskopiossa kaikki. */
    val carProfiles: List<CarEntity>,
    /** Jakson autojen käyttövoima rekisterinumeroittain: profiili, muuten viimeisin polttoainekirjaus. */
    val carKinds: Map<String, FuelKind>,
)

/**
 * Viikon, kuukauden tai vuoden luvut. [fromDay] ja [toDay] ovat ryhmän ensimmäinen ja viimeinen päivä jaksolla, joten
 * jakson rajan ylittävä viikko näyttää vain jakson päivät.
 */
data class ReportPeriodGroup(
    val fromDay: Long,
    val toDay: Long,
    /** ISO-viikko viikkoryhmälle, muuten null. */
    val isoWeek: Int?,
    val workDays: Int,
    val workSec: Long,
    val km: Long,
    val fuels: PeriodFuels,
)

/** Kirjaus, sen päättämä kulutusväli koko historiasta ja kuvat. */
data class ReportEntry(val fillup: FuelFillupEntity, val interval: FuelInterval?, val photos: List<ReportPhoto>)

/** Auton yhden lajin rivi. Hinta kilometriltä vain yhden valuutan väleistä, eikä AdBluella sitä ole. */
data class ReportCar(
    val plate: String,
    val kind: FuelKind,
    val count: Int,
    val amount: Double,
    val costs: Map<String, Double>,
    val avgConsumption: Double?,
    val costPerKm: Double?,
    val currency: String?,
)

/** Kirjauksen kuva; [index] (nollasta) ja [count] ovat kirjauksen sisäiset. */
data class ReportPhoto(val photo: FuelPhotoEntity, val fillup: FuelFillupEntity, val index: Int, val count: Int)

/**
 * Rajaa päivät, kirjaukset ja kuvat jaksoon. Kulutusvälit lasketaan koko historiasta, jotta jakson ensimmäinen täysi
 * saa välinsä ennen jaksoa olleesta täydestä.
 */
fun reportModel(
    period: ExportPeriod,
    segments: List<WorkSegmentEntity>,
    routeDays: List<RouteDayEntity>,
    fillups: List<FuelFillupEntity>,
    photosByFillup: Map<Long, List<FuelPhotoEntity>>,
    cars: List<CarEntity>,
    zone: ZoneId,
): ReportModel {
    val days = workHistoryDays(routeDays, segments, fillups, zone).filter { period.includes(LocalDate.ofEpochDay(it.date)) }
    val carsByPlate = cars.associateBy { it.plate }
    val fuelOf: (String) -> FuelKind? = { carFuel(it, carsByPlate, fillups) }
    val intervals = fuelIntervals(fillups).associateBy { it.fillupId }
    val periodFillups = days.flatMap { it.fillups }.sortedWith(fillupOrder.reversed())
    val entries = periodFillups.map { fillup ->
        val photos = photosByFillup[fillup.id].orEmpty()
        ReportEntry(fillup, intervals[fillup.id], photos.mapIndexed { i, photo -> ReportPhoto(photo, fillup, i, photos.size) })
    }
    val reportCars = carPeriodFuel(periodFillups, intervals).map { car ->
        val own = periodFillups.filter { it.licensePlate == car.plate && it.kind == car.kind }
        val costs = costsByCurrency(own.sortedWith(fillupOrder))
        ReportCar(
            plate = car.plate,
            kind = car.kind,
            count = car.fillupCount,
            amount = own.sumOf { it.liters },
            costs = costs,
            avgConsumption = car.consumption,
            // AdBluen hinta kilometriltä on häviävän pieni eikä vertailukelpoinen polttoaineen kanssa. Usean valuutan
            // jaksolla (myös hinnaton ulkomaan tankkaus) summat näytetään valuutoittain, ja hinta kilometriltä jää pois
            // kuten jaksokortissa.
            costPerKm = car.costPerKm.takeIf { car.kind != FuelKind.ADBLUE && own.map { it.currency }.distinct().size <= 1 },
            currency = car.currency,
        )
    }
    val plates = days.flatMap { day -> day.segments.mapNotNull { it.licensePlate } + day.fillups.map { it.licensePlate } }.toSet()
    val profiles = if (period == ExportPeriod.Full) cars else cars.filter { it.plate in plates }
    val group = { groupDays: List<WorkHistoryDay>, isoWeek: Int? -> periodGroup(groupDays, isoWeek, fuelOf) }
    return ReportModel(
        period = period,
        days = days,
        weeks = days.groupBy { weekKey(it.date) }.map { (key, groupDays) -> group(groupDays, key.second) },
        months = days.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.date)) }.values.map { group(it, null) },
        years = days.groupBy { LocalDate.ofEpochDay(it.date).year }.values.map { group(it, null) },
        total = days.takeIf { it.isNotEmpty() }?.let { group(it, null) },
        entries = entries,
        cars = reportCars,
        photos = entries.flatMap { it.photos },
        carProfiles = profiles.sortedBy { it.plate },
        carKinds = plates.mapNotNull { plate -> fuelOf(plate)?.let { plate to it } }.toMap(),
    )
}

/** ISO-viikko vuoteen sidottuna: vuodenvaihteen viikko kuuluu viikkovuoteensa. */
private fun weekKey(epochDay: Long): Pair<Int, Int> {
    val date = LocalDate.ofEpochDay(epochDay)
    return date.get(IsoFields.WEEK_BASED_YEAR) to date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
}

/** Ryhmän luvut kuten vanhan raportin kuukausirivillä: kulutus lajin omista kilometreistä ([periodKmByKind]). */
private fun periodGroup(days: List<WorkHistoryDay>, isoWeek: Int?, fuelOf: (String) -> FuelKind?): ReportPeriodGroup {
    val totals = days.mapNotNull { it.totals }
    val fillups = days.flatMap { it.fillups }
    val kmByKind = periodKmByKind(fillups, days.flatMap { it.segments }, fuelOf)
    return ReportPeriodGroup(
        fromDay = days.minOf { it.date },
        toDay = days.maxOf { it.date },
        isoWeek = isoWeek,
        workDays = totals.size,
        workSec = totals.sumOf { it.durationSec ?: 0 },
        km = totals.sumOf { it.totalKm },
        fuels = periodFuels(fillups, kmByKind, includeConsumption = hasCompleteWorkReadings(days)),
    )
}
