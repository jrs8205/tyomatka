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

    @Test
    fun `otsikko jaetaan sanoiksi välilyönneistä`() {
        assertEquals(listOf("Klistra", "in", "lista"), tabTitleWords("Klistra in lista"))
        assertEquals(listOf("Hae", "osoite"), tabTitleWords("  Hae   osoite "))
        assertEquals(listOf("Lähellä"), tabTitleWords("Lähellä"))
    }

    @Test
    fun `teksti pysyy koossaan, kun pisin sana mahtuu pikselin varalla`() {
        assertEquals(1f, tabTextScale(listOf(80, 99, 60), availablePx = 100))
        assertEquals(1f, tabTextScale(emptyList(), availablePx = 100))
    }

    @Test
    fun `teksti pienenee niin, että pisin sana mahtuu kokonaisena`() {
        assertEquals(0.99f, tabTextScale(listOf(100), availablePx = 100), 0.0001f)
        assertEquals(0.495f, tabTextScale(listOf(50, 200), availablePx = 100), 0.0001f)
    }
}
