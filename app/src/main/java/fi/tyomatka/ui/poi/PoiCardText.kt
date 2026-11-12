package fi.tyomatka.ui.poi

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import fi.tyomatka.R
import fi.tyomatka.core.api.RouteEta
import fi.tyomatka.core.poi.FuelOffer
import fi.tyomatka.core.poi.FuelType
import fi.tyomatka.core.poi.HoursKind
import fi.tyomatka.core.poi.InfoSource
import fi.tyomatka.core.poi.OpeningHoursText
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiChip
import fi.tyomatka.core.poi.PoiChipKind
import fi.tyomatka.core.poi.PoiCorrectionField
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.Sourced
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatKm
import fi.tyomatka.ui.formatDate
import fi.tyomatka.ui.localClockText
import fi.tyomatka.ui.localDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Kohdekortin tekstit ilman Compose-riippuvuutta: [strings] on `getString(id, *args)`, jotta muotoilu testataan JVM:ssä.
 */
object PoiCardText {
    private val PLAIN_NUMBER = Regex("""\d{1,6}([.,]\d{1,6})?""")

    fun chip(chip: PoiChip, strings: (Int, Array<out Any>) -> String): String {
        fun s(id: Int, vararg args: Any) = strings(id, args)
        return when (chip.kind) {
            PoiChipKind.HGV_DIESEL -> s(R.string.poi_chip_hgv_diesel)
            PoiChipKind.ADBLUE -> when (chip.value) {
                "pump" -> s(R.string.poi_chip_adblue_pump)
                "canister" -> s(R.string.poi_chip_adblue_canister)
                else -> s(R.string.poi_chip_adblue)
            }
            PoiChipKind.TRUCK_PARKING -> chip.value?.let { s(R.string.poi_chip_truck_parking_n, it) } ?: s(R.string.poi_chip_truck_parking)
            PoiChipKind.TOILETS -> s(R.string.poi_chip_toilets)
            PoiChipKind.SHOWER -> s(R.string.poi_chip_shower)
            PoiChipKind.FOOD -> s(R.string.poi_chip_food)
            PoiChipKind.SHOP -> s(R.string.poi_chip_shop)
            PoiChipKind.OPEN_24H -> s(R.string.poi_chip_24h)
            PoiChipKind.CHARGING_SOCKET -> {
                val parts = chip.value.orEmpty().split("|", limit = 2)
                s(R.string.poi_chip_socket, parts[0], parts.getOrElse(1) { "" })
            }
            PoiChipKind.CHARGING_POWER -> s(R.string.poi_chip_power, decimal(chip.value, "kW"))
            PoiChipKind.GAS_KIND -> chip.value.orEmpty()
            PoiChipKind.CAPACITY -> s(R.string.poi_chip_capacity, chip.value.orEmpty())
            PoiChipKind.MAX_HEIGHT -> if (chip.known) s(R.string.poi_chip_max_height, decimal(chip.value, "m")) else s(R.string.poi_chip_unknown_height)
            PoiChipKind.MAX_WEIGHT -> if (chip.known) s(R.string.poi_chip_max_weight, decimal(chip.value, "t")) else s(R.string.poi_chip_unknown_weight)
            PoiChipKind.OVERNIGHT -> when {
                !chip.known -> s(R.string.poi_chip_unknown_overnight)
                chip.value == "no" -> s(R.string.poi_chip_overnight_no)
                else -> s(R.string.poi_chip_overnight)
            }
        }
    }

    /** Matka ja aika reititettynä, linnuntie ilman reittiä, null ilman sijaintia. */
    fun distance(eta: RouteEta?, straightM: Long?, strings: (Int, Array<out Any>) -> String): String? = when {
        eta != null -> strings(R.string.poi_distance_time, arrayOf(formatKm(eta.distanceM), formatDuration(eta.durationSec)))
        straightM != null -> strings(R.string.poi_distance_straight, arrayOf(formatKm(straightM)))
        else -> null
    }

    /** Yli näin vanha ravintolatieto saa kehotteen varmistaa (puoli vuotta). */
    const val RESTAURANT_STALE_DAYS = 183

    /** "22.10.2026", englanniksi "22 Oct 2026". */
    fun date(epochDay: Long): String = formatDate(LocalDate.ofEpochDay(epochDay))

