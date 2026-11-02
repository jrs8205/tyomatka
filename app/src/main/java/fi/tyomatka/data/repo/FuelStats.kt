package fi.tyomatka.data.repo

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Kulutusväli saman auton saman lajin kahden täyden tankkauksen välillä. Välissä olevien vajaiden
 * ja päättävän tankkauksen litrat summataan — täydestä täyteen ne ovat juuri välin kulutus.
 */
data class FuelInterval(
    val plate: String,
    val kind: FuelKind,
    /** Välin päättävän (täyden) tankkauksen id (listan "kulutus edellisestä täydestä" -rivi). */
    val fillupId: Long,
    val fromKm: Long,
    val toKm: Long,
    val liters: Double,
    /** Välin hinta, kun kaikki välin tankkaukset ovat hinnoiteltuja ja samassa valuutassa; muuten null. */
    val cost: Double?,
    val currency: String?,
    val epochSec: Long,
    /** Välissä olevien vajaiden tankkauskertojen määrä; saman lukeman kuitit ovat yksi kerta. */
    val partialCount: Int = 0,
    /** Välin päättävän tankkauskerran rivit: samalla lukemalla tehdyt tankkaukset, [fillupId] viimeinen täysi. */
    val eventIds: List<Long> = listOf(fillupId),
) {
    val km: Long get() = toKm - fromKm
    /** Lajin yksikössä: polttoaineet /100 km, AdBlue /1000 km. */
    val consumption: Double get() = consumption(kind, liters, km)
    val costPerKm: Double? get() = cost?.let { costPerKm(it, km) }
}

/**
 * Kulutus lajin yksikössä: polttoaineet (l, kg, kWh) sadalle kilometrille, AdBlue tuhannelle (sen kulutus on
 * muutamia prosentteja dieselistä).
 */
fun consumption(kind: FuelKind, amount: Double, km: Long): Double = amount / km * kind.perKm

fun costPerKm(cost: Double, km: Long): Double = cost / km

val fillupOrder: Comparator<FuelFillupEntity> = compareBy({ it.epochSec }, { it.odometerKm }, { it.id })

private fun inCarOrder(fillups: List<FuelFillupEntity>): List<FuelFillupEntity> = fillups.sortedWith(fillupOrder)

/** Hinnoiteltujen tankkausten summat valuutoittain ensiesiintymän järjestyksessä. */
fun costsByCurrency(fillups: List<FuelFillupEntity>): Map<String, Double> =
    fillups.filter { it.totalCost > 0 }.groupBy { it.currency }.mapValues { (_, f) -> f.sumOf { it.totalCost } }

/**
 * Välit kaikille autoille ja lajeille. Väli alkaa täydestä ja päättyy seuraavaan täyteen;
 * pelkät vajaat eivät muodosta väliä. Samalla lukemalla tehdyt tankkaukset ovat yksi kerta
 * ([fillEvents]). Laskeva lukema (kirjausvirhe) katkaisee ketjun: seuraava väli alkaa siitä
 * kerrasta, jos se on täysi.
 */
fun fuelIntervals(fillups: List<FuelFillupEntity>): List<FuelInterval> =
    fillups.groupBy { it.licensePlate to it.kind }.values.flatMap { car -> carIntervals(inCarOrder(car)) }

/**
 * Saman auton ja lajin peräkkäiset tankkaukset samalla lukemalla ovat yksi tankkauskerta (kaksi
 * kuittia samalla pysähdyksellä tai kahden tankin auto). Kerta on täysi, jos jokin sen tankkauksista on täysi, ja
 * sen määrät kuuluvat samaan väliin.
 */
private fun fillEvents(car: List<FuelFillupEntity>): List<List<FuelFillupEntity>> {
    val events = mutableListOf<MutableList<FuelFillupEntity>>()
    for (f in car) {
        val last = events.lastOrNull()
        if (last != null && last.last().odometerKm == f.odometerKm) last += f else events += mutableListOf(f)
    }
    return events
}

