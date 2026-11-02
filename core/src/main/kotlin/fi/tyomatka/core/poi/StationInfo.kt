package fi.tyomatka.core.poi

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Tiedon lähde; tasapelissä (sama päivä) etusija on DRIVER > CHAIN > AFIR > OSM. */
enum class InfoSource { OSM, CHAIN, AFIR, DRIVER }

/** Arvo lähteineen; [epochDay] on lähteen päivä (OSM:n `osm_ts`, kertatuonnin `k:date`, AFIRin `afir:date`, ilmoituksen päivä). */
data class Sourced<T>(val value: T, val source: InfoSource, val epochDay: Long?)

/**
 * Saman tekohetken tasapelin ratkaisija [StationReport.rank] (suurempi voittaa). Palvelimen vastaus tuntee omat rivit,
 * jotka kuitattiin ennen sen hakua, ja on ratkaissut niiden tasapelit rivitunnisteella. Myöhemmin kuitattu tai
 * lähettämätön oma rivi on vastausta uudempi tieto, joten vanha välimuistivastaus ei kumoa sitä. Arvo on puolisekunteina:
 * samalla sekunnilla kuin haku kuitattu oma voittaa, koska järjestystä ei tiedetä.
 */
object ReportRank {
    fun server(fetchedEpochSec: Long): Long = fetchedEpochSec * 2
    fun sent(sentEpochSec: Long): Long = sentEpochSec * 2 + 1
    const val PENDING: Long = Long.MAX_VALUE

    /** Tästä puhelimesta tehty ilmoitus: lähettämätön tai kuitattu (pariton), palvelimen rivit ovat parillisia. */
    fun isOwn(rank: Long): Boolean = rank and 1L == 1L
}

/**
 * Kuljettajan ilmoitus palvelimelta tai omasta jonosta; [epochDay] on päivä kuljettajan aikavyöhykkeellä (vertailuun muiden
 * lähteiden päiviin) ja [atEpochSec] tarkka tekohetki, joka ratkaisee ilmoitusten keskinäisen järjestyksen ([rank]
 * tasapelissä, ks. [ReportRank]).
 */
data class StationReport(
    val poiId: String,
    val field: ReportField,
    val value: String,
    val epochDay: Long,
    val atEpochSec: Long = epochDay * 86_400L,
    val rank: Long = 0,
)

enum class HoursKind { STATION, RESTAURANT, LUNCH, BREAKFAST, SHOP }

/**
 * Kohteen tiedot kentittäin yhdistettynä: uusin päivä voittaa, puuttuva `osm_ts` on vanhin. Teboilin polttoaineet ovat
 * yliviivattuja ([fuelsOff]), kunnes kuljettaja ilmoittaa polttoaineet alasajon jälkeen; suljettu asema ja Teboil näkyvät
 * kartalla himmennettyinä ([dimmed]).
 */
data class StationInfo(
    val closed: Sourced<Boolean>?,
    val fuelsOff: Boolean,
    val fuels: Sourced<List<FuelOffer>>?,
    val adblue: Sourced<String>?,
    val hours: Map<HoursKind, Sourced<String>>,
    val services: Map<ReportField, Sourced<Boolean>>,
    val restaurantNames: List<String>,
    val cafe: Boolean,
    val extras: Set<String>,
    val limits: Map<ReportField, Sourced<String>>,
    val truckLengthsM: Sourced<List<Double>>?,
    val hgvLane: Sourced<Boolean>?,
    val cards: Sourced<List<String>>?,
    val electricPrice: Sourced<String>?,
    val effectiveHgv: Boolean,
    val dimmed: Boolean,
) {
    /** Mistä lähteestä kortin tieto tälle ilmoituskentälle tulee; null = tieto ei näy. */
    fun sourceOf(field: ReportField): InfoSource? = when (field) {
        ReportField.CLOSED -> closed?.source
        ReportField.FUELS -> fuels?.source
        ReportField.ADBLUE -> adblue?.source
        ReportField.HOURS_STATION -> hours[HoursKind.STATION]?.source
        ReportField.HOURS_RESTAURANT -> hours[HoursKind.RESTAURANT]?.source
        ReportField.HOURS_LUNCH -> hours[HoursKind.LUNCH]?.source
        ReportField.HOURS_SHOP -> hours[HoursKind.SHOP]?.source
        ReportField.TRUCK_LENGTH -> truckLengthsM?.source
        ReportField.MAX_HEIGHT, ReportField.MAX_WEIGHT -> limits[field]?.source
        else -> services[field]?.source
    }

    companion object {
        /** Teboilin alasajo (kertatuonnin päivä): tätä vanhempi kuljettajan polttoaineilmoitus ei kumoa yliviivausta. */
        val WINDING_DOWN_DAY: Long = LocalDate.of(2026, 10, 22).toEpochDay()

        val SERVICE_FIELDS = listOf(
            ReportField.CHARGING, ReportField.SHOP, ReportField.RESTAURANT, ReportField.LUNCH, ReportField.TRUCK_PARKING,
            ReportField.SHOWER, ReportField.TOILETS, ReportField.SAUNA, ReportField.OVERNIGHT,
        )
        fun of(item: PoiItem, reports: List<StationReport>): StationInfo = Sources(item, reports).info()
    }
}