    /** "Ketjun tieto 22.10.2026", "OSM 3.9.2026" (ilman aikaleimaa "OSM"), "Kuljettajan ilmoitus …", "Fintraffic …". */
    fun sourceLine(source: InfoSource, epochDay: Long?, strings: (Int, Array<out Any>) -> String): String {
        val date = epochDay?.let(::date)
        val res = when (source) {
            InfoSource.OSM -> if (date == null) return strings(R.string.poi_src_osm_undated, emptyArray()) else R.string.poi_src_osm
            InfoSource.CHAIN -> R.string.poi_src_chain
            InfoSource.AFIR -> R.string.poi_src_afir
            InfoSource.DRIVER -> R.string.poi_src_driver
        }
        return strings(res, arrayOf(date.orEmpty()))
    }

    /** Osion lähteet: kukin lähde kerran uusimmalla päivällään, uusin ensin, " · "-eroteltuina; null ilman lähteitä. */
    fun sourcesLine(values: List<Sourced<*>?>, strings: (Int, Array<out Any>) -> String): String? =
        values.filterNotNull()
            .groupBy { it.source }
            .map { (source, items) -> source to items.mapNotNull { it.epochDay }.maxOrNull() }
            .sortedByDescending { it.second ?: Long.MIN_VALUE }
            .joinToString(" · ") { (source, day) -> sourceLine(source, day, strings) }
            .takeIf(String::isNotEmpty)

    fun fuelNameRes(type: FuelType): Int = when (type) {
        FuelType.DIESEL -> R.string.poi_fuel_diesel
        FuelType.HVO -> R.string.poi_fuel_hvo
        FuelType.PETROL95 -> R.string.poi_fuel_petrol95
        FuelType.PETROL98 -> R.string.poi_fuel_petrol98
        FuelType.E85 -> R.string.poi_fuel_e85
        FuelType.MPO -> R.string.poi_fuel_mpo
        FuelType.BIOGAS -> R.string.poi_fuel_biogas
        FuelType.LPG -> R.string.poi_fuel_lpg
        FuelType.CNG -> R.string.poi_fuel_cng
        FuelType.LNG -> R.string.poi_fuel_lng
    }

    /** "Uusiutuva diesel · Neste MY"; ilman tuotenimeä pelkkä laji. */
    fun fuelLabel(offer: FuelOffer, strings: (Int, Array<out Any>) -> String): String {
        val name = strings(fuelNameRes(offer.type), emptyArray())
        return offer.product?.let { strings(R.string.poi_fuel_product, arrayOf(name, it)) } ?: name
    }

    /** `CCS2:4:150;Type 2:2:22` → "4 × CCS2 enintään 150 kW · 2 × Type 2 22 kW"; virheelliset osat jätetään pois. */
    fun connectors(value: String, strings: (Int, Array<out Any>) -> String): String =
        value.split(';').mapNotNull { part ->
            val pieces = part.split(':')
            if (pieces.size != 3) return@mapNotNull null
            val count = pieces[1].toIntOrNull() ?: return@mapNotNull null
            val kw = pieces[2].toDoubleOrNull() ?: return@mapNotNull null
            Triple(pieces[0], count, kw)
        }.mapIndexed { index, (type, count, kw) ->
            val power = localDecimal(if (kw == Math.floor(kw)) kw.toLong().toString() else kw.toString())
            strings(if (index == 0) R.string.poi_connectors_max else R.string.poi_connectors, arrayOf(count.toString(), type, power))
        }.joinToString(" · ")

    /** Ravintolatieto on yli puoli vuotta vanha (tai päivätön) eikä kukaan ole vahvistanut sitä sen jälkeen. */
    fun restaurantStale(info: StationInfo, todayEpochDay: Long): Boolean {
        val restaurant = info.services[ReportField.RESTAURANT]?.takeIf { it.value } ?: return false
        val day = restaurant.epochDay ?: return true
        return todayEpochDay - day > RESTAURANT_STALE_DAYS
    }

