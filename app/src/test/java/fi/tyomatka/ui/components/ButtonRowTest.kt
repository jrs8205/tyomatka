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
    fun `tayttava nappi mahtuu rinnakkain kun leveyksien summa mahtuu`() {
        // Tasaosuus (324 - 10) / 2 = 157 px ei riittäisi 176:lle, mutta 176 + 100 + 10 = 286 mahtuu.
        assertEquals(2, buttonsPerRow(listOf(176, 100), availableWidthPx = 324, gapPx = 10, equalWidths = false))
    }

    @Test
    fun `tayttava nappi raja on summa ja vali`() {
        assertEquals(2, buttonsPerRow(listOf(214, 100), availableWidthPx = 324, gapPx = 10, equalWidths = false))
        assertEquals(1, buttonsPerRow(listOf(215, 100), availableWidthPx = 324, gapPx = 10, equalWidths = false))
        assertEquals(1, buttonsPerRow(listOf(100, 100, 120), availableWidthPx = 324, gapPx = 10, equalWidths = false))
    }

    @Test
    fun `yksi nappi on oma rivinsa`() {
        assertEquals(1, buttonsPerRow(listOf(400), availableWidthPx = 324, gapPx = 10))
        assertEquals(1, buttonsPerRow(emptyList(), availableWidthPx = 324, gapPx = 10))
    }
}
