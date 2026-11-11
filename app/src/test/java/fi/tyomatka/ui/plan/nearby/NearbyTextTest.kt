package fi.tyomatka.ui.plan.nearby

import fi.tyomatka.R
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.ui.poi.PoiCardText
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NearbyTextTest {
    private val strings = StringsXml.strings

    private fun station(tags: Map<String, String>, category: PoiCategory = PoiCategory.FUEL) =
        PoiItem("n1", category, GeoPoint(60.3, 24.8), "Asema", null, null, false, tags)

    private fun info(tags: Map<String, String>, category: PoiCategory = PoiCategory.FUEL) = StationInfo.of(station(tags, category), emptyList())

    /** Maanantai 26.10.2026 + [days] päivää klo [hour]. */
    private fun at(days: Long, hour: Int): LocalDateTime = LocalDate.of(2026, 10, 26).plusDays(days).atTime(hour, 0)

    @Test
    fun `etäisyys alle 10 km yhdellä desimaalilla, muuten kokonaislukuna`() {
        assertEquals("0,9 km", NearbyText.distance(0.94))
        assertEquals("9,9 km", NearbyText.distance(9.94))
        assertEquals("10 km", NearbyText.distance(9.95))
        assertEquals("10 km", NearbyText.distance(9.96))
        assertEquals("23 km", NearbyText.distance(23.4))
    }

    @Test
    fun `polttoaineet lyhyinä nimillä ja AdBlue`() {
        val diesel = info(mapOf("fuel:diesel" to "yes", "fuel:hvo" to "yes", "fuel:adblue" to "yes", "fuel:adblue:pump" to "yes"))
        val text = NearbyText.details(station(emptyMap()), diesel, strings)
        assertEquals("Diesel · HVO · AdBlue", text)
        assertEquals("95 · 98 · E85", NearbyText.details(station(emptyMap()), info(mapOf("fuel:octane_95" to "yes", "fuel:octane_98" to "yes", "fuel:e85" to "yes")), strings))
        assertNull(NearbyText.details(station(emptyMap()), info(emptyMap()), strings))
        // Tiedossa, ettei AdBluea ole: ei mainita.
        assertEquals("Diesel", NearbyText.details(station(emptyMap()), info(mapOf("fuel:diesel" to "yes", "fuel:adblue" to "no")), strings))
        assertEquals("Diesel · AdBlue", NearbyText.details(station(emptyMap()), info(mapOf("fuel:diesel" to "yes", "fuel:adblue" to "yes")), strings))
        assertEquals("AdBlue", NearbyText.details(station(emptyMap()), info(mapOf("fuel:adblue" to "yes", "fuel:adblue:canister" to "yes")), strings))
        // Kaasuasemalla samoin.
        val gas = station(mapOf("fuel:lpg" to "yes", "fuel:cng" to "yes"), PoiCategory.GAS)
        assertEquals("Nestekaasu · CNG", NearbyText.details(gas, StationInfo.of(gas, emptyList()), strings))
    }

    @Test
    fun `lyhyet polttoainenimet resursseista`() {
        val expected = mapOf(
            "DIESEL" to "Diesel", "HVO" to "HVO", "PETROL95" to "95", "PETROL98" to "98", "E85" to "E85", "MPO" to "MPÖ",
            "BIOGAS" to "Biokaasu", "LPG" to "Nestekaasu", "CNG" to "CNG", "LNG" to "LNG",
        )
        fi.tyomatka.core.poi.FuelType.entries.forEach { assertEquals(expected.getValue(it.name), strings(NearbyText.fuelShortRes(it), emptyArray())) }
    }

    @Test
    fun `latauspisteen liitännät kortin muodossa tai teho ja pistokkeet`() {
        val afir = station(mapOf("afir:connectors" to "CCS2:4:150;Type 2:2:22"), PoiCategory.CHARGING)
        // Luvun ja yksikön välissä sitova väli (U+00A0).
        assertEquals("4 × CCS2 enintään 150 kW · 2 × Type 2 22 kW", NearbyText.details(afir, StationInfo.of(afir, emptyList()), strings))
        // Muut sirut (24 h) eivät tule latauspisteen riville.
        val osm = station(mapOf("socket:type2" to "2", "charging_station:output" to "22 kW", "opening_hours" to "24/7"), PoiCategory.CHARGING)
        // Suurin teho ensin, sitten pistokkeet.
        assertEquals("22 kW · Type 2 ×2", NearbyText.details(osm, StationInfo.of(osm, emptyList()), strings))
        // Kelvoton AFIR-teksti: sirut.
        val broken = station(mapOf("afir:connectors" to "CCS2:x:150", "socket:type2" to "2", "charging_station:output" to "22 kW"), PoiCategory.CHARGING)
        assertEquals("22 kW · Type 2 ×2", NearbyText.details(broken, StationInfo.of(broken, emptyList()), strings))
    }

    @Test
    fun `lepopaikan tunnetut palvelut kuten kortissa`() {
        val rest = station(mapOf("hgv" to "yes", "toilets" to "yes", "shower" to "yes", "opening_hours" to "24/7"), PoiCategory.REST)
        val expected = listOf(R.string.poi_chip_truck_parking, R.string.poi_chip_toilets, R.string.poi_chip_shower)
            .joinToString(" · ") { strings(it, emptyArray()) }

        assertEquals(expected, NearbyText.details(rest, StationInfo.of(rest, emptyList()), strings))
        // Tuntemattomat rajat ja yöpyminen eivät tule riville.
        assertNull(NearbyText.details(station(emptyMap(), PoiCategory.REST), info(emptyMap(), PoiCategory.REST), strings))
    }

    @Test
    fun `lepopaikan palvelut kuljettajien ilmoitusten mukaan kuten kortissa`() {
        val rest = station(mapOf("toilets" to "yes", "shower" to "yes"), PoiCategory.REST)
        val reports = listOf(
            StationReport("n1", ReportField.TOILETS, "no", 20_750),
            StationReport("n1", ReportField.TRUCK_PARKING, "yes", 20_750),
        )
        val expected = listOf(R.string.poi_chip_truck_parking, R.string.poi_chip_shower).joinToString(" · ") { strings(it, emptyArray()) }

        assertEquals(expected, NearbyText.details(rest, StationInfo.of(rest, reports), strings))
    }

    @Test
    fun `aukiolo nyt`() {
        fun hours(osm: String, at: LocalDateTime) = NearbyText.hours(info(mapOf("opening_hours" to osm)), at, strings)
        assertEquals("Auki 24 h", hours("24/7", at(0, 3)))
        assertEquals("Auki klo 21 asti", hours("Mo-Su 06:00-21:00", at(0, 12)))
        assertEquals("Kiinni, aukeaa klo 6", hours("Mo-Su 06:00-21:00", at(0, 23)))
        assertEquals("Auki klo 24 asti", hours("Fr 06:00-24:00", at(4, 12)))
        assertEquals("Auki pe klo 24 asti", hours("Mo-Fr 00:00-24:00; Sa 06:00-22:00", at(0, 12)))
        assertEquals("Kiinni, aukeaa ma klo 0", hours("Mo 00:00-06:00", at(5, 12)))
        // Muu OSM-aukiolo kortin tapaan; ei aukioloa → ei riviä.
        assertEquals("Mo-Fr 06:00-22:00; PH off", hours("Mo-Fr 06:00-22:00; PH off", at(0, 12)))
        val ambiguous = "Sa 16:00-03:00; Su 16:00-23:00"
        assertEquals(PoiCardText.hours(ambiguous, PoiCardText.hoursLabels(strings), strings), hours(ambiguous, at(6, 1)))
        assertNotEquals(ambiguous, hours(ambiguous, at(6, 1)))
        assertNull(NearbyText.hours(info(emptyMap()), at(0, 12), strings))
    }

    @Test
    fun `suljetulla asemalla ei aukioloa`() {
        val closed = info(mapOf("k:status" to "closed", "opening_hours" to "Mo-Su 06:00-21:00"))
        assertNull(NearbyText.hours(closed, at(0, 12), strings))
    }
}
