package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressThrottleTest {

    @Test
    fun `ensimmainen paivitys lahtee heti`() {
        assertTrue(ProgressThrottle().shouldEmit(0, 5_000.0))
    }

    @Test
    fun `pieni muutos ei paivita`() {
        val t = ProgressThrottle()
        t.shouldEmit(0, 5_000.0)

        assertFalse(t.shouldEmit(10_000, 4_950.0))
    }

    @Test
    fun `kolmekymmenta sekuntia paivittaa`() {
        val t = ProgressThrottle()
        t.shouldEmit(0, 5_000.0)

        assertTrue(t.shouldEmit(30_000, 4_990.0))
    }

    @Test
    fun `sata metria paivittaa kumpaan suuntaan tahansa`() {
        val t = ProgressThrottle()
        t.shouldEmit(0, 5_000.0)
        assertTrue(t.shouldEmit(5_000, 4_900.0))

        assertTrue(t.shouldEmit(6_000, 5_000.0), "uusi reitti voi olla pidempi")
    }

    @Test
    fun `alle 500 metrissa kaksikymmenta metria paivittaa`() {
        val t = ProgressThrottle()
        t.shouldEmit(0, 450.0)

        assertFalse(t.shouldEmit(1_000, 431.0))
        assertTrue(t.shouldEmit(2_000, 430.0))
    }

    @Test
    fun `nollaus paivittaa heti`() {
        val t = ProgressThrottle()
        t.shouldEmit(0, 5_000.0)

        t.reset()

        assertTrue(t.shouldEmit(1_000, 5_000.0))
    }

    @Test
    fun `epakelpo matka ei paivita eika sotke seuraavaa vertailua`() {
        val t = ProgressThrottle()
        t.shouldEmit(0, 5_000.0)

        assertFalse(t.shouldEmit(1_000, Double.NaN))
        assertFalse(t.shouldEmit(2_000, Double.POSITIVE_INFINITY))
        assertFalse(t.shouldEmit(3_000, 4_950.0), "50 m ei vielä päivitä")
        assertTrue(t.shouldEmit(4_000, 4_900.0))
    }
}