/** Tankkauskerta ([fillEvents]), johon tankkaus [id] kuuluu; tyhjä, jos riviä ei ole. */
fun fillEventOf(fillups: List<FuelFillupEntity>, id: Long): List<FuelFillupEntity> {
    val target = fillups.firstOrNull { it.id == id } ?: return emptyList()
    val car = inCarOrder(fillups.filter { it.licensePlate == target.licensePlate && it.kind == target.kind })
    return fillEvents(car).first { event -> event.any { it.id == id } }
}

private fun carIntervals(car: List<FuelFillupEntity>): List<FuelInterval> {
    val result = mutableListOf<FuelInterval>()
    var anchorKm: Long? = null
    // Välissä olevat vajaat kerrat; aloittavan kerran määrät ovat lähtötaso eivätkä välin kulutusta.
    val partials = mutableListOf<List<FuelFillupEntity>>()
    for (event in fillEvents(car)) {
        val km = event.first().odometerKm
        // Kerran viimeinen täysi omistaa välin ("kulutus edellisestä täydestä" -rivi).
        val closing = event.lastOrNull { it.fullTank }
        val anchor = anchorKm
        val lastKm = partials.lastOrNull()?.first()?.odometerKm ?: anchor
        if (anchor == null || lastKm == null || km < lastKm) {
            anchorKm = km.takeIf { closing != null }
            partials.clear()
            continue
        }
        if (closing == null) {
            partials += event
            continue
        }
        val chain = partials.flatten() + event
        val currencies = chain.map { it.currency }.distinct()
        val priced = currencies.size == 1 && chain.all { it.totalCost > 0 }
        result += FuelInterval(
            plate = closing.licensePlate,
            kind = closing.kind,
            fillupId = closing.id,
            fromKm = anchor,
            toKm = km,
            liters = chain.sumOf { it.liters },
            cost = if (priced) chain.sumOf { it.totalCost } else null,
            currency = currencies.singleOrNull(),
            epochSec = closing.epochSec,
            partialCount = partials.size,
            eventIds = event.map { it.id },
        )
        anchorKm = km
        partials.clear()
    }
    return result
}

/** Auto- ja lajikohtainen yhteenveto raportin autotaulukkoon. */
data class CarFuelSummary(
    val plate: String,
    val kind: FuelKind,
    val latest: FuelInterval?,
    /** Painotettu: välien litrat / välien km lajin yksikössä. Null ilman väliä. */
    val avgConsumption: Double?,
    /** Vain yhden valuutan hinnoitelluista väleistä. */
    val avgCostPerKm: Double?,
    val currency: String?,
    /** Kilometrit tankkausten välillä yhteensä. */
    val totalKm: Long,
    /** Kaikki lajin tankkaukset, myös ensimmäinen. */
    val totalLiters: Double,
    val costs: Map<String, Double>,
    val fillupCount: Int,
    val lastEpochSec: Long,
)

fun carSummaries(fillups: List<FuelFillupEntity>): List<CarFuelSummary> {
    val intervalsByCar = fuelIntervals(fillups).groupBy { it.plate to it.kind }
    return fillups.groupBy { it.licensePlate to it.kind }.map { (key, car) ->
        val (plate, kind) = key
        val intervals = intervalsByCar[key].orEmpty()
        val km = intervals.sumOf { it.km }
        // Hinta/km vain hinnoitelluista väleistä ja yhdestä valuutasta — hinnaton väli ei laimenna.
        val priced = intervals.filter { it.cost != null }
        val currency = priced.map { it.currency }.distinct().singleOrNull()
        val pricedKm = priced.sumOf { it.km }
        CarFuelSummary(
            plate = plate,
            kind = kind,
            latest = intervals.lastOrNull(),
            avgConsumption = if (km > 0) consumption(kind, intervals.sumOf { it.liters }, km) else null,
            avgCostPerKm = if (currency != null && pricedKm > 0) costPerKm(priced.sumOf { it.cost ?: 0.0 }, pricedKm) else null,
            currency = currency,
            totalKm = km,
            totalLiters = car.sumOf { it.liters },
            costs = costsByCurrency(car),
            fillupCount = car.size,
            lastEpochSec = car.maxOf { it.epochSec },
        )
    }.let { rows ->
        // Auton rivit peräkkäin: autot viimeisin tankkaus ensin, auton lajit vakiojärjestyksessä.
        val latestByPlate = rows.groupBy { it.plate }.mapValues { (_, car) -> car.maxOf { it.lastEpochSec } }
        rows.sortedWith(
            compareByDescending<CarFuelSummary> { latestByPlate.getValue(it.plate) }.thenBy { it.plate }.thenBy { it.kind }
        )
    }
}