private class Sources(private val item: PoiItem, reports: List<StationReport>) {
    private val tags = item.tags
    private val osmDay = day(tags["osm_ts"])
    private val chainDay = day(tags["k:date"])
    private val afirDay = day(tags["afir:date"])
    private val latest: Map<ReportField, StationReport> = reports
        .filter { it.poiId == item.id }
        .sortedWith(compareBy({ it.atEpochSec }, { it.rank }))
        .associateBy { it.field }

    fun info(): StationInfo {
        val closed = pick(
            if (tags["k:status"] == "closed") Sourced(true, InfoSource.CHAIN, chainDay) else null,
            driver(ReportField.CLOSED)?.let { bool(it) },
        )
        val fuels = pick(osmFuels(), chain("k:fuels")?.map(::parseFuels), driver(ReportField.FUELS)?.map(::parseFuels))
        val fuelsOff = fuelsOff()
        val services = StationInfo.SERVICE_FIELDS.mapNotNull { field -> service(field)?.let { field to it } }.toMap()
        val hgvLane = pick(
            if (tags["fuel:hgv_diesel"] == "yes") Sourced(true, InfoSource.OSM, osmDay) else null,
            chain("k:hgv_lane")?.takeIf { it.value == "yes" }?.map { true },
        )
        return StationInfo(
            closed = closed,
            fuelsOff = fuelsOff,
            fuels = fuels,
            adblue = pick(osmAdblue(), chain("k:adblue"), driver(ReportField.ADBLUE)),
            hours = hours(),
            services = services,
            restaurantNames = tags["k:restaurant"]?.split(';')?.map(String::trim)?.filter { it.isNotEmpty() && it != "yes" }.orEmpty(),
            cafe = tags["k:cafe"] == "yes" || tags["cafe"] == "yes" || tags["amenity"] == "cafe",
            extras = setOfNotNull("rest_room".takeIf { tags["k:rest_room"] == "yes" }, "carwash".takeIf { tags["k:carwash"] == "yes" }),
            limits = listOf(ReportField.MAX_HEIGHT, ReportField.MAX_WEIGHT).mapNotNull { field ->
                pick(osm(tags[field.key]), driver(field))?.let { field to it }
            }.toMap(),
            truckLengthsM = pick(
                chain("k:truck_length")?.map { value -> value.split(';').mapNotNull { it.trim().toDoubleOrNull() } },
                driver(ReportField.TRUCK_LENGTH)?.map { listOfNotNull(it.toDoubleOrNull()) },
            ),
            hgvLane = hgvLane,
            cards = chain("k:cards")?.map { value -> value.split(';').map(String::trim).filter(String::isNotEmpty) },
            electricPrice = tags["afir:price"]?.let { Sourced(it, InfoSource.AFIR, afirDay) },
            effectiveHgv = item.hgv || hgvLane?.value == true || services[ReportField.TRUCK_PARKING]?.value == true,
            dimmed = closed?.value == true || fuelsOff,
        )
    }

    private fun fuelsOff(): Boolean {
        // Yliviivaus koskee vain polttoaineita myyviä kohteita (ei Teboil-nimistä latauspistettä tai lepopaikkaa).
        if (item.category != PoiCategory.FUEL && item.category != PoiCategory.GAS) return false
        val teboil = listOfNotNull(item.name, item.brand).any { it.contains("teboil", ignoreCase = true) }
        // Uudempi OSM-tieto toisella merkillä voittaa kertatuonnin alasajotilan (asema vaihtanut ketjua).
        val renamed = !teboil && osmDay != null && chainDay != null && osmDay > chainDay
        val windingDown = tags["k:status"] == "winding_down" && !renamed
        if (!windingDown && !teboil) return false
        val since = if (windingDown) chainDay ?: StationInfo.WINDING_DOWN_DAY else StationInfo.WINDING_DOWN_DAY
        val report = latest[ReportField.FUELS] ?: return true
        return report.epochDay < since || parseFuels(report.value).isEmpty()
    }

