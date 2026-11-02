package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class VoicePolicyTest {

    private fun gate(nowMs: Long = 50_000, offRoute: Boolean = false, rerouting: Boolean = false, routeReplacedMs: Long? = null) =
        VoicePolicy.gate(nowMs, offRoute, rerouting, routeReplacedMs)

    @Test
    fun `reitilta poikenneena ohje hylataan`() {
        assertEquals(SpeechGate.Drop, gate(offRoute = true))
    }

    @Test
    fun `uudelleenreitityksen aikana vanhan reitin ohje hylataan`() {
        assertEquals(SpeechGate.Drop, gate(rerouting = true))
    }

    @Test
    fun `reitin vaihdon jalkeen ohje lykataan hiljaisen jakson loppuun`() {
        // Sekunnin hiljaisuus riittää merkkiäänelle; uuden reitin ohje kuuluu heti sen perään.
        assertEquals(SpeechGate.Defer(500), gate(nowMs = 10_500, routeReplacedMs = 10_000))
        assertEquals(SpeechGate.Defer(1), gate(nowMs = 10_999, routeReplacedMs = 10_000))
        assertEquals(SpeechGate.Allow, gate(nowMs = 11_000, routeReplacedMs = 10_000))
    }

    @Test
    fun `vaihdon jalkeinen lykkays voittaa viela paalla olevan uudelleenreitityksen ja poikkeaman`() {
        // Uuden reitin ensimmäinen ohje laukeaa, kun ydin ei ole vielä nollannut hakutilaa eikä vanhaa poikkeamaa.
        assertEquals(SpeechGate.Defer(1_000), gate(nowMs = 10_000, offRoute = true, rerouting = true, routeReplacedMs = 10_000))
    }

    @Test
    fun `ilman poikkeamaa ja vaihtoa ohje puhutaan`() {
        assertEquals(SpeechGate.Allow, gate())
    }
}