/** Jakson (päivä/viikko/kk/vuosi) yhden lajin tankkausluvut kortille ja raporttiin. */
data class PeriodFuel(
    val kind: FuelKind,
    val liters: Double,
    /** Hinnoiteltujen tankkausten summat valuutoittain. */
    val costs: Map<String, Double>,
    /** Null, jos hintoja ei ole kirjattu tai valuuttoja on useita. */
    val avgPricePerLiter: Double?,
    /** Litrat / jakson työkilometrit lajin yksikössä — vain pidemmille jaksoille. */
    val consumption: Double?,
    /** Null usean valuutan jaksolla (ei muunnoksia). */
    val costPerKm: Double?,
    /** Osa tankkauksista on hinnattomia: summa on vain kirjattu osuus. */
    val missingPrices: Boolean = false,
    /** Jakson ainoa valuutta kaikista lajin tankkauksista, myös hinnattomista; null usean valuutan jaksolla. */
    val currency: String? = null,
)

/** Jakson luvut lajeittain lajien järjestyksessä (diesel, bensiini, kaasu, sähkö, AdBlue); vain jaksolla olevat lajit. */
data class PeriodFuels(val byKind: List<PeriodFuel>) {
    operator fun get(kind: FuelKind): PeriodFuel? = byKind.firstOrNull { it.kind == kind }

    /** AdBlue-litrat prosentteina diesel-litroista samalta jaksolta. */
    val adBluePercent: Double?
        get() {
            val diesel = get(FuelKind.DIESEL)?.liters?.takeIf { it > 0 } ?: return null
            return get(FuelKind.ADBLUE)?.liters?.let { it / diesel * 100 }
        }
    val isEmpty: Boolean get() = byKind.isEmpty()
}

/**
 * Jakson luvut yhdelle lajille. Päiväkortille `includeConsumption = false`: yhden päivän
 * litrat / päivän kilometrit heittelee, koska tankataan harvoin.
 */
fun periodFuel(
    fillups: List<FuelFillupEntity>,
    periodKm: Long,
    includeConsumption: Boolean,
    kind: FuelKind,
): PeriodFuel? {
    val ofKind = fillups.filter { it.kind == kind }
    if (ofKind.isEmpty()) return null
    val liters = ofKind.sumOf { it.liters }
    val costs = costsByCurrency(ofKind)
    // Valuutat kaikista tankkauksista ennen hintojen suodatusta: hinnaton SEK-tankkaus tekee jaksosta usean
    // valuutan jakson, eikä sen litroja saa hinnoitella euroilla.
    val currency = ofKind.map { it.currency }.distinct().singleOrNull()
    // Keskihinta vain hinnoitelluista litroista ja yhdestä valuutasta; hinta/km arvioidaan kaikille
    // litroille sillä hinnalla — hinnaton tankkaus ei laimenna kumpaakaan.
    val pricedLiters = ofKind.filter { it.totalCost > 0 }.sumOf { it.liters }
    val avgPrice = if (currency != null && pricedLiters > 0) costs.getValue(currency) / pricedLiters else null
    val withConsumption = includeConsumption && periodKm > 0 && liters > 0
    return PeriodFuel(
        kind = kind,
        liters = liters,
        costs = costs,
        avgPricePerLiter = avgPrice,
        consumption = if (withConsumption) consumption(kind, liters, periodKm) else null,
        costPerKm = if (withConsumption && avgPrice != null) costPerKm(liters * avgPrice, periodKm) else null,
        missingPrices = ofKind.any { it.totalCost <= 0 },
        currency = currency,
    )
}

