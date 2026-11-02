package fi.tyomatka.ui.plan.nearby

import fi.tyomatka.R
import fi.tyomatka.core.poi.FuelType
import fi.tyomatka.core.poi.HoursKind
import fi.tyomatka.core.poi.OpenState
import fi.tyomatka.core.poi.OpeningHoursText
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiChipKind
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiServices
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.ui.localDecimal
import fi.tyomatka.ui.localShortClock
import fi.tyomatka.ui.poi.PoiCardText
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** Kohdelistan rivien tekstit näytön kielellä. */
object NearbyText {
    private val REST_KINDS = setOf(
        PoiChipKind.TRUCK_PARKING, PoiChipKind.TOILETS, PoiChipKind.SHOWER, PoiChipKind.FOOD, PoiChipKind.SHOP, PoiChipKind.OVERNIGHT,
    )
    private val ADBLUE_KNOWN = setOf("pump", "canister", "yes")

    /** Linnuntie: alle 10 km yhdellä desimaalilla ("0,9 km"), muuten kokonaislukuna ("23 km"). */
    fun distance(km: Double): String =
        if (km < 9.95) localDecimal(String.format(Locale.ROOT, "%.1f km", km)) else "${km.roundToInt()} km"

    fun fuelShortRes(type: FuelType): Int = when (type) {
        FuelType.DIESEL -> R.string.fuel_short_diesel
        FuelType.HVO -> R.string.fuel_short_hvo
        FuelType.PETROL95 -> R.string.fuel_short_petrol95
        FuelType.PETROL98 -> R.string.fuel_short_petrol98
        FuelType.E85 -> R.string.fuel_short_e85
        FuelType.MPO -> R.string.fuel_short_mpo
        FuelType.BIOGAS -> R.string.fuel_short_biogas
        FuelType.LPG -> R.string.fuel_short_lpg
        FuelType.CNG -> R.string.fuel_short_cng
        FuelType.LNG -> R.string.fuel_short_lng
    }

    /**
     * Rivin lisätieto: polttoaine- ja kaasuasemalla polttoaineet lyhyinä nimillä ja AdBlue, latauspisteellä liitännät
     * kortin muodossa (tai suurin teho ja pistokkeet), lepopaikalla tunnetut palvelut; null, kun mitään ei tiedetä.
     */
    fun details(item: PoiItem, info: StationInfo, strings: (Int, Array<out Any>) -> String): String? {
        val parts = when (item.category) {
            PoiCategory.FUEL, PoiCategory.GAS ->
                info.fuels?.value.orEmpty().map { strings(fuelShortRes(it.type), emptyArray()) } +
                    listOfNotNull(info.adblue?.value?.takeIf { it in ADBLUE_KNOWN }?.let { strings(R.string.poi_chip_adblue, emptyArray()) })
            PoiCategory.CHARGING ->
                item.tags["afir:connectors"]?.let { PoiCardText.connectors(it, strings) }?.takeIf(String::isNotEmpty)?.let(::listOf)
                    ?: PoiServices.chips(item)
                        .filter { it.kind == PoiChipKind.CHARGING_POWER || it.kind == PoiChipKind.CHARGING_SOCKET }
                        .sortedBy { if (it.kind == PoiChipKind.CHARGING_POWER) 0 else 1 }
                        .map { PoiCardText.chip(it, strings) }
            // Kuljettajien ilmoitukset korvaavat palvelut kentittäin kuten kortissa.
            PoiCategory.REST -> PoiServices.chips(item, PoiCardText.driverCorrections(info))
                .filter { it.known && it.kind in REST_KINDS }
                .map { PoiCardText.chip(it, strings) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * Aseman aukiolo nyt: "Auki 24 h", "Auki klo 21 asti", "Kiinni, aukeaa ma klo 6"; muu kuin yksinkertainen aukiolo
     * kortin muodossa; null, kun aukioloa ei tiedetä tai asema on suljettu ("auki" suljetulla asemalla olisi pahin virhe).
     */
    fun hours(info: StationInfo, at: LocalDateTime, strings: (Int, Array<out Any>) -> String): String? {
        if (info.closed?.value == true) return null
        val osm = info.hours[HoursKind.STATION]?.value ?: return null
        val labels = PoiCardText.hoursLabels(strings)
        fun day(day: DayOfWeek) = labels.days[day.ordinal]
        return when (val state = OpeningHoursText.openState(osm, at)) {
            null -> PoiCardText.hours(osm, labels, strings)
            OpenState.AllDay -> labels.allDay
            is OpenState.OpenUntil -> {
                val time = localShortClock(OpeningHoursText.closesShort(state.time))
                state.day?.let { strings(R.string.nearby_open_until_day, arrayOf(day(it), time)) } ?: strings(R.string.nearby_open_until, arrayOf(time))
            }
            is OpenState.ClosedUntil -> {
                val time = localShortClock(OpeningHoursText.opensShort(state.time))
                state.day?.let { strings(R.string.nearby_closed_until_day, arrayOf(day(it), time)) } ?: strings(R.string.nearby_closed_until, arrayOf(time))
            }
        }
    }
}
