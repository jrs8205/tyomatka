package fi.tyomatka.ui.plan

import kotlin.test.Test
import kotlin.test.assertEquals

class AddStopPagesTest {
    @Test
    fun `takaisin siirtää edelliselle sivulle eikä ensimmäiseltä sivulta mihinkään`() {
        assertEquals(1, previousAddStopPage(2))
        assertEquals(0, previousAddStopPage(1))
        assertEquals(null, previousAddStopPage(0))
    }
}
