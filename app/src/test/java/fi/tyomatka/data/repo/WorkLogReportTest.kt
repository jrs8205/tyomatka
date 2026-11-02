package fi.tyomatka.data.repo

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.report.ExportPeriod
import fi.tyomatka.data.report.ReportLanguage
import fi.tyomatka.data.report.ReportLocale
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raportin luvut repositorion kautta: lajit, yksiköt, vajaat, valuutat, jakson rajaus ja varmuuskopion versio 6. */
class WorkLogReportTest {
    private val nb = '\u00A0'
    private val zone = ZoneId.systemDefault()
    private val noon = LocalDate.of(2026, 9, 25).atTime(12, 0).atZone(zone).toEpochSecond()

    private fun fillup(
        id: Long,
        km: Long = 100_000,
        at: Long = noon,
        liters: Double = 50.0,
        cost: Double = 90.0,
        kind: FuelKind = FuelKind.DIESEL,
        full: Boolean = true,
        currency: String = "EUR",
        plate: String = "ABC-123",
    ) = FuelFillupEntity(
        id, at, plate, km, liters, cost, if (liters > 0) cost / liters else 0.0,
        kind = kind, fullTank = full, currency = currency,
    )

    /** Työjakso 25.9.2026; [endKm] null = lopetuslukema puuttuu (jakso on päättynyt). */
    private fun WorkLogTestStore.drive(id: Long, plate: String?, startKm: Long, endKm: Long?, date: LocalDate = LocalDate.of(2026, 9, 25)) {
        val day = date.toEpochDay()
        val routeDay = days.firstOrNull { it.dateEpochDay == day } ?: RouteDayEntity(id = day, dateEpochDay = day).also { days.add(it) }
        val start = date.atTime(8, 0).atZone(zone).toEpochSecond() + id * 600
        segments.add(WorkSegmentEntity(id, routeDay.id, start, start + 300, startKm, endKm, plate))
    }

    private fun String.section(id: String): String = substringAfter("<section id=\"s-$id\"").substringBefore("</section>")

    /** Viikot, kuukaudet ja vuodet -osion kuukausipaneeli. */
    private fun String.months(): String =
        substringAfter("<div class=\"panel\" id=\"p-months\">").substringBefore("<div class=\"panel\" id=\"p-years\">")

    private fun String.data(): String = substringAfter("id=\"tyomatka-data\">").substringBefore("</script>")

    @Test fun `report shows kinds, partial fill-ups and currencies`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.add(fillup(1))
        store.fillups.add(fillup(2, liters = 2.0, cost = 1.8, kind = FuelKind.ADBLUE))
        store.fillups.add(fillup(3, km = 100_300, at = noon + 3600, liters = 20.0, cost = 400.0, full = false, currency = "SEK"))

        val report = store.repository.exportHtml(noon)

