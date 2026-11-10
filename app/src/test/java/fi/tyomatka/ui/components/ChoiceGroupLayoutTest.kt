package fi.tyomatka.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Yhdistetyn valintaryhmän sarakkeet: kaikki rinnakkain, jos jokainen nimi mahtuu omaan soluunsa, muuten neljä
 * vaihtoehtoa 2 × 2 ja lopulta allekkain. Leveydet ovat pikseleitä; välit ja reunukset annetaan erikseen.
 */
class ChoiceGroupLayoutTest {

    private fun columns(widths: List<Int>, available: Int) =
        choiceGroupColumns(labelWidthsPx = widths, availableWidthPx = available, gapPx = 3, labelPaddingPx = 8)

    @Test
    fun `nelja lyhytta nimea mahtuvat rinnakkain`() {
        assertEquals(4, columns(listOf(40, 50, 60, 40), 400))
    }

    @Test
    fun `nelja joista yksi ei mahdu neljasosaan menee kahteen sarakkeeseen`() {
        assertEquals(2, columns(listOf(40, 50, 120, 40), 400))
    }

    @Test
    fun `kolme joista yksi ei mahdu kolmasosaan asettuu allekkain`() {
        assertEquals(1, columns(listOf(100, 60, 60), 300))
    }

    @Test
    fun `kaksi jotka eivat mahdu asettuvat allekkain`() {
        assertEquals(1, columns(listOf(150, 150), 300))
    }

    @Test
    fun `nelja jotka eivat mahdu edes puolikkaaseen asettuvat allekkain`() {
        assertEquals(1, columns(listOf(200, 40, 40, 40), 400))
    }

    @Test
    fun `tyhja lista on yksi sarake`() {
        assertEquals(1, columns(emptyList(), 400))
    }

    @Test
    fun `nimi joka tayttaa solun tasan mahtuu`() {
        // Solu (400 - 3 * 3) / 4 = 97,75 px; nimi 81 + 2 * 8 = 97 mahtuu, 82 + 16 = 98 ei.
        assertEquals(4, columns(listOf(81, 40, 40, 40), 400))
        assertEquals(2, columns(listOf(82, 40, 40, 40), 400))
    }

    @Test
    fun `valit lasketaan vain solujen valiin`() {
        // (403 - 3 * 3) / 4 = 98,5 px: 82 + 16 = 98 mahtuu vain, jos välejä on kolme eikä neljä.
        assertEquals(4, columns(listOf(82, 40, 40, 40), 403))
    }

    @Test
    fun `tasan solun levyinen nimi mahtuu`() {
        // (397 - 9) / 4 = 97 px ja 81 + 16 = 97.
        assertEquals(4, columns(listOf(81, 40, 40, 40), 397))
    }

    @Test
    fun `ruotsin jaksot 150 prosentin fontilla menevat kahteen sarakkeeseen`() {
        // Dag, Vecka, Månad, År lihavoituna 15 sp × 1,5 noin 360 dp:n puhelimessa (328 dp sisältöä).
        assertEquals(2, columns(listOf(37, 62, 75, 25), 328))
    }

    @Test
    fun `teemavalinta 150 prosentin fontilla asettuu allekkain`() {
        // Puhelimen mukaan, Vaalea, Tumma.
        assertEquals(1, columns(listOf(198, 74, 62), 328))
    }
}