/** Jakson luvut kaikille lajeille, joita jaksolla on; kulutussuhde lajin kilometreistä ([periodKmByKind]). */
fun periodFuels(fillups: List<FuelFillupEntity>, kmByKind: Map<FuelKind, Long>, includeConsumption: Boolean): PeriodFuels =
    PeriodFuels(FuelKind.entries.mapNotNull { periodFuel(fillups, kmByKind[it] ?: 0, includeConsumption, it) })

/**
 * Jakson työkilometrit lajeittain kulutussuhdetta varten. Työjakson auton lajit tulevat ensin jakson omista
 * polttoainetankkauksista (ladattava hybridi saa molemmat), muuten auton polttoaineesta ([fuelOf]); toisen
 * polttoaineen auton kilometrit eivät siis laimenna lajia. Rekkarittomat ja tuntemattomat kilometrit kuuluvat jakson
 * ainoalle polttoaineelle (vanhoista työjaksoista puuttuu usein rekkari); usean polttoaineen jaksolla niitä ei voi
 * kohdistaa, joten kulutussuhdetta ei lasketa lainkaan. AdBlue saa dieselin kilometrit.
 */
fun periodKmByKind(
    fillups: List<FuelFillupEntity>,
    segments: List<WorkSegmentEntity>,
    fuelOf: (String) -> FuelKind?,
): Map<FuelKind, Long> {
    val main = fillups.filter { it.kind.isMainFuel }
    val mainKinds = main.map { it.kind }.toSet()
    val periodKinds = main.groupBy({ it.licensePlate }, { it.kind }).mapValues { (_, kinds) -> kinds.toSet() }
    val km = mutableMapOf<FuelKind, Long>()
    var unknownKm = 0L
    for (seg in segments) {
        val start = seg.odometerStartKm ?: continue
        val end = seg.odometerEndKm ?: continue
        if (start < 0 || end < start) continue
        val kinds = seg.licensePlate?.let { periodKinds[it] ?: fuelOf(it)?.let(::setOf) }.orEmpty()
        if (kinds.isEmpty()) unknownKm += end - start else kinds.forEach { km.merge(it, end - start, Long::plus) }
    }
    if (unknownKm > 0) {
        if (mainKinds.size > 1) return emptyMap()
        km.merge(mainKinds.singleOrNull() ?: FuelKind.DIESEL, unknownKm, Long::plus)
    }
    km[FuelKind.ADBLUE] = km[FuelKind.DIESEL] ?: 0L
    return km
}

/** Yhden auton yhden lajin kulutus jaksolla (viikko/kk/vuosi-kortin rivi). */
data class CarPeriodFuel(
    val plate: String,
    val kind: FuelKind,
    /** Lajin yksikössä; null, jos jaksoon ei pääty yhtään laskettavaa väliä. */
    val consumption: Double?,
    val costPerKm: Double?,
    val currency: String?,
    val fillupCount: Int,
    /** AdBlue: auton AdBlue-litrat prosentteina sen diesel-litroista jaksolla. */
    val percentOfDiesel: Double? = null,
)

/**
 * Auto- ja lajikohtainen kulutus jaksolle: mukaan välit, joiden päättävä tankkaus on jaksolla
 * ([periodFillups]); edeltävä tankkaus saa olla jakson ulkopuolella. Painotettu litrat / km; hinta/km
 * vain yhden valuutan hinnoitelluista väleistä. Järjestys: autot eniten tankkauksia ensin, sitten rekkari; auton
 * lajit vakiojärjestyksessä (polttoaine ennen AdBluea), jotta auton rivit ovat peräkkäin.
 */
