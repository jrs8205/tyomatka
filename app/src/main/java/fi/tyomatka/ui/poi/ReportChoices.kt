package fi.tyomatka.ui.poi

import fi.tyomatka.R
import fi.tyomatka.core.poi.HoursKind
import fi.tyomatka.core.poi.InfoSource
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.ReportRank
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.repo.ReportOutcome
import fi.tyomatka.ui.localDecimal

/** Kentän ilmoituksen tila: odottaa verkkoa, tästä puhelimesta lähetetty tai toisen kuljettajan tieto. */
enum class ReportState { WAITING, SENT, OTHERS }

/** Kentän voimassa oleva ilmoitus: arvo näkyy lomakkeella valittuna ja [state] sen alla sanoin. */
data class FieldReport(val value: String, val epochDay: Long, val state: ReportState)

/**
 * Ilmoituslomakkeen palaute: mikä valinta on voimassa ja menikö se talteen. Tila luetaan kannan ja palvelimen riveistä
 * eikä napautuksesta, joten hylätty ilmoitus ei jää näkymään valittuna ja lomake näyttää saman uudelleen avattaessa.
 */
object ReportChoices {
    /**
     * Uusin ilmoitus kentittäin samassa järjestyksessä kuin kortilla. Kun [info] on annettu, mukana on vain se, minkä kortti
     * näyttää: uudempi muu lähde tai ilmoitus kumoaa valinnan, ja sen saa myös ilmoittaa uudelleen. [chargingNearby] on
     * kortin latauspisteet, jotka voivat kumota ilmoituksen, ettei latausta ole.
     */
    fun latest(reports: List<StationReport>, info: StationInfo? = null, chargingNearby: List<PoiItem> = emptyList()): Map<ReportField, FieldReport> =
        reports.groupBy { it.field }.mapValues { (_, same) ->
            val newest = same.sortedWith(compareBy({ it.atEpochSec }, { it.rank })).last()
            FieldReport(newest.value, newest.epochDay, state(newest, same))
        }.filter { (field, report) -> info == null || shown(field, report, info, chargingNearby) }

    /** Kentät, joita kortti ei näytä, kun palvelu on ilmoitettu lopettaneeksi tai lounasta ei ole. */
    private val DEPENDS_ON = mapOf(
        ReportField.HOURS_SHOP to listOf(ReportField.SHOP),
        ReportField.HOURS_RESTAURANT to listOf(ReportField.RESTAURANT),
        ReportField.HOURS_LUNCH to listOf(ReportField.RESTAURANT, ReportField.LUNCH),
    )

    private fun shown(field: ReportField, report: FieldReport, info: StationInfo, chargingNearby: List<PoiItem>): Boolean = when {
        info.sourceOf(field) != InfoSource.DRIVER -> false
        info.services[field]?.let { it.value != (report.value == "yes") } == true -> false
        DEPENDS_ON[field].orEmpty().any { info.services[it]?.value == false } -> false
        // "Lounasta ei ole" pitää paikkansa lopettaneessakin ravintolassa, joten se näkyy valittuna ja estää toiston.
        field == ReportField.LUNCH && report.value == "yes" && info.services[ReportField.RESTAURANT]?.value == false -> false
        field == ReportField.CHARGING && report.value == "no" && PoiCardText.showAfirCharging(info, chargingNearby) -> false
        else -> true
    }

    private val HOURS_KINDS = listOf(
        ReportField.HOURS_STATION to HoursKind.STATION,
        ReportField.HOURS_RESTAURANT to HoursKind.RESTAURANT,
        ReportField.HOURS_LUNCH to HoursKind.LUNCH,
        ReportField.HOURS_SHOP to HoursKind.SHOP,
    )

    /**
     * Lomakkeen pohja tallennetusta tiedosta. Rajoista mukana ovat vain kuljettajien ilmoitukset, jotta uudelleen avattaessa
     * näkee, mitä lähti. Aukiolo, joka ei mahdu arki/la/su-muotoon, ei ole pohja.
     */
    fun savedForm(info: StationInfo?, latest: Map<ReportField, FieldReport>): ReportForm = ReportForm(
        fuels = info?.fuels?.value?.map { it.type }?.toSet(),
        hours = HOURS_KINDS.mapNotNull { (field, kind) -> info?.hours?.get(kind)?.value?.let(HoursForm::from)?.let { field to it } }.toMap(),
        maxHeight = latest[ReportField.MAX_HEIGHT]?.value?.let(::localDecimal).orEmpty(),
        maxWeight = latest[ReportField.MAX_WEIGHT]?.value?.let(::localDecimal).orEmpty(),
    )

    /** Palvelimen palauttama oma ilmoitus voi voittaa oman rivin, joten omaksi tunnistetaan myös sama sisältö ja tekohetki. */
    private fun state(newest: StationReport, same: List<StationReport>): ReportState = when {
        newest.rank == ReportRank.PENDING -> ReportState.WAITING
        same.any { ReportRank.isOwn(it.rank) && it.value == newest.value && it.atEpochSec == newest.atEpochSec } -> ReportState.SENT
        else -> ReportState.OTHERS
    }

    /** Sama oma valinta uudelleen on vahinko eikä uusi tieto; muiden tiedon voi vahvistaa ja omankin myöhemmin. */
    fun isRepeat(report: FieldReport?, value: String, todayEpochDay: Long): Boolean = report != null && report.value == value && when (report.state) {
        ReportState.WAITING -> true
        ReportState.SENT -> report.epochDay == todayEpochDay
        ReportState.OTHERS -> false
    }

    fun status(report: FieldReport, strings: (Int, Array<out Any>) -> String): String = when (report.state) {
        ReportState.WAITING -> strings(R.string.poi_report_state_waiting, emptyArray())
        ReportState.SENT -> strings(R.string.poi_report_state_sent, arrayOf(PoiCardText.date(report.epochDay)))
        ReportState.OTHERS -> PoiCardText.sourceLine(InfoSource.DRIVER, report.epochDay, strings)
    }

    /** "Kauppa: Auki 24 h · Ilmoitettu 1.11.2026": [label] nimeää kentän ja [value] kertoo arvon, kun valinta ei näytä sitä. */
    fun statusLine(report: FieldReport, label: String?, value: String?, strings: (Int, Array<out Any>) -> String): String {
        val state = status(report, strings)
        val body = if (value == null) state else "$value · $state"
        return if (label == null) body else strings(R.string.poi_report_status_labeled, arrayOf(label, body))
    }

    /** Hetkellinen ilmoitus tuloksesta; hylkäyksellä ei ole sellaista, koska se kerrotaan pysyvästi ([fi.tyomatka.data.repo.ReportRepository.rejectedUnseen]). */
    fun noticeRes(outcome: ReportOutcome): Int? = when (outcome) {
        ReportOutcome.SENT -> R.string.poi_report_sent
        ReportOutcome.QUEUED -> R.string.poi_report_queued
        ReportOutcome.REJECTED -> null
    }
}

/** Valinnan tiedot ilmoituksista: kortin yhdistelmä ja lomakkeen omat rivit päivittyvät aina yhdessä. */
fun PoiSelection.withReports(all: List<StationReport>): PoiSelection =
    copy(info = StationInfo.of(item, all), reports = all.filter { it.poiId == item.id })
