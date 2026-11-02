package fi.tyomatka.diag

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagThrottleTest {
    private var now = 1_000L
    private val throttle = DiagThrottle(minIntervalMs = 10_000) { now }

    @Test
    fun `ensimmainen sallitaan`() {
        assertTrue(throttle.allow())
    }

    @Test
    fun `alle 10 s hylataan`() {
        throttle.allow()
        now += 9_999
        assertFalse(throttle.allow())
    }

    @Test
    fun `10 s jalkeen sallitaan ja laskenta alkaa sallitusta`() {
        throttle.allow()
        now += 10_000
        assertTrue(throttle.allow())
        now += 5_000
        assertFalse(throttle.allow())
        now += 5_000
        assertTrue(throttle.allow())
    }
}