fun carPeriodFuel(
    periodFillups: List<FuelFillupEntity>,
    intervalsByFillup: Map<Long, FuelInterval>,
): List<CarPeriodFuel> {
    val dieselLiters = periodFillups.filter { it.kind == FuelKind.DIESEL }
        .groupBy { it.licensePlate }.mapValues { (_, f) -> f.sumOf { it.liters } }
    return periodFillups.groupBy { it.licensePlate to it.kind }.map { (key, car) ->
        val (plate, kind) = key
        val intervals = car.mapNotNull { intervalsByFillup[it.id] }
        val km = intervals.sumOf { it.km }
        val priced = intervals.filter { it.cost != null }
        val currency = priced.map { it.currency }.distinct().singleOrNull()
        val pricedKm = priced.sumOf { it.km }
        val diesel = dieselLiters[plate]?.takeIf { it > 0 }
        CarPeriodFuel(
            plate = plate,
            kind = kind,
            consumption = if (km > 0) consumption(kind, intervals.sumOf { it.liters }, km) else null,
            costPerKm = if (currency != null && pricedKm > 0) costPerKm(priced.sumOf { it.cost ?: 0.0 }, pricedKm) else null,
            currency = currency,
            fillupCount = car.size,
            percentOfDiesel = if (kind == FuelKind.ADBLUE && diesel != null) car.sumOf { it.liters } / diesel * 100 else null,
        )
    }.let { rows ->
        val fillupsByPlate = rows.groupBy { it.plate }.mapValues { (_, car) -> car.sumOf { it.fillupCount } }
        rows.sortedWith(
            compareByDescending<CarPeriodFuel> { fillupsByPlate.getValue(it.plate) }.thenBy { it.plate }.thenBy { it.kind }
        )
    }
}

/** Mittarilukeman rajat: saman auton edellinen ja seuraava tankkaus ajassa. */
data class FillupKmBounds(val previousKm: Long?, val nextKm: Long?)

/**
 * Lukeman rajat tankkaukselle, joka osuu hetkeen [atEpochSec] (muokattava
 * rivi pois): alaraja = viimeisin sitä edeltävä saman auton tankkaus,
 * yläraja = ensimmäinen sen jälkeinen. Näin vanhaa tai takautuvaa
 * tankkausta voi muokata, vaikka autolla olisi jo uudempia lukemia.
 * Rajat tulevat saman lajin tankkauksista: diesel ja AdBlue eivät rajoita toisiaan.
 */
fun fillupKmBounds(
    fillups: List<FuelFillupEntity>,
    plate: String,
    excludeId: Long?,
    atEpochSec: Long,
    odometerKm: Long? = null,
    kind: FuelKind,
): FillupKmBounds {
    val others = inCarOrder(fillups.filter { it.licensePlate == plate && it.kind == kind && it.id != excludeId })
    // Muokattaessa tasatilanteen paikka perustuu alkuperäiseen lukemaan,
    // jotta hinnan korjaus ei vaihda naapureita. Uusi rivi sijoitetaan syötteellä.
    val reading = fillups.firstOrNull { it.id == excludeId && it.licensePlate == plate && it.epochSec == atEpochSec }
        ?.odometerKm ?: odometerKm
    return FillupKmBounds(
        previousKm = others.lastOrNull {
            it.epochSec < atEpochSec || (it.epochSec == atEpochSec && reading != null && it.odometerKm <= reading)
        }?.odometerKm,
        nextKm = others.firstOrNull {
            it.epochSec > atEpochSec || (it.epochSec == atEpochSec && reading != null && it.odometerKm > reading)
        }?.odometerKm,
    )
}

/**
 * Uudet varmuuskopiot tunnistetaan pysyvällä tunnisteella myös muokkausten
 * jälkeen. Vanhasta tiedostosta ilman tunnistetta hyväksytään kaikki
 * sisällöltään erilliset tankkaukset: aika tai lukema ei yksin yksilöi niitä.
 * Sisältöä verrataan ilman valuuttaa: aiemmat versiot antoivat vanhoille
 * riveille puhelimen valuutan, joka on voinut olla muu kuin euro. Vanhan tiedoston
 * (v1–v3, [legacy]) rivi on oletuksena täysi diesel, joten laji ja täysi eivät
 * erota sitä rivistä, jonka käyttäjä on sittemmin merkinnyt vajaaksi tai AdBlueksi.
 */
