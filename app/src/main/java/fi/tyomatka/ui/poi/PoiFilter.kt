package fi.tyomatka.ui.poi

import fi.tyomatka.core.geo.distanceToPolylineKm
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.repo.PoiSettings

/** Kartan merkki; [dimmed] = suljettu asema tai Teboil, jonka polttoaineisiin ei luoteta (määrittely, päätökset 7 ja 8). */
data class PoiMarker(val item: PoiItem, val dimmed: Boolean)

/** "Vain reitin varrella": etäisyys reittiviivasta. */
const val POI_ROUTE_KM = 2.0

/**
 * Näytettävät merkit: valitut luokat, raskaan kaluston suodatin ja reitin varrella. Kertatuonnin tiedot ja kuljettajien
 * ilmoitukset vaikuttavat kuten OSM-tieto: ketjun rekkapiste tai ilmoitettu rekkapysäköinti tuo aseman rekkasuodattimeen.
 * Himmennetty Teboil näkyy rekkasuodattimellakin, jotta kahvila ja lounas löytyvät (päätös 8).
 */
fun poiMarkers(items: List<PoiItem>, reports: List<StationReport>, settings: PoiSettings, route: List<GeoPoint>): List<PoiMarker> {
    val byPoi = reports.groupBy { it.poiId }
    return items.mapNotNull { item ->
        if (item.category !in settings.categories) return@mapNotNull null
        val info = StationInfo.of(item, byPoi[item.id].orEmpty())
        val heavy = !settings.heavyOnly || heavyOk(item, info)
        val routeOk = !settings.routeOnly || route.size < 2 || distanceToPolylineKm(item.location, route) <= POI_ROUTE_KM
        if (heavy && routeOk) PoiMarker(item, info.dimmed) else null
    }
}

/** Raskas-rajaus (kartta ja kohdelista): rajattavat lajit vain raskaalle kalustolle sopivina, himmennetty Teboil mukana. */
internal fun heavyOk(item: PoiItem, info: StationInfo): Boolean =
    !item.category.heavyFiltered() || info.effectiveHgv || info.fuelsOff