        val months = report.months()
        assertTrue("Diesel · tankattu <b class=\"nw\">70,0${nb}l</b> · 90,00${nb}€ + 400,00${nb}SEK" in months, months)
        assertTrue("AdBlue · tankattu <b class=\"nw\">2,0${nb}l</b> · 1,80${nb}€" in months, months)
        val days = report.section("days")
        assertTrue("<span class=\"tag\">vajaa</span>" in days)
        assertTrue("AdBlue · tankattu" in days)
        val fills = report.section("fills")
        assertTrue("<td>AdBlue</td>" in fills)
        assertTrue("<td>Diesel (vajaa)</td>" in fills)
        assertTrue("400,00${nb}SEK" in fills)
        assertTrue("20,000${nb}SEK/l" in fills)
        // Ei vanhoja euro-oletuksia selitteessä.
        assertFalse("euro-osuus" in report)
    }

    @Test fun `month consumption uses each fuel's own kilometres like the cards`() = runTest {
        val store = WorkLogTestStore()
        store.drive(1, "D-1", 100_000, 100_500)
        store.drive(2, "E-1", 5_000, 5_200)
        store.fillups.add(fillup(1, km = 100_500, liters = 45.0, cost = 90.0, plate = "D-1"))
        store.fillups.add(fillup(2, km = 5_200, liters = 30.0, cost = 7.5, kind = FuelKind.ELECTRIC, plate = "E-1"))

        val months = store.repository.exportHtml(noon).months()
        assertTrue("<b>9,0${nb}l/100${nb}km</b> · 0,18${nb}€/km" in months, months)
        assertTrue("<b>15,0${nb}kWh/100${nb}km</b>" in months, months)

        // Rekkariton jakso usean polttoaineen kuussa: kilometrejä ei voi kohdistaa, joten suhdetta ei lasketa.
        store.drive(3, null, 100_500, 100_600)
        val unassigned = store.repository.exportHtml(noon).months()
        assertFalse("/100${nb}km" in unassigned, unassigned)
    }

    @Test fun `single fuel month counts plateless kilometres and a missing reading hides consumption`() = runTest {
        val store = WorkLogTestStore()
        store.drive(1, null, 100_000, 100_400)
        store.drive(2, "D-1", 100_400, 100_500)
        store.fillups.add(fillup(1, km = 100_500, liters = 50.0, plate = "D-1"))
        assertTrue("<b>10,0${nb}l/100${nb}km</b>" in store.repository.exportHtml(noon).months())

        store.drive(3, "D-1", 100_500, null)
        assertFalse("/100${nb}km" in store.repository.exportHtml(noon).months())
    }

    @Test fun `summary groups rows by car and shows no cost per km for AdBlue`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.add(fillup(1, km = 5_000, at = noon - 86_400, liters = 30.0, cost = 7.5, kind = FuelKind.ELECTRIC, plate = "E-1"))
        store.fillups.add(fillup(2, km = 5_200, at = noon - 3600, liters = 32.0, cost = 8.0, kind = FuelKind.ELECTRIC, plate = "E-1"))
        store.fillups.add(fillup(3, km = 100_000, at = noon - 7200, plate = "D-1"))
        store.fillups.add(fillup(4, km = 100_000, at = noon - 7200, liters = 2.0, cost = 1.8, kind = FuelKind.ADBLUE, plate = "D-1"))
        store.fillups.add(fillup(5, km = 100_800, plate = "D-1"))
        // AdBlue aiemmin kuin diesel: pelkkä viimeisimmän tankkauksen järjestys erottaisi auton rivit.
        store.fillups.add(fillup(6, km = 100_800, at = noon - 5400, liters = 3.0, cost = 2.7, kind = FuelKind.ADBLUE, plate = "D-1"))

        val summary = store.repository.exportHtml(noon).section("summary")

        val lines = Regex("<li class=\"carline\">.*?</li>", RegexOption.DOT_MATCHES_ALL).findAll(summary).map { it.value }.toList()
        assertEquals(
            listOf("<b class=\"nw\">D-1</b> · Diesel", "<b class=\"nw\">D-1</b> · AdBlue", "<b class=\"nw\">E-1</b> · Sähkö"),
            lines.map { Regex("<b class=\"nw\">[^<]*</b> · [^<]*").find(it)!!.value },
        )
        assertTrue("€/km" in lines[0])
        assertFalse("€/km" in lines[1])
    }

    @Test fun `report dates start with a capital weekday and kilometres are grouped like in the app`() = runTest {
        // "Pe 25.9.2026" ja "475 279 km" kuten sovelluksessa; lauseen sisällä päivä pienellä.
        val store = WorkLogTestStore()
        store.drive(1, "ABC-123", 465_000, 475_279)
        store.fillups.add(fillup(1, km = 475_279))

        val report = store.repository.exportHtml(noon)

        assertTrue("viety pe${nb}25.9.2026 klo${nb}" in report)
        assertTrue("<h3><span class=\"nw\">Pe${nb}25.9.2026</span></h3>" in report)
        assertFalse(">pe${nb}25.9.2026<" in report)
        assertTrue("475${nb}279${nb}km" in report)
        assertTrue("10${nb}279${nb}km" in report)
        assertTrue("465${nb}000${nb}– 475${nb}279${nb}km" in report)
        assertFalse("475279" in report.substringBefore("id=\"tyomatka-data\""))
    }

    @Test fun `report shows electricity and gas in their own units and words`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.add(fillup(5, km = 5_000, liters = 40.0, cost = 10.0, kind = FuelKind.ELECTRIC, plate = "EL-1"))
        store.fillups.add(fillup(6, km = 9_000, liters = 12.0, cost = 24.0, kind = FuelKind.GAS, plate = "GAS-1"))

        val report = store.repository.exportHtml(noon)

        val months = report.months()
        assertTrue("Ladattu <b class=\"nw\">40,0${nb}kWh</b>" in months, months)
        assertTrue("Täytetty <b class=\"nw\">12,0${nb}kg</b>" in months, months)
        val fills = report.section("fills")
        assertTrue("Lataukset ja täytöt" in fills)
        assertTrue("0,250${nb}€/kWh" in fills)
        assertTrue("2,000${nb}€/kg" in fills)
        assertTrue("<td>Sähkö</td>" in fills)
        assertTrue("<td>Kaasu</td>" in fills)
    }

    @Test fun `period export contains only the period and records it with the language`() = runTest {
        val store = WorkLogTestStore()
        val september = fillup(1, km = 100_000)
        val october = fillup(2, km = 100_500, at = noon + 10 * 86_400)
        store.fillups.addAll(listOf(september, october))
        store.drive(1, "ABC-123", 99_900, 100_000)
        store.drive(2, "ABC-123", 100_400, 100_500, date = LocalDate.of(2026, 10, 5))

        val month = ExportPeriod.Month(YearMonth.of(2026, 10))
        val data = store.repository.exportHtml(noon, month, ReportLocale(ReportLanguage.SV, finnishNumerals = false)).data()

        assertTrue("\"version\":8" in data)
        val from = LocalDate.of(2026, 10, 1).toEpochDay()
        val to = LocalDate.of(2026, 10, 31).toEpochDay()
        assertTrue("\"period\":{\"from\":$from,\"to\":$to}" in data, data)
        assertTrue("\"language\":\"sv\"" in data)
        assertTrue(october.backupId in data)
        assertFalse(september.backupId in data)
        assertFalse("\"date\":${LocalDate.of(2026, 9, 25).toEpochDay()}" in data)
        assertTrue("\"period\":null" in store.repository.exportHtml(noon).data())

        // Jakson tiedosto tuodaan kuten ennenkin: vain jakson kirjaukset.
        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(store.repository.exportHtml(noon, month))
        assertEquals(1, result.fuelAdded)
        assertEquals(listOf(october.backupId), destination.fillups.map { it.backupId })
        assertEquals(1, destination.segments.size)
    }

    @Test fun `report language follows the app`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.add(fillup(1))
        val swedish = store.repository.exportHtml(noon, ExportPeriod.Full, ReportLocale(ReportLanguage.SV, finnishNumerals = true))
        assertTrue("<html lang=\"sv\"" in swedish)
        assertTrue("Fre${nb}25.9.2026" in swedish)
        val english = store.repository.exportHtml(noon, ExportPeriod.Full, ReportLocale(ReportLanguage.EN, finnishNumerals = false))
        assertTrue("<html lang=\"en\"" in english)
        assertTrue("Fri${nb}25${nb}Sep${nb}2026" in english)
    }
}
