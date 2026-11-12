package fi.tyomatka.ui.poi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.poi.HoursKind
import fi.tyomatka.core.poi.InfoSource
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.Sourced
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.ui.localDecimal

/** Kortin rivi: teksti ja yliviivaus (Teboilin polttoaineet). */
private data class Line(val text: String, val struck: Boolean = false)

/**
 * Huoltoaseman ja kaasuaseman kortin osiot luonnosten A ja B2 mukaan: varoitus (suljettu tai Teboil), asema,
 * polttoaineet, raskas kalusto, ravintola tai kahvila, kauppa, lataus ja kortit; jokaisen alla lähde ja päivä.
 * Tuntematon tieto sanotaan suoraan, eikä aseman aukioloa oleteta ravintolalle.
 */
@Composable
internal fun StationSections(info: StationInfo, chargingNearby: List<PoiItem>, todayEpochDay: Long, strings: (Int, Array<out Any>) -> String) {
    fun s(id: Int, vararg args: Any) = strings(id, args)
    val labels = PoiCardText.hoursLabels(strings)
    fun hours(kind: HoursKind) = info.hours[kind]?.let { PoiCardText.hours(it.value, labels, strings) }

    when {
        info.closed?.value == true -> Warning(s(R.string.poi_warning_closed), PoiCardText.sourcesLine(listOf(info.closed), strings))
        info.fuelsOff -> Warning(s(R.string.poi_warning_teboil), null)
    }

    val running = PoiCardText.stationStatus(info, strings)
    Section(
        s(R.string.poi_section_station),
        listOfNotNull(running?.let(::Line), Line(hours(HoursKind.STATION) ?: s(R.string.poi_hours_unknown))),
        listOf(info.hours[HoursKind.STATION], info.closed?.takeIf { !it.value }),
        strings,
    )

    val fuelLines = listOfNotNull(PoiCardText.noFuels(info, strings)?.let(::Line)) +
        info.fuels?.value.orEmpty().map { Line(PoiCardText.fuelLabel(it, strings), struck = info.fuelsOff) } +
        listOfNotNull(info.adblue?.let { Line(adblueText(it.value, strings), struck = info.fuelsOff && it.value != "no") })
    Section(s(R.string.poi_section_fuels), fuelLines, listOf(info.fuels, info.adblue), strings)

    val heavy = buildList {
        if (info.hgvLane?.value == true) add(Line(s(R.string.poi_hgv_lane), struck = info.fuelsOff))
        info.truckLengthsM?.value?.maxOrNull()?.let { add(Line(s(R.string.poi_truck_length, decimal(it)))) }
        service(info, ReportField.TRUCK_PARKING, s(R.string.poi_truck_parking), strings)?.let(::add)
        service(info, ReportField.SHOWER, s(R.string.poi_chip_shower), strings)?.let(::add)
        service(info, ReportField.SAUNA, s(R.string.poi_sauna), strings)?.let(::add)
        if ("rest_room" in info.extras) add(Line(s(R.string.poi_rest_room)))
        service(info, ReportField.TOILETS, s(R.string.poi_chip_toilets), strings)?.let(::add)
        info.services[ReportField.OVERNIGHT]?.let { add(Line(s(if (it.value) R.string.poi_chip_overnight else R.string.poi_chip_overnight_no))) }
        info.limits[ReportField.MAX_HEIGHT]?.let { add(Line(s(R.string.poi_chip_max_height, localDecimal(it.value.replace(',', '.'))))) }
        info.limits[ReportField.MAX_WEIGHT]?.let { add(Line(s(R.string.poi_chip_max_weight, localDecimal(it.value.replace(',', '.'))))) }
        if ("carwash" in info.extras) add(Line(s(R.string.poi_carwash)))
    }
    val heavySources = listOf(
        info.hgvLane, info.truckLengthsM, info.services[ReportField.TRUCK_PARKING], info.services[ReportField.SHOWER],
        info.services[ReportField.SAUNA], info.services[ReportField.TOILETS], info.services[ReportField.OVERNIGHT],
        info.limits[ReportField.MAX_HEIGHT], info.limits[ReportField.MAX_WEIGHT],
    )
    Section(s(R.string.poi_section_heavy), heavy, heavySources, strings)

    Section(
        s(R.string.poi_section_food),
        PoiCardText.foodLines(info, todayEpochDay, strings).map(::Line),
        listOf(info.services[ReportField.RESTAURANT], info.services[ReportField.LUNCH], info.hours[HoursKind.RESTAURANT], info.hours[HoursKind.LUNCH], info.hours[HoursKind.BREAKFAST]),
        strings,
    )

    Section(s(R.string.poi_section_shop), PoiCardText.shopLines(info, strings).map(::Line), listOf(info.services[ReportField.SHOP], info.hours[HoursKind.SHOP]), strings)

    val afir = PoiCardText.nearbyChargingLines(chargingNearby, strings)
    if (afir.isNotEmpty() && PoiCardText.showAfirCharging(info, chargingNearby)) {
        val dates = chargingNearby.mapNotNull { point -> point.tags["afir:date"]?.let { Sourced(Unit, InfoSource.AFIR, epochDay(it)) } }
        Section(s(R.string.poi_section_charging), afir.map(::Line), dates, strings)
    } else {
        info.services[ReportField.CHARGING]?.let { charging ->
            Section(s(R.string.poi_section_charging), listOf(Line(s(if (charging.value) R.string.poi_charging_on_site else R.string.poi_charging_none))), listOf(charging), strings)
        }
    }

    info.cards?.let { Section(s(R.string.poi_section_cards), listOf(Line(it.value.joinToString(" · "))), listOf(it), strings) }

}

