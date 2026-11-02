package fi.tyomatka.ui.poi

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.repo.PoiSettings
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PoiFilterTest {
    private val heavy = PoiSettings(setOf(PoiCategory.FUEL, PoiCategory.REST, PoiCategory.CHARGING), heavyOnly = true, routeOnly = false)

    private fun station(id: String, name: String, hgv: Boolean = false, tags: Map<String, String> = emptyMap(), category: PoiCategory = PoiCategory.FUEL) =
        PoiItem(id, category, GeoPoint(61.17, 23.67), name, null, null, hgv, tags)

    private fun report(poi: String, field: ReportField, value: String, date: String) =
        StationReport(poi, field, value, LocalDate.parse(date).toEpochDay())

    private fun markers(items: List<PoiItem>, reports: List<StationReport> = emptyList(), settings: PoiSettings = heavy) =
        poiMarkers(items, reports, settings, emptyList()).associate { it.item.id to it.dimmed }

    @Test
    fun `rekkasuodattimella Teboil näkyy himmennettynä ja tavallinen asema ei näy`() {
        val shown = markers(listOf(station("n1", "Teboil Akaa"), station("n2", "Neste Akaa"), station("n3", "Neste Truck", hgv = true)))
        assertEquals(mapOf("n1" to true, "n3" to false), shown)
    }

    @Test
    fun `ilmoitettu rekkapysäköinti tuo aseman rekkasuodattimeen`() {
        val shown = markers(listOf(station("n2", "Neste Akaa")), listOf(report("n2", ReportField.TRUCK_PARKING, "yes", "2026-10-23")))
        assertEquals(mapOf("n2" to false), shown)
    }

    @Test
    fun `ketjun rekkapiste tuo aseman rekkasuodattimeen`() {
        val shown = markers(listOf(station("k-abc-1", "ABC Kylmäkoski", tags = mapOf("k:date" to "2026-10-22", "k:hgv_lane" to "yes"))))
        assertEquals(mapOf("k-abc-1" to false), shown)
    }

    @Test
    fun `suljettu rekka-asema on himmennetty ja toiminnassa oleva ei`() {
        val item = station("n3", "Neste Truck", hgv = true)
        assertEquals(mapOf("n3" to true), markers(listOf(item), listOf(report("n3", ReportField.CLOSED, "yes", "2026-10-23"))))
        val reopened = listOf(report("n3", ReportField.CLOSED, "yes", "2026-10-23"), report("n3", ReportField.CLOSED, "no", "2026-10-24"))
        assertEquals(mapOf("n3" to false), markers(listOf(item), reopened))
    }

    @Test
    fun `latauspisteet eivät ole rekkasuodattimen alaisia ja pois valitut luokat jäävät pois`() {
        val items = listOf(station("c-FITSL-1", "Lataus", category = PoiCategory.CHARGING), station("n9", "Kaasu", category = PoiCategory.GAS))
        assertEquals(mapOf("c-FITSL-1" to false), markers(items))
    }

    @Test
    fun `reitin varrella rajaa kuten ennen`() {
        val route = listOf(GeoPoint(60.0, 24.0), GeoPoint(60.0, 25.0))
        val near = station("n1", "Lähellä", hgv = true).copy(location = GeoPoint(60.01, 24.5))
        val far = station("n2", "Kaukana", hgv = true)
        val shown = poiMarkers(listOf(near, far), emptyList(), heavy.copy(routeOnly = true), route).map { it.item.id }
        assertEquals(listOf("n1"), shown)
    }
}