class FuelDedupe(existing: List<FuelFillupEntity>) {
    private val ids = existing.map { it.backupId }.toMutableSet()
    private val contents = existing.map { it.contentKey() }.toMutableSet()
    private val legacyContents = existing.map { it.legacyKey() }.toMutableSet()

    /** Lisätiedot eivät kuulu sisältöön: vanhan varmuuskopion kirjaus ilman tekstiä on sama kirjaus. */
    private fun FuelFillupEntity.contentKey() = copy(id = 0, backupId = "", currency = "", note = "")

    private fun FuelFillupEntity.legacyKey() = contentKey().copy(kind = FuelKind.DIESEL, fullTank = true)

    /** true = uusi (ja merkitään nähdyksi), false = tupla. */
    fun isNew(fillup: FuelFillupEntity, hasBackupId: Boolean, legacy: Boolean): Boolean {
        // Tunniste TAI sisältö: v2-palautuksen jälkeen tuotu v3 tai v4 ei saa tuplata.
        val sameContent = if (legacy) fillup.legacyKey() in legacyContents else fillup.contentKey() in contents
        if ((hasBackupId && fillup.backupId in ids) || sameContent) return false
        ids.add(fillup.backupId)
        contents.add(fillup.contentKey())
        legacyContents.add(fillup.legacyKey())
        return true
    }
}

/**
 * Oletusauto, kun työt eivät ole käynnissä (Tänään-kortin nappi, tankkauslomake, työn aloitus): viimeisimmän
 * työjakson auto, myös tyhjä. Ilman työjaksoja viimeisimmän kirjauksen auto (pelkkiä latauksia kirjannut saa oman
 * autonsa), muuten ensimmäisenä lisätty autoprofiili (esittelyssä annettu auto; [cars]
 * lisäysjärjestyksessä).
 */
fun defaultCarPlate(segments: List<WorkSegmentEntity>, fillups: List<FuelFillupEntity>, cars: List<CarEntity>): String? =
    if (segments.isNotEmpty()) {
        latestWorkSegment(segments)?.licensePlate
    } else {
        fillups.maxWithOrNull(fillupOrder)?.licensePlate ?: cars.firstOrNull()?.plate
    }

/**
 * Tankkausdialogin ja Tänään-napin oletusrekkari: avoimen segmentin auto, jos työt ovat
 * käynnissä — myös silloin kun sen rekkari on tyhjä (autonvaihdossa
 * jätetty täyttämättä), ettei tankkaus mene edelliselle autolle. Muuten oletusauto ([defaultCarPlate]), ja jos
 * viimeisin työjakso on ilman autoa, viimeisimmän kirjauksen auto (pelkkiä latauksia kirjannut saa oman autonsa ja
 * sen mukaisen napin).
 */
fun defaultFuelPlate(openSegment: WorkSegmentEntity?, defaultPlate: String?, fillups: List<FuelFillupEntity>): String? =
    if (openSegment != null) openSegment.licensePlate else defaultPlate ?: fillups.maxWithOrNull(fillupOrder)?.licensePlate

/**
 * Tankkaushetki valitulle päivälle: tänään = nyt, mennyt päivä = keskipäivä.
 * Muokattaessa alkuperäinen hetki säilyy, jos päivä ei vaihtunut.
 */
fun fillupEpochSec(epochDay: Long, existingEpochSec: Long?, nowEpochSec: Long, zone: ZoneId): Long {
    fun dayOf(epochSec: Long): Long =
        Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate().toEpochDay()
    if (existingEpochSec != null && dayOf(existingEpochSec) == epochDay) return existingEpochSec
    if (dayOf(nowEpochSec) == epochDay) return nowEpochSec
    return LocalDate.ofEpochDay(epochDay).atTime(12, 0).atZone(zone).toEpochSecond()
}
