package fi.tyomatka.core.location

import java.util.EnumMap

/** Sijaintilähteet etusijajärjestyksessä: `fused` ja `gps` ovat jatkuvia virtoja, `network` vain varalla. */
enum class LocationSource { FUSED, GPS, NETWORK }

data class LocationSample(val source: LocationSource, val elapsedNanos: Long, val accuracyM: Float?)

/** Yli 25 m:n näyte ei kelpaa pysähdyssaapumiseen, joten se ei saa syrjäyttää tarkempaa lähdettä. */
internal const val GOOD_ACCURACY_M = 25f

internal fun LocationSample.isGood(): Boolean = (accuracyM ?: Float.MAX_VALUE) <= GOOD_ACCURACY_M

/**
 * Valitsee, minkä lähteen näyte ohjaa navigointia ja kartan pistettä, jotta pisteet eivät hypi lähteiden välillä.
 *
 * - Aktiivinen lähde pitää paikkansa, kunnes se vaikenee yli 5 s, on epätarkka ja toinen virta tarkka, tai on yli 100 m
 *   epätarkka ja toinen virta sitä parempi.
 * - `fused` palaa `gps`:n tilalle vasta kolmesta peräkkäisestä tarkasta näytteestä enintään 2,5 s välein. Harvoin näytteitä
 *   antava tai rajalla vuorotteleva lähde ei siis keskeytä elävää virtaa.
 * - `network` ei syrjäytä elävää virtaa, koska sen näytteillä ei yleensä ole nopeutta ja ne voivat olla mastopaikannuksia.
 * - Yli 100 m:n näytettä ei julkaista minuuttiin tarkan näytteen jälkeen, joten kartan piste ei hyppää tunnelissa.
 * - Julkaisu ei koskaan mene ajassa taaksepäin. Enintään 1 s jäljessä oleva lähde voi silti ottaa ohjauksen seuraavasta
 *   näytteestään.
 */
class FixSelector {
    private val latest = EnumMap<LocationSource, LocationSample>(LocationSource::class.java)
    private val goodStreak = EnumMap<LocationSource, Int>(LocationSource::class.java)
    private var lastPublishedNanos: Long? = null
    private var lastGoodNanos: Long? = null

    var active: LocationSource? = null
        private set

    /** Palauttaa, julkaistaanko näyte. */
    fun accept(sample: LocationSample): Boolean {
        val previous = latest[sample.source]
        if (previous != null && sample.elapsedNanos <= previous.elapsedNanos) return false
        goodStreak[sample.source] = when {
            !sample.isGood() -> 0
            previous != null && sample.elapsedNanos - previous.elapsedNanos <= STREAK_GAP_NANOS -> (goodStreak[sample.source] ?: 0) + 1
            else -> 1
        }
        latest[sample.source] = sample

        if (!leads(sample) || isHeld(sample)) return false
        val published = lastPublishedNanos
        if (published != null && sample.elapsedNanos <= published) {
            // Leima on hieman jäljessä: lähde vaihtuu, mutta tätä näytettä ei julkaista, koska piste kulkisi taaksepäin.
            if (published - sample.elapsedNanos <= SWITCH_LAG_NANOS) active = sample.source
            return false
        }

        lastPublishedNanos = sample.elapsedNanos
        if (sample.isGood()) lastGoodNanos = sample.elapsedNanos
        active = sample.source
        return true
    }

    private fun leads(sample: LocationSample): Boolean {
        val current = active?.let { latest[it] } ?: return true
        if (sample.source == current.source) return true
        if (sample.source == LocationSource.NETWORK) return streamsSilent(sample.elapsedNanos)
        val age = sample.elapsedNanos - current.elapsedNanos
        if (current.source == LocationSource.NETWORK) {
            return sample.isGood() || sample.accuracy() <= current.accuracy() || age > NETWORK_STALE_NANOS
        }
        if (age > SILENT_NANOS) return true
        // Erittäin epätarkka virta ei saa jäädyttää pistettä, kun toinen virta kelpaa.
        if (current.accuracy() > HOLD_ACCURACY_M && sample.accuracy() <= HOLD_ACCURACY_M) return true
        return if (sample.source < current.source) {
            (goodStreak[sample.source] ?: 0) >= RETURN_STREAK
        } else {
            !current.isGood() && sample.isGood()
        }
    }

    private fun streamsSilent(nowNanos: Long): Boolean =
        listOf(LocationSource.FUSED, LocationSource.GPS).all { source ->
            latest[source]?.let { nowNanos - it.elapsedNanos > SILENT_NANOS } ?: true
        }

    private fun isHeld(sample: LocationSample): Boolean {
        val lastGood = lastGoodNanos ?: return false
        return sample.accuracy() > HOLD_ACCURACY_M && sample.elapsedNanos - lastGood < HOLD_NANOS
    }

    private fun LocationSample.accuracy(): Float = accuracyM ?: Float.MAX_VALUE

    private companion object {
        const val SILENT_NANOS = 5_000_000_000L
        const val NETWORK_STALE_NANOS = 30_000_000_000L
        const val SWITCH_LAG_NANOS = 1_000_000_000L
        const val STREAK_GAP_NANOS = 2_500_000_000L
        const val RETURN_STREAK = 3
        const val HOLD_ACCURACY_M = 100f
        const val HOLD_NANOS = 60_000_000_000L
    }
}