/** Fintrafficin latauspisteen tiedot latauspisteen kortille: liittimet, operaattori, aukiolo ja hinta. */
@Composable
internal fun ChargingSection(item: PoiItem, info: StationInfo, strings: (Int, Array<out Any>) -> String) {
    fun s(id: Int, vararg args: Any) = strings(id, args)
    val connectors = item.tags["afir:connectors"]?.let { PoiCardText.connectors(it, strings) }?.takeIf(String::isNotEmpty) ?: return
    val lines = buildList {
        add(Line(connectors))
        item.tags["afir:operator"]?.let { add(Line(s(R.string.poi_charging_operator, it))) }
        info.hours[HoursKind.STATION]?.let { add(Line(PoiCardText.hours(it.value, PoiCardText.hoursLabels(strings), strings))) }
        info.electricPrice?.let { add(Line(PoiCardText.electricPrice(it.value, strings))) }
    }
    Section(s(R.string.poi_section_charging), lines, listOf(Sourced(Unit, InfoSource.AFIR, item.tags["afir:date"]?.let(::epochDay)), info.hours[HoursKind.STATION]), strings)
}

/**
 * Lepopaikan ja latauspisteen kortin lähteelliset tiedot: suljettu-varoitus ja aukiolo (ilmoitukset ja OSM).
 * Palvelut näkyvät siruina, joihin [PoiCardText.driverCorrections] tuo ilmoitukset. Fintrafficin latauspisteen aukiolo
 * ja hinta ovat jo [ChargingSection]issa.
 */
@Composable
internal fun OtherSections(item: PoiItem, info: StationInfo, strings: (Int, Array<out Any>) -> String) {
    fun s(id: Int, vararg args: Any) = strings(id, args)
    if (info.closed?.value == true) Warning(s(PoiCardText.closedRes(item.category)), PoiCardText.sourcesLine(listOf(info.closed), strings))
    PoiCardText.placeStatus(info, strings)?.let { Section(s(R.string.poi_section_status), listOf(Line(it)), listOf(info.closed), strings) }
    val denials = PoiCardText.REST_DENIALS.map { (field, _) -> info.services[field]?.takeIf { !it.value && it.source == InfoSource.DRIVER } }
    Section(s(R.string.poi_section_heavy), PoiCardText.restExtras(info, strings).map(::Line), listOf(info.services[ReportField.SAUNA], info.truckLengthsM) + denials, strings)
    if (item.tags["afir:connectors"] != null) return
    info.hours[HoursKind.STATION]?.let { hours ->
        val text = PoiCardText.hours(hours.value, PoiCardText.hoursLabels(strings), strings)
        Section(s(R.string.poi_opening_hours), listOf(Line(text)), listOf(hours), strings)
    }
}

/** Kortin alarivin lähdemaininnat: vain käytetyt lähteet. */
internal fun attribution(item: PoiItem, chargingNearby: List<PoiItem>, strings: (Int, Array<out Any>) -> String): String {
    val osm = item.id.firstOrNull() in setOf('n', 'w', 'r') && item.id.drop(1).all(Char::isDigit)
    val afir = item.id.startsWith("c-") || chargingNearby.isNotEmpty()
    val chainDate = item.tags["k:date"]?.let { epochDay(it) }
    return listOfNotNull(
        strings(R.string.poi_source, emptyArray()).takeIf { osm },
        strings(R.string.poi_attribution_afir, emptyArray()).takeIf { afir },
        chainDate?.let { strings(R.string.poi_attribution_chain, arrayOf(PoiCardText.date(it))) },
    ).joinToString(" · ").ifEmpty { strings(R.string.poi_source, emptyArray()) }
}

private fun adblueText(value: String, strings: (Int, Array<out Any>) -> String): String = strings(
    when (value) {
        "pump" -> R.string.poi_chip_adblue_pump
        "canister" -> R.string.poi_chip_adblue_canister
        "no" -> R.string.poi_adblue_no
        else -> R.string.poi_chip_adblue
    },
    emptyArray(),
)

/** Palvelu kyllä → nimi, ei → "Nimi: ei"; tuntematon → ei riviä. */
private fun service(info: StationInfo, field: ReportField, name: String, strings: (Int, Array<out Any>) -> String): Line? =
    info.services[field]?.let { Line(if (it.value) name else strings(R.string.poi_service_no, arrayOf(name))) }

private fun decimal(value: Double): String = localDecimal(if (value == Math.floor(value)) value.toLong().toString() else value.toString())

private fun epochDay(text: String): Long? = runCatching { java.time.LocalDate.parse(text).toEpochDay() }.getOrNull()

@Composable
private fun Section(title: String, lines: List<Line>, sources: List<Sourced<*>?>, strings: (Int, Array<out Any>) -> String) {
    if (lines.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
    lines.forEach { line ->
        Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (line.struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (line.struck) TextDecoration.LineThrough else null,
        )
    }
    PoiCardText.sourcesLine(sources, strings)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Warning(text: String, source: String?) {
    Spacer(Modifier.height(12.dp))
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onErrorContainer)
            source?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}
