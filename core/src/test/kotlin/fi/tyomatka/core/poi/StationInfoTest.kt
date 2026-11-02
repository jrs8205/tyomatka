package fi.tyomatka.core.poi

import fi.tyomatka.core.model.GeoPoint
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationInfoTest {
    private fun day(text: String) = LocalDate.parse(text).toEpochDay()

    private fun item(tags: Map<String, String>, name: String? = "Neste Kylmäkoski", brand: String? = "Neste", hgv: Boolean = false) =
        PoiItem("n1", PoiCategory.FUEL, GeoPoint(61.17, 23.67), name, brand, null, hgv, tags)

    private fun report(field: ReportField, value: String, date: String, poi: String = "n1") = StationReport(poi, field, value, day(date))

    private val chainHours = mapOf("k:date" to "2026-10-22", "k:hours:station" to "Mo-Fr 06:00-22:00")

    @Test
    fun `ketjun aukiolo voittaa vanhemman OSM-tiedon`() {
        val info = StationInfo.of(item(chainHours + mapOf("opening_hours" to "24/7", "osm_ts" to "2026-09-03")), emptyList())
        assertEquals(Sourced("Mo-Fr 06:00-22:00", InfoSource.CHAIN, day("2026-10-22")), info.hours[HoursKind.STATION])
    }

    @Test
    fun `myöhemmin muuttunut OSM voittaa ketjun`() {
        val info = StationInfo.of(item(chainHours + mapOf("opening_hours" to "24/7", "osm_ts" to "2026-11-01")), emptyList())
        assertEquals(Sourced("24/7", InfoSource.OSM, day("2026-11-01")), info.hours[HoursKind.STATION])
    }

    @Test
    fun `OSM ilman aikaleimaa on vanhin`() {
        val info = StationInfo.of(item(chainHours + mapOf("opening_hours" to "24/7")), emptyList())
        assertEquals(InfoSource.CHAIN, info.hours[HoursKind.STATION]?.source)
    }

    @Test
    fun `kuljettajan ilmoitus voittaa vanhemman OSM-tiedon ja häviää uudemmalle`() {
        val report = listOf(report(ReportField.HOURS_STATION, "Mo-Su 07:00-21:00", "2026-10-25"))
        val older = StationInfo.of(item(mapOf("opening_hours" to "24/7", "osm_ts" to "2026-10-24")), report)
        assertEquals(Sourced("Mo-Su 07:00-21:00", InfoSource.DRIVER, day("2026-10-25")), older.hours[HoursKind.STATION])
        val newer = StationInfo.of(item(mapOf("opening_hours" to "24/7", "osm_ts" to "2026-10-30")), report)
        assertEquals(InfoSource.OSM, newer.hours[HoursKind.STATION]?.source)
    }

    @Test
    fun `tasapelissä kuljettaja voittaa`() {
        val info = StationInfo.of(item(chainHours), listOf(report(ReportField.HOURS_STATION, "24/7", "2026-10-22")))
        assertEquals(InfoSource.DRIVER, info.hours[HoursKind.STATION]?.source)
    }

    @Test
    fun `muiden kohteiden ilmoitukset eivät vaikuta`() {
        val info = StationInfo.of(item(chainHours), listOf(report(ReportField.HOURS_STATION, "24/7", "2026-10-30", poi = "n2")))
        assertEquals(InfoSource.CHAIN, info.hours[HoursKind.STATION]?.source)
    }

    @Test
    fun `Teboil on himmennetty eikä sen polttoaineisiin luoteta`() {
        val info = StationInfo.of(item(mapOf("fuel:diesel" to "yes"), name = "Teboil Akaa", brand = "Teboil"), emptyList())
        assertTrue(info.fuelsOff)
        assertTrue(info.dimmed)
        val chain = StationInfo.of(item(mapOf("k:date" to "2026-10-22", "k:status" to "winding_down"), name = "Asema", brand = null), emptyList())
        assertTrue(chain.fuelsOff)
    }

    @Test
    fun `kuljettajan uudempi polttoaineilmoitus kumoaa Teboilin yliviivauksen`() {
        val teboil = item(mapOf("k:date" to "2026-10-22", "k:status" to "winding_down", "k:fuels" to "diesel=Diesel;petrol95=95 E10"), name = "Teboil Akaa", brand = "Teboil")
        val info = StationInfo.of(teboil, listOf(report(ReportField.FUELS, "diesel", "2026-11-01")))
        assertFalse(info.fuelsOff)
        assertFalse(info.dimmed)
        assertEquals(Sourced(listOf(FuelOffer(FuelType.DIESEL, null)), InfoSource.DRIVER, day("2026-11-01")), info.fuels)
    }

    @Test
    fun `kertatuontia vanhempi ilmoitus ei kumoa yliviivausta`() {
        val teboil = item(mapOf("k:date" to "2026-10-22", "k:status" to "winding_down"), name = "Teboil Akaa", brand = "Teboil")
        assertTrue(StationInfo.of(teboil, listOf(report(ReportField.FUELS, "diesel", "2026-10-20"))).fuelsOff)
    }

    @Test
    fun `OSM-tiedossa myöhemmin toiselle ketjulle vaihtunut asema ei ole enää Teboil`() {
        val tags = mapOf("k:date" to "2026-10-22", "k:status" to "winding_down")
        val renamed = StationInfo.of(item(tags + ("osm_ts" to "2026-11-05"), name = "Neste Akaa", brand = "Neste"), emptyList())
        assertFalse(renamed.fuelsOff)
        assertFalse(renamed.dimmed)
        val older = StationInfo.of(item(tags + ("osm_ts" to "2026-09-01"), name = "Neste Akaa", brand = "Neste"), emptyList())
        assertTrue(older.fuelsOff)
        val stillTeboil = StationInfo.of(item(tags + ("osm_ts" to "2026-11-05"), name = "Teboil Akaa", brand = "Teboil"), emptyList())
        assertTrue(stillTeboil.fuelsOff)
    }

    @Test
    fun `Teboil-nimi ei himmennä latauspistettä eikä lepopaikkaa`() {
        val charger = PoiItem("c-FITEB-1", PoiCategory.CHARGING, GeoPoint(61.17, 23.67), "Teboil Akaa lataus", "Teboil", null, false, emptyMap())
        val info = StationInfo.of(charger, emptyList())
        assertFalse(info.fuelsOff)
        assertFalse(info.dimmed)
    }

    @Test
    fun `samana päivänä myöhemmin tehty ilmoitus voittaa listan järjestyksestä riippumatta`() {
        val day = day("2026-10-23")
        val afternoon = StationReport("n1", ReportField.CLOSED, "yes", day, atEpochSec = day * 86_400 + 15 * 3_600)
        val morningQueued = StationReport("n1", ReportField.CLOSED, "no", day, atEpochSec = day * 86_400 + 9 * 3_600)
        val info = StationInfo.of(item(emptyMap()), listOf(afternoon, morningQueued))
        assertEquals(true, info.closed?.value)
    }

    @Test
    fun `myöhemmin ilmoitettu lounasaika kumoaa lounasta ei ole -ilmoituksen`() {
        val reports = listOf(
            report(ReportField.LUNCH, "no", "2026-10-23"),
            report(ReportField.HOURS_LUNCH, "Mo-Fr 10:30-14:00", "2026-10-24"),
        )
        assertEquals(Sourced(true, InfoSource.DRIVER, day("2026-10-24")), StationInfo.of(item(emptyMap()), reports).services[ReportField.LUNCH])
        val onlyNo = StationInfo.of(item(emptyMap()), reports.take(1))
        assertEquals(false, onlyNo.services[ReportField.LUNCH]?.value)
    }

    @Test
    fun `saman tekohetken tasapelissä palvelin voittaa vain ennen hakua kuitatun oman listan järjestyksestä riippumatta`() {
        val at = day("2026-10-23") * 86_400 + 7 * 3_600
        fun closed(value: String, rank: Long) = StationReport("n1", ReportField.CLOSED, value, day("2026-10-23"), atEpochSec = at, rank = rank)
        val fetchedLater = closed("no", ReportRank.server(at + 60))
        val cachedEarlier = closed("no", ReportRank.server(at + 5))
        val sent = closed("yes", ReportRank.sent(at + 10))
        val pending = closed("yes", ReportRank.PENDING)
        // Kuittauksen jälkeen haettu vastaus tunsi oman rivin ja ratkaisi tasapelin rivitunnisteella.
        assertEquals(false, StationInfo.of(item(emptyMap()), listOf(sent, fetchedLater, sent)).closed?.value)
        assertEquals(false, StationInfo.of(item(emptyMap()), listOf(fetchedLater, sent)).closed?.value)
        // Ennen kuittausta haettu välimuistivastaus ei voinut tuntea omaa riviä.
        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(sent, cachedEarlier)).closed?.value)
        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(cachedEarlier, sent)).closed?.value)
        // Kuittaus samalla sekunnilla kuin haku: järjestystä ei tiedetä, joten oma voittaa.
        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(sent, closed("no", ReportRank.server(at + 10)))).closed?.value)
        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(pending, fetchedLater)).closed?.value)
        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(fetchedLater, pending, fetchedLater)).closed?.value)
    }

    @Test
    fun `samana päivänä lounasaika ja lounasta ei ole ratkeavat tekohetken mukaan`() {
        val day = day("2026-10-23")
        val hoursMorning = StationReport("n1", ReportField.HOURS_LUNCH, "Mo-Fr 10:30-14:00", day, atEpochSec = day * 86_400 + 8 * 3_600)
        val noAfternoon = StationReport("n1", ReportField.LUNCH, "no", day, atEpochSec = day * 86_400 + 14 * 3_600)
        assertEquals(false, StationInfo.of(item(emptyMap()), listOf(hoursMorning, noAfternoon)).services[ReportField.LUNCH]?.value)
        assertEquals(false, StationInfo.of(item(emptyMap()), listOf(noAfternoon, hoursMorning)).services[ReportField.LUNCH]?.value)

        val noMorning = StationReport("n1", ReportField.LUNCH, "no", day, atEpochSec = day * 86_400 + 8 * 3_600)
        val hoursAfternoon = StationReport("n1", ReportField.HOURS_LUNCH, "Mo-Fr 10:30-14:00", day, atEpochSec = day * 86_400 + 14 * 3_600)
        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(noMorning, hoursAfternoon)).services[ReportField.LUNCH]?.value)
        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(hoursAfternoon, noMorning)).services[ReportField.LUNCH]?.value)
    }

    @Test
    fun `uudempi OSM-tieto ravintolan puuttumisesta voittaa vanhemman myönteisen`() {
        val tags = mapOf("k:date" to "2026-10-22", "k:restaurant" to "Kotipizza", "restaurant" to "no", "osm_ts" to "2026-11-01")
        assertEquals(Sourced(false, InfoSource.OSM, day("2026-11-01")), StationInfo.of(item(tags), emptyList()).services[ReportField.RESTAURANT])
        val older = tags + ("osm_ts" to "2026-09-01")
        assertEquals(true, StationInfo.of(item(older), emptyList()).services[ReportField.RESTAURANT]?.value)
    }

    @Test
    fun `suljettu ja myöhemmin toiminnassa ei ole suljettu`() {
        val reports = listOf(report(ReportField.CLOSED, "yes", "2026-10-23"), report(ReportField.CLOSED, "no", "2026-10-25"))
        val info = StationInfo.of(item(emptyMap()), reports)
        assertEquals(Sourced(false, InfoSource.DRIVER, day("2026-10-25")), info.closed)
        assertFalse(info.dimmed)
        val closed = StationInfo.of(item(mapOf("k:date" to "2026-10-22", "k:status" to "closed")), emptyList())
        assertEquals(true, closed.closed?.value)
        assertTrue(closed.dimmed)
    }

    @Test
    fun `ketjun rekkapiste tekee asemasta raskaan kaluston aseman`() {
        val info = StationInfo.of(item(mapOf("k:date" to "2026-10-22", "k:hgv_lane" to "yes")), emptyList())
        assertTrue(info.effectiveHgv)
        assertEquals(true, info.hgvLane?.value)
        assertFalse(StationInfo.of(item(emptyMap()), emptyList()).effectiveHgv)
        val parking = StationInfo.of(item(emptyMap()), listOf(report(ReportField.TRUCK_PARKING, "yes", "2026-10-23")))
        assertTrue(parking.effectiveHgv)
    }

    @Test
    fun `yhdistelmän pituusluokat`() {
        val info = StationInfo.of(item(mapOf("k:date" to "2026-10-22", "k:truck_length" to "14;34.5")), emptyList())
        assertEquals(listOf(14.0, 34.5), info.truckLengthsM?.value)
    }

    @Test
    fun `OSM-tagit kartoitetaan kuten siruissa`() {
        val tags = mapOf(
            "osm_ts" to "2026-09-03", "fuel:diesel" to "yes", "fuel:hgv_diesel" to "yes", "fuel:octane_95" to "yes", "fuel:lpg" to "yes",
            "fuel:adblue" to "yes", "fuel:adblue:pump" to "yes", "toilets" to "yes", "shower" to "no", "shop" to "convenience",
            "maxheight" to "4.2", "fast_food" to "yes",
        )
        val info = StationInfo.of(item(tags), emptyList())
        assertEquals(listOf(FuelType.DIESEL, FuelType.PETROL95, FuelType.LPG), info.fuels?.value?.map { it.type })
        assertEquals(Sourced("pump", InfoSource.OSM, day("2026-09-03")), info.adblue)
        assertEquals(true, info.services[ReportField.TOILETS]?.value)
        assertEquals(false, info.services[ReportField.SHOWER]?.value)
        assertEquals(true, info.services[ReportField.SHOP]?.value)
        assertEquals(true, info.services[ReportField.RESTAURANT]?.value)
        assertEquals("4.2", info.limits[ReportField.MAX_HEIGHT]?.value)
        assertEquals(true, info.hgvLane?.value)
    }

    @Test
    fun `ketjun ravintolat, kortit ja lisäpalvelut`() {
        val tags = mapOf(
            "k:date" to "2026-10-22", "k:restaurant" to "Kotipizza;Hesburger", "k:cafe" to "yes", "k:cards" to "Neste-kortti;DKV",
            "k:carwash" to "yes", "k:rest_room" to "yes", "k:hours:lunch" to "Mo-Fr 10:30-14:00",
        )
        val info = StationInfo.of(item(tags), emptyList())
        assertEquals(listOf("Kotipizza", "Hesburger"), info.restaurantNames)
        assertTrue(info.cafe)
        assertEquals(listOf("Neste-kortti", "DKV"), info.cards?.value)
        assertEquals(setOf("carwash", "rest_room"), info.extras)
        assertEquals(true, info.services[ReportField.RESTAURANT]?.value)
        assertEquals(true, info.services[ReportField.LUNCH]?.value)
        assertEquals("Mo-Fr 10:30-14:00", info.hours[HoursKind.LUNCH]?.value)
    }

    @Test
    fun `sähkön hinta vain Fintrafficilta`() {
        val charging = PoiItem("c-FITSL-1", PoiCategory.CHARGING, GeoPoint(60.3, 25.0), "Lataus", null, null, false,
            mapOf("afir:date" to "2026-10-23", "afir:price" to "0.64 €/kWh", "afir:hours" to "24/7"))
        val info = StationInfo.of(charging, emptyList())
        assertEquals(Sourced("0.64 €/kWh", InfoSource.AFIR, day("2026-10-23")), info.electricPrice)
        assertNull(StationInfo.of(charging.copy(tags = charging.tags - "afir:price"), emptyList()).electricPrice)
        assertEquals(InfoSource.AFIR, info.hours[HoursKind.STATION]?.source)
        assertNull(info.fuels)
    }

    @Test
    fun `uudempi ravintolan aukiolo kumoaa lopettaneen mutta vanhempi ei`() {
        val closed = report(ReportField.RESTAURANT, "no", "2026-10-30")

        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(closed, report(ReportField.HOURS_RESTAURANT, "24/7", "2026-10-31"))).services[ReportField.RESTAURANT]?.value)
        assertEquals(false, StationInfo.of(item(emptyMap()), listOf(closed, report(ReportField.HOURS_RESTAURANT, "24/7", "2026-10-29"))).services[ReportField.RESTAURANT]?.value)
    }

    @Test
    fun `uudempi lounas kumoaa lopettaneen ravintolan mutta lounasta ei ole ei kumoa`() {
        val closed = report(ReportField.RESTAURANT, "no", "2026-10-30")

        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(closed, report(ReportField.LUNCH, "yes", "2026-10-31"))).services[ReportField.RESTAURANT]?.value)
        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(closed, report(ReportField.HOURS_LUNCH, "24/7", "2026-10-31"))).services[ReportField.RESTAURANT]?.value)
        assertEquals(false, StationInfo.of(item(emptyMap()), listOf(closed, report(ReportField.LUNCH, "no", "2026-10-31"))).services[ReportField.RESTAURANT]?.value)
    }

    @Test
    fun `pelkkä lounasilmoitus ei tee kohteesta ravintolaa`() {
        val info = StationInfo.of(item(emptyMap()), listOf(report(ReportField.LUNCH, "yes", "2026-10-31")))

        assertEquals(null, info.services[ReportField.RESTAURANT])
    }

    @Test
    fun `uudempi ilmoitus kumoaa OSM-tiedon ei ravintolaa`() {
        val tags = mapOf("restaurant" to "no", "osm_ts" to "2026-10-01")

        assertEquals(true, StationInfo.of(item(tags), listOf(report(ReportField.HOURS_RESTAURANT, "24/7", "2026-10-31"))).services[ReportField.RESTAURANT]?.value)
        assertEquals(false, StationInfo.of(item(tags), listOf(report(ReportField.HOURS_RESTAURANT, "24/7", "2026-09-01"))).services[ReportField.RESTAURANT]?.value)
    }

    @Test
    fun `uudempi kaupan aukiolo kumoaa lopettaneen kaupan`() {
        val closed = report(ReportField.SHOP, "no", "2026-10-30")

        assertEquals(true, StationInfo.of(item(emptyMap()), listOf(closed, report(ReportField.HOURS_SHOP, "24/7", "2026-10-31"))).services[ReportField.SHOP]?.value)
        assertEquals(false, StationInfo.of(item(emptyMap()), listOf(closed, report(ReportField.HOURS_SHOP, "24/7", "2026-10-29"))).services[ReportField.SHOP]?.value)
    }

    @Test
    fun `kentän lähde kertoo mistä kortin tieto tulee`() {
        val tags = mapOf("k:date" to "2026-10-22", "k:status" to "closed", "k:hours:shop" to "24/7", "osm_ts" to "2026-10-01", "maxheight" to "4.0")
        val reports = listOf(
            report(ReportField.CLOSED, "no", "2026-10-15"),
            report(ReportField.FUELS, "diesel", "2026-10-30"),
            report(ReportField.ADBLUE, "pump", "2026-10-30"),
            report(ReportField.HOURS_STATION, "24/7", "2026-10-30"),
            report(ReportField.HOURS_RESTAURANT, "24/7", "2026-10-30"),
            report(ReportField.HOURS_LUNCH, "24/7", "2026-10-30"),
            report(ReportField.HOURS_SHOP, "24/7", "2026-10-15"),
            report(ReportField.SHOWER, "yes", "2026-10-30"),
            report(ReportField.TRUCK_LENGTH, "14", "2026-10-30"),
            report(ReportField.MAX_HEIGHT, "3.8", "2026-10-30"),
        )
        val info = StationInfo.of(item(tags), reports)

        assertEquals(InfoSource.CHAIN, info.sourceOf(ReportField.CLOSED))
        assertEquals(InfoSource.CHAIN, info.sourceOf(ReportField.HOURS_SHOP))
        listOf(
            ReportField.FUELS, ReportField.ADBLUE, ReportField.HOURS_STATION, ReportField.HOURS_RESTAURANT, ReportField.HOURS_LUNCH,
            ReportField.SHOWER, ReportField.TRUCK_LENGTH, ReportField.MAX_HEIGHT,
        ).forEach { assertEquals(InfoSource.DRIVER, info.sourceOf(it), it.key) }
        assertEquals(null, info.sourceOf(ReportField.MAX_WEIGHT))
        assertEquals(null, info.sourceOf(ReportField.SAUNA))
    }
}
