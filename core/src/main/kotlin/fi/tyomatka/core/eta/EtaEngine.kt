package fi.tyomatka.core.eta

import fi.tyomatka.core.model.DeadlineStatus
import fi.tyomatka.core.model.LearnedStats
import fi.tyomatka.core.model.PlannedStop
import fi.tyomatka.core.model.ScheduleSnapshot
import fi.tyomatka.core.model.StopEta

/**
 * ETA-laskennan syöte.
 *
 * @param nowEpochSec nykyhetki
 * @param liveEtaToNextSec navigoinnin jäljellä oleva ajoaika seuraavalle stopille todellisesta
 *   sijainnista. Null, jos ei ajossa — silloin ensimmäinenkin legi lasketaan matriisiennusteesta.
 * @param remainingStops jäljellä olevat stopit ajojärjestyksessä
 * @param legPredictedSec matriisin ennustama ajoaika kullekin legille: [i] =
 *   ajoaika stopille i edellisestä pisteestä (indeksi 0 = nykysijainnista).
 *   Sama koko kuin [remainingStops].
 * @param stats historiasta opitut korjauskertoimet
 * @param atStopSinceEpochSec jos kuljettaja on parhaillaan stopilla, aika
 *   jolloin hän saapui sinne (ensimmäisen stopin "saapumisaika" on jo toteutunut)
 */
data class EtaInput(
    val nowEpochSec: Long,
    val liveEtaToNextSec: Long?,
    val remainingStops: List<PlannedStop>,
    val legPredictedSec: List<Long>,
    val stats: LearnedStats = LearnedStats(),
    val atStopSinceEpochSec: Long? = null,
)

/**
 * Laskee jokaiselle jäljellä olevalle stopille ennustetun saapumis- ja
 * lähtöajan sekä koko päivän valmistumisajan.
 *
 * Ketju: live-ETA seuraavalle stopille × navigoinnin kerroin (tai matriisiennuste × nopeuskerroin)
 * + jokaiselle myöhemmälle legille matriisiennuste × opittu nopeuskerroin
 * + jokaiselle stopille arvioitu kesto (stoppikohtainen arvio tai opittu keskiarvo).
 *
 * Navigoinnin aika ei huomioi liikennettä, joten sekin korjataan opitulla kertoimella. Kertoimia on kaksi,
 * koska navigointi ja päivän suunnitelma tulevat eri reitityspalveluista.
 */
class EtaEngine(
    /** Pelivara, jonka alittuessa aikaraja näytetään keltaisena. */
    private val tightSlackSec: Long = 10 * 60L,
) {

    fun compute(input: EtaInput): ScheduleSnapshot {
        require(input.remainingStops.size == input.legPredictedSec.size) {
            "legPredictedSec-lista ei vastaa stoppilistaa"
        }
        val etas = ArrayList<StopEta>(input.remainingStops.size)
        var clock = input.nowEpochSec

        input.remainingStops.forEachIndexed { i, stop ->
            val arrival: Long
            if (i == 0 && input.atStopSinceEpochSec != null) {
                // Ollaan jo perillä ensimmäisellä stopilla.
                arrival = input.atStopSinceEpochSec
                val serviceEnd = arrival + serviceSec(stop, input.stats)
                // Lähtö ei voi olla menneisyydessä: jos stoppi venyy, valmistuminen siirtyy.
                clock = maxOf(serviceEnd, input.nowEpochSec)
            } else {
                val driveSec = if (i == 0 && input.liveEtaToNextSec != null) {
                    (input.liveEtaToNextSec * input.stats.navSpeedFactor).toLong()
                } else {
                    (input.legPredictedSec[i] * input.stats.speedFactor).toLong()
                }
                arrival = clock + driveSec
                clock = arrival + serviceSec(stop, input.stats)
            }
            val slack = stop.deadlineEpochSec?.let { it - arrival }
            etas.add(
                StopEta(
                    stop = stop,
                    arrivalEpochSec = arrival,
                    departureEpochSec = clock,
                    deadlineSlackSec = slack,
                    deadlineStatus = status(slack),
                )
            )
        }

        return ScheduleSnapshot(
            computedAtEpochSec = input.nowEpochSec,
            stopEtas = etas,
            finishEpochSec = if (etas.isEmpty()) input.nowEpochSec else etas.last().departureEpochSec,
        )
    }

    private fun serviceSec(stop: PlannedStop, stats: LearnedStats): Long =
        stop.serviceSec ?: stats.avgServiceSec

    private fun status(slackSec: Long?): DeadlineStatus = when {
        slackSec == null -> DeadlineStatus.NONE
        slackSec < 0 -> DeadlineStatus.LATE
        slackSec < tightSlackSec -> DeadlineStatus.TIGHT
        else -> DeadlineStatus.OK
    }
}
