package fi.tyomatka.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** Nappirivi: napit rinnakkain vain, jos jokainen mahtuu tasaosuuteensa kokonaisena; muuten allekkain. */
class ButtonRowTest {

    @Test
    fun `kaksi kapeaa nappia mahtuvat rinnakkain`() {
        assertEquals(2, buttonsPerRow(listOf(140, 150), availableWidthPx = 324, gapPx = 10))
    }

    @Test
    fun `leveampi nappi ei mahdu puolikkaaseen joten napit allekkain`() {
        // Puolikas (324 - 10) / 2 = 157 px; 176 ei mahdu, vaikka summa 176 + 100 + 10 mahtuisi riville.
        assertEquals(1, buttonsPerRow(listOf(176, 100), availableWidthPx = 324, gapPx = 10))
    }

    @Test
    fun `tasan puolikkaan levyinen nappi mahtuu`() {
        assertEquals(2, buttonsPerRow(listOf(157, 157), availableWidthPx = 324, gapPx = 10))
        assertEquals(1, buttonsPerRow(listOf(158, 100), availableWidthPx = 324, gapPx = 10))
    }

    @Test
    fun `yksi nappi on oma rivinsa`() {
        assertEquals(1, buttonsPerRow(listOf(400), availableWidthPx = 324, gapPx = 10))
        assertEquals(1, buttonsPerRow(emptyList(), availableWidthPx = 324, gapPx = 10))
    }
}
