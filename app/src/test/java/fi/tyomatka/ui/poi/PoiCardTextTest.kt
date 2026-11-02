package fi.tyomatka.ui.poi

import fi.tyomatka.R
import fi.tyomatka.core.api.RouteEta
import fi.tyomatka.core.poi.PoiChip
import fi.tyomatka.core.poi.PoiChipKind
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.FuelOffer
import fi.tyomatka.core.poi.FuelType
import fi.tyomatka.core.poi.InfoSource
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiCorrectionField
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoiCardTextTest {
    /** Resurssien korvike: suomenkieliset mallit kuten strings.xml:ssä; muotoilu Androidin getString-tapaan. */
    private val templates = mapOf(
        R.string.poi_chip_socket to "%1\$s ×%2\$s",
        R.string.poi_chip_power to "%1\$s kW",
        R.string.poi_chip_max_height to "Korkeusraja %1\$s m",
        R.string.poi_chip_unknown_height to "Korkeusraja: ei tietoa",
        R.string.poi_chip_max_weight to "Painoraja %1\$s t",
        R.string.poi_chip_adblue_pump to "AdBlue pumppu",
        R.string.poi_chip_overnight_no to "Ei yöpymistä",
        R.string.poi_chip_truck_parking_n to "Rekkaparkki %1\$s paikkaa",
        R.string.poi_distance_time to "%1\$s · %2\$s nykysijainnista",
        R.string.poi_distance_straight to "%1\$s linnuntietä",
        R.string.poi_price_value to "%1\$s %2\$s",
        R.string.fuel_kind_electric to "Sähkö",
        R.string.fuel_section_diesel to "Diesel",
        R.string.poi_src_chain to "Ketjun tieto %1\$s",
        R.string.poi_src_osm to "OSM %1\$s",
        R.string.poi_src_osm_undated to "OSM",
        R.string.poi_src_driver to "Kuljettajan ilmoitus %1\$s",
        R.string.poi_src_afir to "Fintraffic %1\$s",
        R.string.poi_fuel_hvo to "Uusiutuva diesel",
        R.string.poi_fuel_product to "%1\$s · %2\$s",
        R.string.poi_connectors_max to "%1\$s × %2\$s enintään %3\$s kW",
        R.string.poi_connectors to "%1\$s × %2\$s %3\$s kW",
        R.string.poi_report_open to "Asema toiminnassa",
        R.string.poi_place_running to "Toiminnassa",
        R.string.poi_shop_closed to "Kauppa lopettanut",
        R.string.poi_report_fuels_none to "Asema ei myy polttoaineita",
        R.string.poi_report_lunch_yes to "Lounas on",
        R.string.poi_lunch_none to "Lounasta ei ole",
        R.string.poi_food_closed to "Ravintola lopettanut",
        R.string.poi_food_cafe to "kahvila",
        R.string.poi_food_cafe_only to "Kahvila",
        R.string.poi_food_restaurant to "Ravintola",
        R.string.poi_food_named to "Ravintola %1\$s",
        R.string.poi_food_stale to "Vanha tieto",
        R.string.poi_hours_unknown to "Aukiolo ei tiedossa",
        R.string.poi_hours_open to "Auki %1\$s",
        R.string.poi_hours_lunch to "Lounas %1\$s",
        R.string.poi_hours_breakfast to "Aamiainen %1\$s",
        R.string.poi_hours_all_day to "Auki 24 h",
        R.string.poi_hours_and to "ja",
        R.string.poi_hours_days to "ma,ti,ke,to,pe,la,su",
    )
    private val strings: (Int, Array<out Any>) -> String = { id, args -> String.format(templates.getValue(id), *args) }

    @Test
    fun `sirut muotoillaan lajin ja arvon mukaan`() {
        assertEquals("Type 2 ×4", PoiCardText.chip(PoiChip(PoiChipKind.CHARGING_SOCKET, "Type 2|4"), strings))
        assertEquals("150 kW", PoiCardText.chip(PoiChip(PoiChipKind.CHARGING_POWER, "150"), strings))
        assertEquals("Korkeusraja 4,2 m", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, "4.2"), strings))
        assertEquals("Korkeusraja: ei tietoa", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, null, known = false), strings))
        assertEquals("AdBlue pumppu", PoiCardText.chip(PoiChip(PoiChipKind.ADBLUE, "pump"), strings))
        assertEquals("Ei yöpymistä", PoiCardText.chip(PoiChip(PoiChipKind.OVERNIGHT, "no"), strings))
        assertEquals("Rekkaparkki 12 paikkaa", PoiCardText.chip(PoiChip(PoiChipKind.TRUCK_PARKING, "12"), strings))
    }

    @Test
    fun `rajat näytetään annetuin desimaalein eikä niitä pyöristetä ylöspäin`() {
        assertEquals("Korkeusraja 3,85 m", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, "3.85"), strings))
        assertEquals("Korkeusraja 4,15 m", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, "4,15", corrected = true), strings))
        assertEquals("Korkeusraja 4,15 m", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, "4.159"), strings))
        assertEquals("Korkeusraja 4 m", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, "4.00"), strings))
        assertEquals("Painoraja 7,5 t", PoiCardText.chip(PoiChip(PoiChipKind.MAX_WEIGHT, "7.5"), strings))
    }

    @Test
    fun `eksponenttimuoto ja ylipitkä luku näytetään sellaisenaan laskematta`() {
        assertEquals("Korkeusraja 1e999999999 m", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, "1e999999999"), strings))
        assertEquals("Painoraja 1E+5 t", PoiCardText.chip(PoiChip(PoiChipKind.MAX_WEIGHT, "1E+5"), strings))
        val long = "1".repeat(40)
        assertEquals("$long kW", PoiCardText.chip(PoiChip(PoiChipKind.CHARGING_POWER, long), strings))
    }

    @Test
    fun `tagin oma yksikkö ei toistu`() {
        assertEquals("Korkeusraja 4,2 m", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, "4.2 m"), strings))
        assertEquals("Korkeusraja 3,8 m", PoiCardText.chip(PoiChip(PoiChipKind.MAX_HEIGHT, "3.8m"), strings))
        assertEquals("Painoraja 40 t", PoiCardText.chip(PoiChip(PoiChipKind.MAX_WEIGHT, "40 t"), strings))
        assertEquals("22 kW", PoiCardText.chip(PoiChip(PoiChipKind.CHARGING_POWER, "22 kW"), strings))
    }

    @Test
    fun `matka ja aika reititettynä, linnuntietä ilman reittiä, ei mitään ilman sijaintia`() {
        assertEquals("12,3 km · 14 min nykysijainnista", PoiCardText.distance(RouteEta(12_300, 846), 11_000, strings))
        assertEquals("11,0 km linnuntietä", PoiCardText.distance(null, 11_000, strings))
        assertNull(PoiCardText.distance(null, null, strings))
    }

    @Test
    fun `lähde ja päivämäärä`() {
        assertEquals("Ketjun tieto 22.10.2026", PoiCardText.sourceLine(InfoSource.CHAIN, 20_748, strings))
        assertEquals("OSM 3.9.2026", PoiCardText.sourceLine(InfoSource.OSM, LocalDate.of(2026, 9, 3).toEpochDay(), strings))
        assertEquals("OSM", PoiCardText.sourceLine(InfoSource.OSM, null, strings))
        assertEquals("Kuljettajan ilmoitus 21.10.2026", PoiCardText.sourceLine(InfoSource.DRIVER, 20_747, strings))
        assertEquals("Fintraffic 22.10.2026", PoiCardText.sourceLine(InfoSource.AFIR, 20_748, strings))
    }

    @Test
    fun `liittimet tehon mukaan ja suurin teho ensin`() {
        assertEquals("4 × CCS2 enintään 150 kW · 2 × Type 2 22 kW", PoiCardText.connectors("CCS2:4:150;Type 2:2:22", strings))
        assertEquals("2 × Type 2 enintään 12,8 kW", PoiCardText.connectors("Type 2:2:12.8", strings))
        assertEquals("", PoiCardText.connectors("rikki;x:y:z", strings))
    }

    @Test
    fun `polttoaine tuotenimen kanssa`() {
        assertEquals("Uusiutuva diesel · Neste MY", PoiCardText.fuelLabel(FuelOffer(FuelType.HVO, "Neste MY"), strings))
        assertEquals("Uusiutuva diesel", PoiCardText.fuelLabel(FuelOffer(FuelType.HVO, null), strings))
    }

    @Test
    fun `ravintolan tieto vanhenee puolessa vuodessa ilman tuoretta vahvistusta`() {
        val chainDay = LocalDate.of(2026, 10, 22).toEpochDay()
        val station = PoiItem("n1", PoiCategory.FUEL, GeoPoint(61.17, 23.67), "Neste", null, null, false, mapOf("k:date" to "2026-10-22", "k:restaurant" to "Kotipizza"))
        assertTrue(PoiCardText.restaurantStale(StationInfo.of(station, emptyList()), chainDay + 184))
        assertFalse(PoiCardText.restaurantStale(StationInfo.of(station, emptyList()), chainDay + 183))
        val confirmed = listOf(StationReport("n1", ReportField.RESTAURANT, "yes", chainDay + 174))
        assertFalse(PoiCardText.restaurantStale(StationInfo.of(station, confirmed), chainDay + 184))
        val none = PoiItem("n2", PoiCategory.FUEL, GeoPoint(61.17, 23.67), "Neste", null, null, false, emptyMap())
        assertFalse(PoiCardText.restaurantStale(StationInfo.of(none, emptyList()), chainDay + 400))
    }

    private fun station(tags: Map<String, String> = emptyMap(), name: String? = "Neste") =
        PoiItem("n1", PoiCategory.FUEL, GeoPoint(61.17, 23.67), name, null, null, false, tags)

    @Test
    fun `asema toiminnassa jää näkyviin kortille`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val open = StationInfo.of(station(), listOf(StationReport("n1", ReportField.CLOSED, "no", day)))
        val closed = StationInfo.of(station(), listOf(StationReport("n1", ReportField.CLOSED, "yes", day)))

        assertEquals("Asema toiminnassa", PoiCardText.stationStatus(open, strings))
        assertNull(PoiCardText.stationStatus(closed, strings), "suljettu on varoitus, ei tämä rivi")
        assertNull(PoiCardText.stationStatus(StationInfo.of(station(), emptyList()), strings))
    }

    @Test
    fun `pelkkä lounasilmoitus näkyy eikä tee asemasta kahvilaa`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val info = StationInfo.of(station(), listOf(StationReport("n1", ReportField.LUNCH, "yes", day)))

        assertEquals(listOf("Lounas on"), PoiCardText.foodLines(info, day, strings))
    }

    @Test
    fun `lounasta ei ole näkyy`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val info = StationInfo.of(station(), listOf(StationReport("n1", ReportField.LUNCH, "no", day)))

        assertEquals(listOf("Lounasta ei ole"), PoiCardText.foodLines(info, day, strings))
    }

    @Test
    fun `ilmoitettu ravintolan aukiolo näkyy ravintolana`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val info = StationInfo.of(station(), listOf(StationReport("n1", ReportField.HOURS_RESTAURANT, "24/7", day)))

        assertEquals(listOf("Ravintola", "Auki 24 h"), PoiCardText.foodLines(info, day, strings))
    }

    @Test
    fun `kahvilan ilmoitettu aukiolo ei tee siitä ravintolaa`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val info = StationInfo.of(station(mapOf("k:cafe" to "yes")), listOf(StationReport("n1", ReportField.HOURS_RESTAURANT, "24/7", day)))

        assertEquals(listOf("Kahvila", "Auki 24 h"), PoiCardText.foodLines(info, day, strings))
    }

    @Test
    fun `pelkkä kaupan aukiolo näkyy kortilla`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        fun lines(vararg reports: StationReport) = PoiCardText.shopLines(StationInfo.of(station(), reports.toList()), strings)

        assertEquals(emptyList(), lines())
        assertEquals(listOf("Auki 24 h"), lines(StationReport("n1", ReportField.HOURS_SHOP, "24/7", day)))
        assertEquals(listOf("Aukiolo ei tiedossa"), lines(StationReport("n1", ReportField.SHOP, "yes", day)))
        assertEquals(listOf("Kauppa lopettanut"), lines(StationReport("n1", ReportField.SHOP, "no", day)))
    }

    @Test
    fun `ei polttoaineita näkyy kortilla paitsi yliviivatulla asemalla`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val none = listOf(StationReport("n1", ReportField.FUELS, "none", day))

        assertEquals("Asema ei myy polttoaineita", PoiCardText.noFuels(StationInfo.of(station(), none), strings))
        assertNull(PoiCardText.noFuels(StationInfo.of(station(name = "Teboil Laukaa"), none), strings), "varoitus kertoo jo")
        assertNull(PoiCardText.noFuels(StationInfo.of(station(), emptyList()), strings), "tuntematon valikoima")
        assertNull(PoiCardText.noFuels(StationInfo.of(station(), listOf(StationReport("n1", ReportField.FUELS, "diesel", day))), strings))
    }

    @Test
    fun `muun kohteen toiminnassa on lajista riippumaton`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val rest = PoiItem("w5", PoiCategory.REST, GeoPoint(61.17, 23.67), "P-alue", null, null, false, emptyMap())

        assertEquals("Toiminnassa", PoiCardText.placeStatus(StationInfo.of(rest, listOf(StationReport("w5", ReportField.CLOSED, "no", day))), strings))
        assertNull(PoiCardText.placeStatus(StationInfo.of(rest, listOf(StationReport("w5", ReportField.CLOSED, "yes", day))), strings))
    }

    @Test
    fun `lepopaikan ei-ilmoitus jää näkyviin riviksi`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val rest = PoiItem("w5", PoiCategory.REST, GeoPoint(61.17, 23.67), "P-alue", null, null, false, mapOf("toilets" to "no", "osm_ts" to "2026-10-01"))
        val local: (Int, Array<out Any>) -> String = { id, args ->
            when (id) {
                R.string.poi_chip_toilets -> "WC"
                R.string.poi_chip_shower -> "Suihku"
                R.string.poi_truck_parking -> "Rekkaparkki"
                R.string.poi_service_no -> "${args[0]}: ei"
                else -> error("tuntematon $id")
            }
        }

        assertEquals(emptyList(), PoiCardText.restExtras(StationInfo.of(rest, emptyList()), local), "OSM:n tieto ei ole ilmoitus")
        assertEquals(
            listOf("WC: ei", "Suihku: ei", "Rekkaparkki: ei"),
            PoiCardText.restExtras(
                StationInfo.of(
                    rest,
                    listOf(ReportField.TOILETS, ReportField.SHOWER, ReportField.TRUCK_PARKING).map { StationReport("w5", it, "no", day) },
                ),
                local,
            ),
        )
        assertEquals(emptyList(), PoiCardText.restExtras(StationInfo.of(rest, listOf(StationReport("w5", ReportField.SHOWER, "yes", day))), local), "kyllä näkyy sirussa")
    }

    @Test
    fun `lopettanut ravintola on ainoa rivi`() {
        val day = LocalDate.of(2026, 11, 1).toEpochDay()
        val info = StationInfo.of(
            station(),
            listOf(StationReport("n1", ReportField.RESTAURANT, "no", day), StationReport("n1", ReportField.LUNCH, "yes", day)),
        )

        assertEquals(listOf("Ravintola lopettanut"), PoiCardText.foodLines(info, day, strings))
    }

    @Test
    fun `ruokatiedot ennallaan muissa tapauksissa`() {
        val day = LocalDate.of(2026, 10, 22).toEpochDay()

        assertEquals(emptyList(), PoiCardText.foodLines(StationInfo.of(station(), emptyList()), day, strings))
        assertEquals(listOf("Kahvila", "Aukiolo ei tiedossa"), PoiCardText.foodLines(StationInfo.of(station(mapOf("k:cafe" to "yes")), emptyList()), day, strings))
        assertEquals(
            listOf("Ravintola Nordia · kahvila", "Auki 24 h", "Lounas Auki 24 h"),
            PoiCardText.foodLines(
                StationInfo.of(station(mapOf("k:date" to "2026-10-22", "k:restaurant" to "Nordia", "k:cafe" to "yes", "k:hours:restaurant" to "24/7", "k:hours:lunch" to "24/7")), emptyList()),
                day,
                strings,
            ),
        )
        assertEquals(
            listOf("Ravintola", "Aukiolo ei tiedossa", "Vanha tieto"),
            PoiCardText.foodLines(StationInfo.of(station(mapOf("k:date" to "2026-10-22", "k:restaurant" to "yes")), emptyList()), day + 200, strings),
        )
    }

    @Test
    fun `kuljettajien ilmoitukset lepopaikan siruihin`() {
        val day = LocalDate.of(2026, 10, 23).toEpochDay()
        val rest = PoiItem("w5", PoiCategory.REST, GeoPoint(61.17, 23.67), "P-alue", null, null, false, mapOf("toilets" to "yes", "osm_ts" to "2026-01-01"))
        val reports = listOf(
            StationReport("w5", ReportField.TOILETS, "no", day),
            StationReport("w5", ReportField.TRUCK_PARKING, "yes", day),
            StationReport("w5", ReportField.MAX_HEIGHT, "4.2", day),
            StationReport("w5", ReportField.OVERNIGHT, "no", day),
        )
        assertEquals(
            mapOf(
                PoiCorrectionField.TOILETS to "no", PoiCorrectionField.TRUCK_PARKING to "yes",
                PoiCorrectionField.MAX_HEIGHT to "4.2", PoiCorrectionField.OVERNIGHT to "no",
            ),
            PoiCardText.driverCorrections(StationInfo.of(rest, reports)),
        )
        assertEquals(emptyMap(), PoiCardText.driverCorrections(StationInfo.of(rest, emptyList())))
    }

    @Test
    fun `uudempi ilmoitus latauksen poistumisesta peittää Fintrafficin latauspisteet`() {
        val day = LocalDate.of(2026, 10, 24).toEpochDay()
        val station = PoiItem("n1", PoiCategory.FUEL, GeoPoint(61.17, 23.67), "Neste", null, null, false, emptyMap())
        val charger = PoiItem("c-FITSL-1", PoiCategory.CHARGING, GeoPoint(61.17, 23.67), "Lataus", null, null, false, mapOf("afir:date" to "2026-10-23"))
        val removed = StationInfo.of(station, listOf(StationReport("n1", ReportField.CHARGING, "no", day)))
        assertFalse(PoiCardText.showAfirCharging(removed, listOf(charger)))
        val newerAfir = charger.copy(tags = mapOf("afir:date" to "2026-10-25"))
        assertTrue(PoiCardText.showAfirCharging(removed, listOf(newerAfir)))
        assertTrue(PoiCardText.showAfirCharging(StationInfo.of(station, emptyList()), listOf(charger)))
        assertFalse(PoiCardText.showAfirCharging(StationInfo.of(station, emptyList()), emptyList()))
    }

    @Test
    fun `lepopaikan sauna ja yhdistelmän pituus näkyvät ilmoituksista`() {
        val day = LocalDate.of(2026, 10, 24).toEpochDay()
        val rest = PoiItem("w5", PoiCategory.REST, GeoPoint(61.17, 23.67), "P-alue", null, null, false, emptyMap())
        val reports = listOf(StationReport("w5", ReportField.SAUNA, "yes", day), StationReport("w5", ReportField.TRUCK_LENGTH, "25.25", day))
        val templates = mapOf(R.string.poi_sauna to "Sauna", R.string.poi_truck_length to "Yhdistelmä %1\$s m", R.string.poi_service_no to "%1\$s: ei")
        val local: (Int, Array<out Any>) -> String = { id, args -> String.format(templates.getValue(id), *args) }
        assertEquals(listOf("Sauna", "Yhdistelmä 25,25 m"), PoiCardText.restExtras(StationInfo.of(rest, reports), local))
        assertEquals(listOf("Sauna: ei"), PoiCardText.restExtras(StationInfo.of(rest, listOf(StationReport("w5", ReportField.SAUNA, "no", day))), local))
    }

    @Test
    fun `sähkön hinta kielen desimaalilla`() {
        assertEquals("Sähkö 0,64 €/kWh", PoiCardText.electricPrice("0.64 €/kWh", strings))
    }

    @Test
    fun `asema-sana vain asemalla, muualla neutraali tila`() {
        listOf(PoiCategory.FUEL, PoiCategory.GAS).forEach {
            assertEquals(R.string.poi_report_station, PoiCardText.statusHeadingRes(it))
            assertEquals(R.string.poi_report_closed, PoiCardText.closedRes(it))
            assertEquals(R.string.poi_report_open, PoiCardText.runningRes(it))
            assertEquals(R.string.poi_report_hours_station, PoiCardText.mainHoursRes(it))
        }
        listOf(PoiCategory.REST, PoiCategory.CHARGING).forEach {
            assertEquals(R.string.poi_section_status, PoiCardText.statusHeadingRes(it))
            assertEquals(R.string.poi_place_closed, PoiCardText.closedRes(it))
            assertEquals(R.string.poi_place_running, PoiCardText.runningRes(it))
            assertEquals(R.string.poi_section_charging, PoiCardText.mainHoursRes(PoiCategory.CHARGING))
        }
    }
}
