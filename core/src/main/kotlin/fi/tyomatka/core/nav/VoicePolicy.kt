package fi.tyomatka.core.nav

/** Puheohjeen kohtalo: puhutaan heti, lykätään [Defer.waitMs] millisekuntia ja arvioidaan uudelleen, tai hylätään. */
sealed interface SpeechGate {
    data object Allow : SpeechGate
    data object Drop : SpeechGate
    data class Defer(val waitMs: Long) : SpeechGate
}

/**
 * Milloin puheohje saa kuulua. Reitiltä kokonaan poikenneena ja uudelleenreitityksen aikana vanhan reitin ohjeet
 * olisivat vääriä, joten ne hylätään. Heti reitin vaihdon jälkeen ydin laukaisee uuden reitin ensimmäisen ohjeen, kun
 * sen hakutila ja vanha poikkeama ovat vielä voimassa: ohje lykätään [QUIET_AFTER_REPLACE_MS]:n päähän ja arvioidaan
 * silloin uudelleen, jotta se ei huku. Jakso on merkkiäänen mittainen, jotta uuden reitin ohje kuuluu heti sen perään
 *. Ajat ovat laitteen käynnistyskellosta.
 */
object VoicePolicy {
    const val QUIET_AFTER_REPLACE_MS = 1_000L

    fun gate(nowMs: Long, offRoute: Boolean, rerouting: Boolean, routeReplacedMs: Long?): SpeechGate {
        val sinceReplace = routeReplacedMs?.let { nowMs - it }
        return when {
            sinceReplace != null && sinceReplace < QUIET_AFTER_REPLACE_MS -> SpeechGate.Defer(QUIET_AFTER_REPLACE_MS - sinceReplace)
            offRoute || rerouting -> SpeechGate.Drop
            else -> SpeechGate.Allow
        }
    }
}
