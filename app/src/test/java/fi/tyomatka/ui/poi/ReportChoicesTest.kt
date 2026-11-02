package fi.tyomatka.ui.poi

import fi.tyomatka.R
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.FuelType
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.ReportRank
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.repo.ReportOutcome
import fi.tyomatka.ui.localDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportChoicesTest {
    private val day = LocalDate.of(2026, 11, 1).toEpochDay()
    private val noon = day * 86_400 + 43_200

    private fun report(value: String, at: Long = noon, rank: Long = ReportRank.server(at + 60), field: ReportField = ReportField.CLOSED) =
        StationReport("n1", field, value, day, atEpochSec = at, rank = rank)

    private val strings: (Int, Array<out Any>) -> String = { id, args ->
        when (id) {
            R.string.poi_report_state_waiting -> "jonossa"
            R.string.poi_report_status_labeled -> "${args[0]}: ${args[1]}"
            R.string.poi_report_state_sent -> "lähetetty ${args[0]}"
            R.string.poi_src_driver -> "kuljettaja ${args[0]}"
            else -> error("tuntematon $id")
        }
    }

    @Test
    fun `lähettämätön oma ilmoitus näkyy valittuna ja odottaa verkkoa`() {
        val latest = ReportChoices.latest(listOf(report("no", rank = ReportRank.PENDING)))

        assertEquals(FieldReport("no", day, ReportState.WAITING), latest[ReportField.CLOSED])
    }

    @Test
    fun `kuitattu oma ilmoitus on lähetetty`() {
        val latest = ReportChoices.latest(listOf(report("no", rank = ReportRank.sent(noon + 1))))

        assertEquals(ReportState.SENT, latest.getValue(ReportField.CLOSED).state)
    }

    @Test
    fun `toisen kuljettajan ilmoitus on muiden tieto`() {
        val latest = ReportChoices.latest(listOf(report("yes")))

        assertEquals(FieldReport("yes", day, ReportState.OTHERS), latest[ReportField.CLOSED])
    }

    @Test
    fun `palvelimen palauttama oma ilmoitus pysyy lähetettynä`() {
        val own = report("no", rank = ReportRank.sent(noon + 1))
        val fromServer = report("no", rank = ReportRank.server(noon + 100))

        assertEquals(ReportState.SENT, ReportChoices.latest(listOf(fromServer, own)).getValue(ReportField.CLOSED).state)
        assertEquals(ReportState.SENT, ReportChoices.latest(listOf(own, fromServer)).getValue(ReportField.CLOSED).state)
    }

    @Test
    fun `uudempi muiden ilmoitus korvaa oman valinnan`() {
        val own = report("no", rank = ReportRank.sent(noon + 1))
        val newer = report("yes", at = noon + 500)

        assertEquals(FieldReport("yes", day, ReportState.OTHERS), ReportChoices.latest(listOf(own, newer))[ReportField.CLOSED])
    }

    @Test
    fun `sama sisältö eri hetkellä ei tee muiden ilmoituksesta omaa`() {
        val own = report("no", rank = ReportRank.sent(noon + 1))
        val newer = report("no", at = noon + 500)

        assertEquals(ReportState.OTHERS, ReportChoices.latest(listOf(own, newer)).getValue(ReportField.CLOSED).state)
    }

    @Test
    fun `saman sekunnin kahdesta omasta listan viimeinen ratkaisee kuten kortilla`() {
        val first = report("yes", rank = ReportRank.PENDING)
        val second = report("no", rank = ReportRank.PENDING)
        val list = listOf(first, second)

        assertEquals("no", ReportChoices.latest(list).getValue(ReportField.CLOSED).value)
        assertEquals(false, StationInfo.of(neste, list).closed?.value)
    }

    private val neste = PoiItem("n1", PoiCategory.FUEL, GeoPoint(60.3, 24.8), "Neste", "Neste", null, false, emptyMap())

    /** Sama valinta kuin kortilla kummassakin listajärjestyksessä. */
    private fun assertSameAsCard(expected: String, vararg rows: StationReport) {
        listOf(rows.toList(), rows.toList().reversed()).forEach { list ->
            assertEquals(expected, ReportChoices.latest(list).getValue(ReportField.CLOSED).value)
            assertEquals(expected == "yes", StationInfo.of(neste, list).closed?.value)
        }
    }

    @Test
    fun `saman sekunnin tasapeli ratkeaa kuten kortilla`() {
        assertSameAsCard("yes", report("no", rank = ReportRank.sent(noon + 1)), report("yes", rank = ReportRank.server(noon + 100)))
        assertSameAsCard("no", report("no", rank = ReportRank.sent(noon + 100)), report("yes", rank = ReportRank.server(noon + 1)))
        assertSameAsCard("no", report("no", rank = ReportRank.PENDING), report("yes", rank = ReportRank.server(noon + 100)))
    }

    @Test
    fun `uudempi muu lähde vie valinnan pois`() {
        val item = neste.copy(tags = mapOf("k:status" to "closed", "k:date" to "2026-10-22"))
        val old = StationReport("n1", ReportField.CLOSED, "no", LocalDate.of(2026, 10, 15).toEpochDay())

        assertNull(ReportChoices.latest(listOf(old), StationInfo.of(item, listOf(old)))[ReportField.CLOSED])
    }

    @Test
    fun `uudempi kuljettajan tieto pysyy valittuna vanhemman muun lähteen rinnalla`() {
        val item = neste.copy(tags = mapOf("k:status" to "closed", "k:date" to "2026-10-22"))
        val rows = listOf(report("no"))

        assertEquals("no", ReportChoices.latest(rows, StationInfo.of(item, rows)).getValue(ReportField.CLOSED).value)
    }

    @Test
    fun `uudempi lounasaika vie valinnan lounasta ei ole eikä estä korjausta`() {
        val no = report("no", field = ReportField.LUNCH)
        val hours = report("24/7", at = noon + 500, field = ReportField.HOURS_LUNCH)

        val latest = ReportChoices.latest(listOf(no, hours), StationInfo.of(neste, listOf(no, hours)))

        assertNull(latest[ReportField.LUNCH])
        assertFalse(ReportChoices.isRepeat(latest[ReportField.LUNCH], "no", day))
        assertEquals("24/7", latest.getValue(ReportField.HOURS_LUNCH).value)
    }

    @Test
    fun `lounasta ei ole lounasajan jälkeen pysyy valittuna`() {
        val hours = report("24/7", field = ReportField.HOURS_LUNCH)
        val no = report("no", at = noon + 500, field = ReportField.LUNCH)

        val latest = ReportChoices.latest(listOf(no, hours), StationInfo.of(neste, listOf(no, hours)))

        assertEquals("no", latest.getValue(ReportField.LUNCH).value)
    }

    @Test
    fun `uudempi aukiolo vie valinnan lopettanut`() {
        val closed = report("no", field = ReportField.SHOP)
        val hours = report("24/7", at = noon + 500, field = ReportField.HOURS_SHOP)

        assertNull(ReportChoices.latest(listOf(closed, hours), StationInfo.of(neste, listOf(closed, hours)))[ReportField.SHOP])
    }

    private fun choicesFor(vararg rows: StationReport, item: PoiItem = neste, nearby: List<PoiItem> = emptyList()) =
        ReportChoices.latest(rows.toList(), StationInfo.of(item, rows.toList()), nearby)

    @Test
    fun `lopettanut kauppa vie aiemman aukiolon pois lomakkeelta`() {
        val hours = report("24/7", field = ReportField.HOURS_SHOP)
        val ended = report("no", at = noon + 500, field = ReportField.SHOP)

        val latest = choicesFor(hours, ended)

        assertNull(latest[ReportField.HOURS_SHOP])
        assertEquals("no", latest.getValue(ReportField.SHOP).value)
        assertFalse(ReportChoices.isRepeat(latest[ReportField.HOURS_SHOP], "24/7", day), "saman aukiolon voi ilmoittaa uudelleen")
    }

    @Test
    fun `uudempi aukiolo palauttaa kaupan ja näkyy lomakkeella`() {
        val ended = report("no", field = ReportField.SHOP)
        val hours = report("24/7", at = noon + 500, field = ReportField.HOURS_SHOP)

        assertEquals("24/7", choicesFor(ended, hours).getValue(ReportField.HOURS_SHOP).value)
    }

    @Test
    fun `lopettanut ravintola vie ruoka-aiheiset valinnat pois lomakkeelta`() {
        val lunch = report("yes", field = ReportField.LUNCH)
        val lunchHours = report("24/7", field = ReportField.HOURS_LUNCH)
        val restaurantHours = report("24/7", field = ReportField.HOURS_RESTAURANT)
        val ended = report("no", at = noon + 500, field = ReportField.RESTAURANT)

        val latest = choicesFor(lunch, lunchHours, restaurantHours, ended)

        assertEquals(setOf(ReportField.RESTAURANT), latest.keys)
    }

    @Test
    fun `lounasta ei ole vie lounasajan pois lomakkeelta`() {
        val hours = report("24/7", field = ReportField.HOURS_LUNCH)
        val no = report("no", at = noon + 500, field = ReportField.LUNCH)

        assertNull(choicesFor(hours, no)[ReportField.HOURS_LUNCH])
    }

    @Test
    fun `lounasta ei ole näkyy valittuna, vaikka ravintola on lopettanut`() {
        val ended = report("no", field = ReportField.RESTAURANT)
        val no = report("no", at = noon + 500, rank = ReportRank.sent(noon + 501), field = ReportField.LUNCH)

        val after = choicesFor(ended, no)
        assertEquals("no", after.getValue(ReportField.LUNCH).value)
        assertTrue(ReportChoices.isRepeat(after[ReportField.LUNCH], "no", day), "toinen napautus ei lähetä uutta julkista riviä")

        val earlier = report("no", at = noon - 500, field = ReportField.LUNCH)
        assertEquals("no", choicesFor(ended, earlier).getValue(ReportField.LUNCH).value)
    }

    private fun charger(date: String) =
        PoiItem("c-1", PoiCategory.CHARGING, GeoPoint(60.3, 24.8), "Lataus", null, null, false, mapOf("afir:date" to date))

    @Test
    fun `lataus ei ole väistyy, kun kortti näyttää uudemmat latauspisteet`() {
        val no = report("no", field = ReportField.CHARGING)

        assertNull(choicesFor(no, nearby = listOf(charger("2026-11-02")))[ReportField.CHARGING])
        assertEquals("no", choicesFor(no, nearby = listOf(charger("2026-10-30"))).getValue(ReportField.CHARGING).value)
        assertEquals("no", choicesFor(no).getValue(ReportField.CHARGING).value)
    }

    @Test
    fun `lataus on ei väisty latauspisteiden takia`() {
        val yes = report("yes", field = ReportField.CHARGING)

        assertEquals("yes", choicesFor(yes, nearby = listOf(charger("2026-11-02"))).getValue(ReportField.CHARGING).value)
    }

    @Test
    fun `lomakkeen tallennettu tieto tulee kortilta ja voimassa olevista rajoista`() {
        val rows = listOf(
            report("diesel;hvo", field = ReportField.FUELS),
            report("24/7", field = ReportField.HOURS_SHOP),
            report("3.8", field = ReportField.MAX_HEIGHT),
        )
        val info = StationInfo.of(neste, rows)

        val saved = ReportChoices.savedForm(info, ReportChoices.latest(rows, info))

        assertEquals(setOf(FuelType.DIESEL, FuelType.HVO), saved.fuels)
        assertEquals(HoursForm.from("24/7"), saved.hours[ReportField.HOURS_SHOP])
        assertEquals(localDecimal("3.8"), saved.maxHeight)
        assertEquals("", saved.maxWeight)
        assertEquals(ReportForm(), ReportChoices.savedForm(null, emptyMap()))
    }

    @Test
    fun `aukiolo, joka ei mahdu lomakkeelle, ei ole lomakkeen pohja`() {
        val rows = listOf(report("Mo 06:00-18:00; Tu 07:00-18:00", field = ReportField.HOURS_STATION))
        val info = StationInfo.of(neste, rows)

        assertNull(ReportChoices.savedForm(info, emptyMap()).hours[ReportField.HOURS_STATION])
    }

    @Test
    fun `tilarivi kertoo kentän, arvon ja tilan`() {
        val sent = FieldReport("3.8", day, ReportState.SENT)
        val date = PoiCardText.date(day)

        assertEquals("lähetetty $date", ReportChoices.statusLine(sent, null, null, strings))
        assertEquals("Korkeus: lähetetty $date", ReportChoices.statusLine(sent, "Korkeus", null, strings))
        assertEquals("Korkeus: 3,8 · lähetetty $date", ReportChoices.statusLine(sent, "Korkeus", "3,8", strings))
        assertEquals("3,8 · lähetetty $date", ReportChoices.statusLine(sent, null, "3,8", strings))
    }

    @Test
    fun `kentät pysyvät erillään`() {
        val latest = ReportChoices.latest(listOf(report("no"), report("pump", field = ReportField.ADBLUE)))

        assertEquals("no", latest.getValue(ReportField.CLOSED).value)
        assertEquals("pump", latest.getValue(ReportField.ADBLUE).value)
        assertNull(latest[ReportField.SHOP])
    }

    @Test
    fun `saman oman valinnan toisto samana päivänä ei lähde uudelleen`() {
        val sent = FieldReport("no", day, ReportState.SENT)

        assertTrue(ReportChoices.isRepeat(sent, "no", day))
        assertFalse(ReportChoices.isRepeat(sent, "yes", day))
        assertFalse(ReportChoices.isRepeat(sent, "no", day + 1), "myöhemmin sama tieto on uusi vahvistus")
    }

    @Test
    fun `jonossa olevan valinnan toisto ei lähde uudelleen vaikka päivä vaihtuu`() {
        assertTrue(ReportChoices.isRepeat(FieldReport("no", day, ReportState.WAITING), "no", day + 1))
    }

    @Test
    fun `muiden ilmoituksen voi vahvistaa`() {
        assertFalse(ReportChoices.isRepeat(FieldReport("no", day, ReportState.OTHERS), "no", day))
        assertFalse(ReportChoices.isRepeat(null, "no", day))
    }

    @Test
    fun `tila kerrotaan selvästi`() {
        val date = PoiCardText.date(day)

        assertEquals("jonossa", ReportChoices.status(FieldReport("no", day, ReportState.WAITING), strings))
        assertEquals("lähetetty $date", ReportChoices.status(FieldReport("no", day, ReportState.SENT), strings))
        assertEquals("kuljettaja $date", ReportChoices.status(FieldReport("no", day, ReportState.OTHERS), strings))
    }

    @Test
    fun `lähetyksen tulos kertoo menikö tieto talteen`() {
        assertEquals(R.string.poi_report_sent, ReportChoices.noticeRes(ReportOutcome.SENT))
        assertEquals(R.string.poi_report_queued, ReportChoices.noticeRes(ReportOutcome.QUEUED))
        assertNull(ReportChoices.noticeRes(ReportOutcome.REJECTED), "hylkäys kerrotaan pysyvästi eikä hetkellisesti")
    }

    @Test
    fun `valinta saa vain oman kohteen ilmoitukset`() {
        val item = PoiItem("n1", PoiCategory.FUEL, GeoPoint(60.3, 24.8), "Neste", "Neste", null, false, emptyMap())
        val other = StationReport("n2", ReportField.CLOSED, "yes", day)

        val selection = PoiSelection(item).withReports(listOf(report("no"), other))

        assertEquals(listOf("n1"), selection.reports.map { it.poiId })
        assertEquals(false, selection.info?.closed?.value)
    }
}