    private fun hours(): Map<HoursKind, Sourced<String>> = buildMap {
        pick(
            osm(tags["opening_hours"]), chain("k:hours:station"),
            tags["afir:hours"]?.let { Sourced(it, InfoSource.AFIR, afirDay) }, driver(ReportField.HOURS_STATION),
        )?.let { put(HoursKind.STATION, it) }
        pick(chain("k:hours:restaurant"), driver(ReportField.HOURS_RESTAURANT))?.let { put(HoursKind.RESTAURANT, it) }
        pick(chain("k:hours:lunch"), driver(ReportField.HOURS_LUNCH))?.let { put(HoursKind.LUNCH, it) }
        chain("k:hours:breakfast")?.let { put(HoursKind.BREAKFAST, it) }
        pick(chain("k:hours:shop"), driver(ReportField.HOURS_SHOP))?.let { put(HoursKind.SHOP, it) }
    }

    private fun service(field: ReportField): Sourced<Boolean>? {
        val yes = { key: String -> chain(key)?.takeIf { it.value == "yes" }?.map { true } }
        val osmYesNo = { key: String -> osm(tags[key]?.takeIf { it == "yes" || it == "no" })?.map { it == "yes" } }
        val candidates = when (field) {
            ReportField.CHARGING -> listOf(yes("k:charging"))
            ReportField.SHOP -> return withSigns(
                field,
                listOf(osm(tags["shop"]?.takeIf(String::isNotBlank))?.map { it != "no" }, yes("k:shop")),
                listOfNotNull(latest[ReportField.HOURS_SHOP]),
            )
            ReportField.RESTAURANT -> return withSigns(
                field,
                listOf(osmRestaurant(), chain("k:restaurant")?.map { true }),
                listOfNotNull(latest[ReportField.HOURS_RESTAURANT], latest[ReportField.HOURS_LUNCH], latest[ReportField.LUNCH]?.takeIf { it.value == "yes" }),
            )
            // Ilmoitettu lounasaika kertoo, että lounas on: uudempi kumoaa "lounasta ei ole". Kuljettajan ilmoituksista
            // vain tekohetkeltään uusin on ehdokas, koska saman päivän ilmoitukset olisivat muuten tasapelissä.
            ReportField.LUNCH -> return pick(chain("k:hours:lunch")?.map { true }, driverLunch())
            ReportField.TRUCK_PARKING -> listOf(osm(tags["capacity:hgv"]?.takeIf(String::isNotBlank))?.map { true }, yes("k:truck_parking"))
            ReportField.SHOWER -> listOf(osmYesNo("shower"), yes("k:shower"))
            ReportField.TOILETS -> listOf(osmYesNo("toilets"), yes("k:toilets"))
            ReportField.SAUNA -> listOf(yes("k:sauna"))
            ReportField.OVERNIGHT -> listOf(osmYesNo("overnight"))
            else -> emptyList()
        }
        return pick(*candidates.toTypedArray(), driver(field)?.let { bool(it) })
    }

    /**
     * Palvelu, jonka toiminnasta uudempi aukiolo- tai lounasilmoitus ([signs]) kertoo: se kumoaa "lopettaneen", jottei
     * ilmoitus jää näkymättä. Yksin se ei tee kohteesta ravintolaa tai kauppaa, vaan kumoaa vain muun lähteen kielteisen tiedon.
     */
    private fun withSigns(field: ReportField, others: List<Sourced<Boolean>?>, signs: List<StationReport>): Sourced<Boolean>? {
        val sign = signs.maxByOrNull { it.atEpochSec }
        val direct = latest[field]
        val driver = when {
            direct == null -> null
            sign != null && sign.atEpochSec > direct.atEpochSec -> Sourced(true, InfoSource.DRIVER, sign.epochDay)
            else -> driver(field)?.let { bool(it) }
        }
        val overrides = sign?.takeIf { others.any { it?.value == false } }?.let { Sourced(true, InfoSource.DRIVER, it.epochDay) }
        return pick(*others.toTypedArray(), driver, overrides)
    }

