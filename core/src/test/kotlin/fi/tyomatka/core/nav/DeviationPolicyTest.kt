package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviationPolicyTest {

    private fun reroute(
        guidanceActive: Boolean = true,
        offStepOnly: Boolean = false,
        fixAgeMs: Long = 2_000,
        guidanceAgeMs: Long = 30_000,
    ) = DeviationPolicy.shouldReroute(guidanceActive, offStepOnly, fixAgeMs, guidanceAgeMs)

    @Test
    fun `reitilta poikettaessa haetaan uusi reitti`() {
        assertTrue(reroute())
    }

    @Test
    fun `pysaytetty opastus ei hae uutta reittia`() {
        assertFalse(reroute(guidanceActive = false))
    }

    @Test
    fun `askeleesta poikkeaminen reitin sisalla ei hae uutta reittia`() {
        assertFalse(reroute(offStepOnly = true))
    }

    @Test
    fun `yli kymmenen sekuntia vanha sijainti ei hae uutta reittia`() {
        assertFalse(reroute(fixAgeMs = 10_001))
        assertTrue(reroute(fixAgeMs = 10_000))
    }

    @Test
    fun `tulevaisuuteen leimattu sijainti ei hae uutta reittia`() {
        assertFalse(reroute(fixAgeMs = -1_001))
        assertTrue(reroute(fixAgeMs = -1_000))
    }

    @Test
    fun `juuri alkanut opastus ei hae uutta reittia vanhasta aloitussijainnista`() {
        assertFalse(reroute(guidanceAgeMs = 1_999))
        assertTrue(reroute(guidanceAgeMs = 2_000))
    }
}