    /**
     * Kuljettajien ilmoitukset lepopaikan ja latauspisteen siruihin: kentät, joissa uusin tieto on ilmoitus, korvaavat
     * OSM-sirun (sama muoto kuin laitteen vanhoissa korjauksissa).
     */
    fun driverCorrections(info: StationInfo): Map<PoiCorrectionField, String> = buildMap {
        fun yesNo(field: ReportField, target: PoiCorrectionField) {
            info.services[field]?.takeIf { it.source == InfoSource.DRIVER }?.let { put(target, if (it.value) "yes" else "no") }
        }
        yesNo(ReportField.TOILETS, PoiCorrectionField.TOILETS)
        yesNo(ReportField.SHOWER, PoiCorrectionField.SHOWER)
        yesNo(ReportField.TRUCK_PARKING, PoiCorrectionField.TRUCK_PARKING)
        yesNo(ReportField.OVERNIGHT, PoiCorrectionField.OVERNIGHT)
        yesNo(ReportField.RESTAURANT, PoiCorrectionField.FOOD)
        info.adblue?.takeIf { it.source == InfoSource.DRIVER }?.let { put(PoiCorrectionField.ADBLUE, if (it.value == "no") "no" else "yes") }
        info.limits[ReportField.MAX_HEIGHT]?.takeIf { it.source == InfoSource.DRIVER }?.let { put(PoiCorrectionField.MAX_HEIGHT, it.value) }
        info.limits[ReportField.MAX_WEIGHT]?.takeIf { it.source == InfoSource.DRIVER }?.let { put(PoiCorrectionField.MAX_WEIGHT, it.value) }
    }

