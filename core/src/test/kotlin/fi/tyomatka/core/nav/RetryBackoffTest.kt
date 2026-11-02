package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class RetryBackoffTest {

    private val backoff = RetryBackoff()

    @Test
    fun `vali kaksinkertaistuu minuuttiin asti`() {
        assertEquals(listOf(5L, 10L, 20L, 40L, 60L), (0..4).map(backoff::delaySec))
    }

    @Test
    fun `minuutin jalkeen yritetaan minuutin valein`() {
        assertEquals(60L, backoff.delaySec(9))
    }

    @Test
    fun `negatiivinen yritys alkaa alusta`() {
        assertEquals(5L, backoff.delaySec(-1))
    }
}
