package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

/** Kartalla valittu lyhin reitti on voimassa valintapäivän; muuten ajetaan nopeinta. */
class RouteChoiceTest {

    @Test
    fun `lyhin on voimassa valintapaivana`() {
        assertEquals(RouteChoice.SHORTEST, routeChoiceForDay(RouteChoice.SHORTEST, chosenOnDay = 20_403, today = 20_403))
    }

    @Test
    fun `lyhin palaa nopeimmaksi seuraavana paivana`() {
        assertEquals(RouteChoice.NORMAL, routeChoiceForDay(RouteChoice.SHORTEST, chosenOnDay = 20_403, today = 20_404))
    }

    @Test
    fun `ilman valintapaivaa tallennettu lyhin on nopein`() {
        assertEquals(RouteChoice.NORMAL, routeChoiceForDay(RouteChoice.SHORTEST, chosenOnDay = null, today = 20_403))
    }

    @Test
    fun `nopein pysyy nopeimpana`() {
        assertEquals(RouteChoice.NORMAL, routeChoiceForDay(RouteChoice.NORMAL, chosenOnDay = 20_403, today = 20_403))
        assertEquals(RouteChoice.NORMAL, routeChoiceForDay(RouteChoice.NORMAL, chosenOnDay = null, today = 20_403))
    }
}