    /**
     * Näytetäänkö aseman lähistön Fintrafficin latauspisteet: ei, jos kuljettaja on ilmoittanut latauksen poistuneen
     * Fintrafficin tietoa myöhemmin (tasapelissä ilmoitus voittaa).
     */
    fun showAfirCharging(info: StationInfo, chargingNearby: List<PoiItem>): Boolean {
        if (chargingNearby.isEmpty()) return false
        val removed = info.services[ReportField.CHARGING]?.takeIf { !it.value } ?: return true
        val afirDay = chargingNearby.mapNotNull { point ->
            point.tags["afir:date"]?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }
        }.maxOrNull() ?: return false
        val removedDay = removed.epochDay ?: return false
        return afirDay > removedDay
    }

    private fun isStation(category: PoiCategory) = category == PoiCategory.FUEL || category == PoiCategory.GAS

    /** Lepopaikalla ja latauspisteellä sana "asema" olisi väärä. */
    fun statusHeadingRes(category: PoiCategory): Int = if (isStation(category)) R.string.poi_report_station else R.string.poi_section_status
    fun closedRes(category: PoiCategory): Int = if (isStation(category)) R.string.poi_report_closed else R.string.poi_place_closed
    fun mainHoursRes(category: PoiCategory): Int = if (isStation(category)) R.string.poi_report_hours_station else R.string.poi_section_charging
    fun runningRes(category: PoiCategory): Int = if (isStation(category)) R.string.poi_report_open else R.string.poi_place_running

    /** Kuljettajan "Asema toiminnassa": suljettu-varoituksen vastine, jotta ilmoituksesta jää näkyvä tieto. */
    fun stationStatus(info: StationInfo, strings: (Int, Array<out Any>) -> String): String? =
        info.closed?.takeIf { !it.value }?.let { strings(R.string.poi_report_open, emptyArray()) }

    /** Sama lepopaikalle ja latauspisteelle, joilla sana "asema" olisi väärä. */
    fun placeStatus(info: StationInfo, strings: (Int, Array<out Any>) -> String): String? =
        info.closed?.takeIf { !it.value }?.let { strings(R.string.poi_place_running, emptyArray()) }

    /** Valikoimaltaan tyhjä asema; yliviivatulla asemalla varoitus kertoo tämän jo. */
    fun noFuels(info: StationInfo, strings: (Int, Array<out Any>) -> String): String? =
        strings(R.string.poi_report_fuels_none, emptyArray()).takeIf { info.fuels?.value?.isEmpty() == true && !info.fuelsOff }

    /** Kauppaosion rivit; pelkkä ilmoitettu aukiolo riittää osioon. */
    fun shopLines(info: StationInfo, strings: (Int, Array<out Any>) -> String): List<String> {
        val shop = info.services[ReportField.SHOP]
        val hours = info.hours[HoursKind.SHOP]
        return when {
            shop == null && hours == null -> emptyList()
            shop?.value == false -> listOf(strings(R.string.poi_shop_closed, emptyArray()))
            else -> listOf(hours?.let { hours(it.value, hoursLabels(strings), strings) } ?: strings(R.string.poi_hours_unknown, emptyArray()))
        }
    }

    /**
     * Ravintola- tai kahvilaosion rivit; tyhjä, kun ruuasta ei tiedetä mitään. Pelkkä lounasilmoitus on vain lounasrivi:
     * se ei kerro, onko paikka ravintola vai kahvila.
     */
    fun foodLines(info: StationInfo, todayEpochDay: Long, strings: (Int, Array<out Any>) -> String): List<String> {
        fun s(id: Int, vararg args: Any) = strings(id, args)
        val labels = hoursLabels(strings)
        val restaurant = info.services[ReportField.RESTAURANT]
        val lunch = info.services[ReportField.LUNCH]
        val restaurantHours = info.hours[HoursKind.RESTAURANT]
        if (restaurant?.value == false) return listOf(s(R.string.poi_food_closed))
        return buildList {
            val title = when {
                info.restaurantNames.isNotEmpty() -> s(R.string.poi_food_named, info.restaurantNames.joinToString(", "))
                restaurant?.value == true || (restaurantHours != null && !info.cafe) -> s(R.string.poi_food_restaurant)
                else -> null
            }
            when {
                title != null -> add(if (info.cafe) "$title · ${s(R.string.poi_food_cafe)}" else title)
                info.cafe -> add(s(R.string.poi_food_cafe_only))
            }
            if (title != null || info.cafe) add(restaurantHours?.let { hours(it.value, labels, strings) } ?: s(R.string.poi_hours_unknown))
            when {
                lunch?.value == false -> add(s(R.string.poi_lunch_none))
                info.hours[HoursKind.LUNCH] != null -> add(s(R.string.poi_hours_lunch, hoursText(info.hours.getValue(HoursKind.LUNCH).value, labels)))
                lunch?.value == true -> add(s(R.string.poi_report_lunch_yes))
            }
            info.hours[HoursKind.BREAKFAST]?.let { add(s(R.string.poi_hours_breakfast, hoursText(it.value, labels))) }
            if (restaurantStale(info, todayEpochDay)) add(s(R.string.poi_food_stale))
        }
    }

    /** Palvelut, joiden kuljettajan ilmoittama "ei" vain poistaisi sirun lepopaikan kortilta. */
    val REST_DENIALS = listOf(
        ReportField.TOILETS to R.string.poi_chip_toilets,
        ReportField.SHOWER to R.string.poi_chip_shower,
        ReportField.TRUCK_PARKING to R.string.poi_truck_parking,
    )

    /** Lepopaikan ja latauspisteen kortin lisärivit, joille ei ole sirua: sauna, yhdistelmän pituus ja ilmoitettu "ei". */
    fun restExtras(info: StationInfo, strings: (Int, Array<out Any>) -> String): List<String> = buildList {
        info.services[ReportField.SAUNA]?.let { sauna ->
            val name = strings(R.string.poi_sauna, emptyArray())
            add(if (sauna.value) name else strings(R.string.poi_service_no, arrayOf(name)))
        }
        info.truckLengthsM?.value?.maxOrNull()?.let { length ->
            val text = localDecimal(if (length == Math.floor(length)) length.toLong().toString() else length.toString())
            add(strings(R.string.poi_truck_length, arrayOf(text)))
        }
        REST_DENIALS.forEach { (field, name) ->
            info.services[field]?.takeIf { !it.value && it.source == InfoSource.DRIVER }?.let {
                add(strings(R.string.poi_service_no, arrayOf(strings(name, emptyArray()))))
            }
        }
    }

    /** Aukiolon päivät ja 24 h -teksti sovelluksen kielellä. */
    fun hoursLabels(strings: (Int, Array<out Any>) -> String): OpeningHoursText.Labels = OpeningHoursText.Labels(
        days = strings(R.string.poi_hours_days, emptyArray()).split(','),
        allDay = strings(R.string.poi_hours_all_day, emptyArray()),
        and = strings(R.string.poi_hours_and, emptyArray()),
    )

    /** Muotoiltu aukiolo näytön kielen kellonajoin; jäsentämätön OSM-teksti sellaisenaan, koska siinä voi olla päivämääriä. */
    fun hoursText(osm: String, labels: OpeningHoursText.Labels): String {
        val text = OpeningHoursText.format(osm, labels)
        return if (text == osm) osm else localClockText(text)
    }

    /** "Auki 24 h", "Auki ma–pe 6.00–22.00"; muu OSM-aukiolo sellaisenaan. */
    fun hours(osm: String, labels: OpeningHoursText.Labels, strings: (Int, Array<out Any>) -> String): String {
        val text = hoursText(osm, labels)
        return if (text == labels.allDay || text == osm) text else strings(R.string.poi_hours_open, arrayOf(text))
    }

    /** Fintrafficin valmis hinta "0.64 €/kWh" → "Sähkö 0,64 €/kWh". */
    fun electricPrice(value: String, strings: (Int, Array<out Any>) -> String): String =
        strings(R.string.poi_price_value, arrayOf(strings(R.string.fuel_kind_electric, emptyArray()), localDecimal(value)))

    /**
     * Huoltoaseman kortin latausrivit lähistön Fintrafficin latauspisteistä: rivi pistettä kohti, liittimet ja samalla
     * rivillä pisteen hinta, kun Fintraffic kertoo sen. Näin hinta ei näytä koskevan muita pisteitä. Piste ilman
     * luettavia liittimiä jää pois hintoineen.
     */
    fun nearbyChargingLines(chargingNearby: List<PoiItem>, strings: (Int, Array<out Any>) -> String): List<String> =
        chargingNearby.mapNotNull { point ->
            val connectors = point.tags["afir:connectors"]?.let { connectors(it, strings) }?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            listOfNotNull(connectors, point.tags["afir:price"]?.let { electricPrice(it, strings) }).joinToString(" · ")
        }

    /** Luokan nimi valikossa (monikko) ja nimettömän kohteen varanimi (yksikkö). */
    fun categoryLabelRes(category: PoiCategory): Int = when (category) {
        PoiCategory.FUEL -> R.string.poi_cat_fuel
        PoiCategory.CHARGING -> R.string.poi_cat_charging
        PoiCategory.GAS -> R.string.poi_cat_gas
        PoiCategory.REST -> R.string.poi_cat_rest
    }

    fun categoryNameRes(category: PoiCategory): Int = when (category) {
        PoiCategory.FUEL -> R.string.poi_name_fuel
        PoiCategory.CHARGING -> R.string.poi_name_charging
        PoiCategory.GAS -> R.string.poi_name_gas
        PoiCategory.REST -> R.string.poi_name_rest
    }

    fun categoryIcon(category: PoiCategory): ImageVector = when (category) {
        PoiCategory.FUEL -> Icons.Filled.LocalGasStation
        PoiCategory.CHARGING -> Icons.Filled.EvStation
        PoiCategory.GAS -> Icons.Filled.LocalFireDepartment
        PoiCategory.REST -> Icons.Filled.LocalParking
    }

    /** Luokan väri kartalla; kartta on aina vaalea, joten värit ovat vaalean teeman AAA-paletista. */
    fun categoryColor(category: PoiCategory): Color = when (category) {
        PoiCategory.FUEL -> Color(0xFF0B4A9E)
        PoiCategory.CHARGING -> Color(0xFF004D43)
        PoiCategory.GAS -> Color(0xFF7A3900)
        PoiCategory.REST -> Color(0xFF5C5F66)
    }

    /**
     * "3.85" → "3,85", "4.2 m" → "4,2", "150" → "150": tagin oma [unit] pois ja enintään kaksi desimaalia katkaistuna,
     * jottei korkeus- tai painoraja näytä sallivan enempää kuin merkki; muu teksti sellaisenaan.
     */
    private fun decimal(value: String?, unit: String): String {
        val text = value.orEmpty().trim()
        val bare = if (text.endsWith(unit, ignoreCase = true)) text.dropLast(unit.length).trim() else text
        // Vain tavallinen desimaaliluku: kohteen tagi tulee palvelimelta, ja eksponenttimuoto ("1e999999999") kaataisi setScalen.
        if (!PLAIN_NUMBER.matches(bare)) return text
        val number = bare.replace(',', '.').toBigDecimal()
        return localDecimal(number.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString())
    }
}
