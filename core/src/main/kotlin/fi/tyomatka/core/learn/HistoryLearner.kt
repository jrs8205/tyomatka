package fi.tyomatka.core.learn

import fi.tyomatka.core.model.LearnedStats

/**
 * Oppii ajohistoriasta kaksi asiaa eksponentiaalisella liukuvalla keskiarvolla
 * (EWMA):
 *
 * 1. **Nopeuskertoimet** = toteutunut ajoaika / ennustettu ajoaika, erikseen päivän suunnitelman ja
 *    navigoinnin ennusteille. Jos kuljettaja
 *    ajaa systemaattisesti ennustetta hitaammin (esim. jakeluauto, talvikeli),
 *    kerroin nousee yli yhden ja kaikki tulevat ennusteet venyvät vastaavasti.
 *    Kerroin päivittyy jokaisen ajetun legin jälkeen, joten aamupäivän
 *    viivästykset korjaavat iltapäivän ennusteita jo saman päivän aikana.
 *
 * 2. **Stoppikesto** = saapumisen ja lähdön väli. Tarkentaa arvioita siitä,
 *    kauanko yksi jakelupysähdys oikeasti kestää.
 */
class HistoryLearner(
    private val legAlpha: Double = 0.25,
    private val serviceAlpha: Double = 0.2,
) {

    /** Päivitä suunnitelman (OSRM) kerroin yhden ajetun legin toteumalla. */
    fun onLegCompleted(stats: LearnedStats, predictedSec: Long, actualSec: Long): LearnedStats {
        val factor = learnedFactor(stats.speedFactor, stats.legSamples, predictedSec, actualSec) ?: return stats
        return stats.copy(speedFactor = factor, legSamples = stats.legSamples + 1)
    }

    /** Päivitä navigoinnin (Valhalla) kerroin yhden ajetun legin toteumalla. */
    fun onNavLegCompleted(stats: LearnedStats, predictedSec: Long, actualSec: Long): LearnedStats {
        val factor = learnedFactor(stats.navSpeedFactor, stats.navLegSamples, predictedSec, actualSec) ?: return stats
        return stats.copy(navSpeedFactor = factor, navLegSamples = stats.navLegSamples + 1)
    }

    private fun learnedFactor(current: Double, samples: Int, predictedSec: Long, actualSec: Long): Double? {
        if (predictedSec < MIN_MEANINGFUL_LEG_SEC || actualSec < MIN_MEANINGFUL_LEG_SEC) {
            // Parin sekunnin siirtymät (esim. naapuriovi) vain kohisevat suhdetta.
            return null
        }
        val ratio = (actualSec.toDouble() / predictedSec).coerceIn(MIN_RATIO, MAX_RATIO)
        val next = if (samples == 0) ratio else current + legAlpha * (ratio - current)
        return next.coerceIn(MIN_RATIO, MAX_RATIO)
    }

    /** Päivitä tilastot yhden valmistuneen stopin kestolla. */
    fun onStopServiced(stats: LearnedStats, durationSec: Long): LearnedStats {
        val clamped = durationSec.coerceIn(MIN_SERVICE_SEC, MAX_SERVICE_SEC)
        val newAvg = if (stats.serviceSamples == 0) {
            clamped.toDouble()
        } else {
            stats.avgServiceSec + serviceAlpha * (clamped - stats.avgServiceSec)
        }
        return stats.copy(
            avgServiceSec = newAvg.toLong(),
            serviceSamples = stats.serviceSamples + 1,
        )
    }

    private companion object {
        const val MIN_MEANINGFUL_LEG_SEC = 30L
        const val MIN_RATIO = 0.5
        const val MAX_RATIO = 2.5
        const val MIN_SERVICE_SEC = 15L
        const val MAX_SERVICE_SEC = 2 * 3600L
    }
}
