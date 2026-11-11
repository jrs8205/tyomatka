package fi.tyomatka.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Sovellus avautuu Työmatka-välilehdelle, joka on alapalkissa ensimmäisenä. */
class StartRouteTest {

    @Test
    fun `sovellus avautuu tyomatka-valilehdelle`() {
        assertEquals("history", START_ROUTE)
    }

    @Test
    fun `alapalkki vasemmalta tyomatka suunnittelu kartta ajo`() {
        assertEquals(listOf("history", "plan", "map", "drive"), TAB_ORDER)
    }

    @Test
    fun `aloitusvalilehti on alapalkissa ensimmaisena`() {
        assertEquals(START_ROUTE, TAB_ORDER.first())
    }
}
