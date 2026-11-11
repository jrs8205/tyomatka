package fi.tyomatka.ui.plan.nearby

import kotlin.test.Test
import kotlin.test.assertEquals

/** Haun aikana valitsimet ohitetaan vain, kun ne veisivät tuloksilta yli puolet listan korkeudesta. */
class NearbyScrollTest {

    @Test
    fun `matalat valitsimet jaavat nakyviin osumamaarineen`() {
        assertEquals(0, nearbyQueryScrollTarget(selectorsPx = 400, viewportPx = 1_100))
    }

    @Test
    fun `korkeat valitsimet ohitetaan`() {
        assertEquals(SELECTOR_ITEMS, nearbyQueryScrollTarget(selectorsPx = 700, viewportPx = 1_100))
    }

    @Test
    fun `puolikas raja jaa nakyviin`() {
        assertEquals(0, nearbyQueryScrollTarget(selectorsPx = 550, viewportPx = 1_100))
    }

    @Test
    fun `mittaamaton lista alkaa alusta`() {
        assertEquals(0, nearbyQueryScrollTarget(selectorsPx = 700, viewportPx = 0))
        assertEquals(0, nearbyQueryScrollTarget(selectorsPx = 0, viewportPx = 1_100))
    }
}