    /** Lounas kuljettajalta: lounasaika (on) tai suora on/ei-ilmoitus, kumpi tehtiin myöhemmin. */
    private fun driverLunch(): Sourced<Boolean>? {
        val hours = latest[ReportField.HOURS_LUNCH]
        val direct = latest[ReportField.LUNCH]
        return if (hours != null && (direct == null || hours.atEpochSec > direct.atEpochSec)) {
            Sourced(true, InfoSource.DRIVER, hours.epochDay)
        } else {
            driver(ReportField.LUNCH)?.let { bool(it) }
        }
    }

    /** OSM:n ravintolatieto: jokin myönteinen tagi → on; vain kielteisiä (esim. `restaurant=no`) → ei ole. */
    private fun osmRestaurant(): Sourced<Boolean>? {
        val values = listOf("restaurant", "food", "fast_food").mapNotNull { tags[it]?.trim()?.lowercase() }
        return when {
            "yes" in values -> Sourced(true, InfoSource.OSM, osmDay)
            values.isNotEmpty() && values.all { it == "no" } -> Sourced(false, InfoSource.OSM, osmDay)
            else -> null
        }
    }

    private fun osmFuels(): Sourced<List<FuelOffer>>? {
        val offers = OSM_FUEL_KEYS.filter { (_, keys) -> keys.any { tags[it] == "yes" } }.map { FuelOffer(it.first, null) }
        return if (offers.isEmpty()) null else Sourced(offers, InfoSource.OSM, osmDay)
    }

    private fun osmAdblue(): Sourced<String>? {
        val value = tags["fuel:adblue"]?.trim()?.lowercase() ?: return null
        val kind = when {
            value == "no" -> "no"
            tags["fuel:adblue:pump"] == "yes" || value == "pump" -> "pump"
            tags["fuel:adblue:canister"] == "yes" || value == "canister" -> "canister"
            else -> "yes"
        }
        return Sourced(kind, InfoSource.OSM, osmDay)
    }

    private fun osm(value: String?): Sourced<String>? = value?.trim()?.takeIf(String::isNotEmpty)?.let { Sourced(it, InfoSource.OSM, osmDay) }

    private fun chain(key: String): Sourced<String>? = tags[key]?.trim()?.takeIf(String::isNotEmpty)?.let { Sourced(it, InfoSource.CHAIN, chainDay) }

    private fun driver(field: ReportField): Sourced<String>? = latest[field]?.let { Sourced(it.value, InfoSource.DRIVER, it.epochDay) }

    private fun bool(value: Sourced<String>): Sourced<Boolean>? = when (value.value) {
        "yes" -> value.map { true }
        "no" -> value.map { false }
        else -> null
    }
}

private fun <T, R> Sourced<T>.map(transform: (T) -> R): Sourced<R> = Sourced(transform(value), source, epochDay)

/** Uusin päivä voittaa; puuttuva päivä on vanhin; tasapelissä lähteen etusija. */
private fun <T> pick(vararg candidates: Sourced<T>?): Sourced<T>? =
    candidates.filterNotNull().maxWithOrNull(compareBy<Sourced<T>>({ it.epochDay ?: Long.MIN_VALUE }, { rank(it.source) }))

private fun rank(source: InfoSource): Int = when (source) {
    InfoSource.OSM -> 0
    InfoSource.AFIR -> 1
    InfoSource.CHAIN -> 2
    InfoSource.DRIVER -> 3
}

private fun day(text: String?): Long? = try {
    text?.trim()?.takeIf { it.length == 10 }?.let { LocalDate.parse(it).toEpochDay() }
} catch (_: DateTimeParseException) {
    null
}

/** OSM-tagit polttoainelajeiksi (avaimet pieninä kuten putkessa). */
private val OSM_FUEL_KEYS = listOf(
    FuelType.DIESEL to listOf("fuel:diesel", "fuel:hgv_diesel"),
    FuelType.HVO to listOf("fuel:hvo"),
    FuelType.PETROL95 to listOf("fuel:octane_95", "fuel:e10"),
    FuelType.PETROL98 to listOf("fuel:octane_98"),
    FuelType.E85 to listOf("fuel:e85"),
    FuelType.MPO to listOf("fuel:taxfree_diesel"),
    FuelType.BIOGAS to listOf("fuel:biogas"),
    FuelType.LPG to listOf("fuel:lpg"),
    FuelType.CNG to listOf("fuel:cng"),
    FuelType.LNG to listOf("fuel:lng"),
)
