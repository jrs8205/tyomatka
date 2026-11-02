package fi.tyomatka.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReorderMathTest {

    /** Rivit 2..5 näkyvissä, ylin osittain (offset -57), rivikorkeus 190 px. */
    private val visible = listOf(
        VisibleRow(index = 2, offset = -57, size = 190),
        VisibleRow(index = 3, offset = 133, size = 190),
        VisibleRow(index = 4, offset = 323, size = 190),
        VisibleRow(index = 5, offset = 513, size = 190),
    )

    @Test
    fun `sormen alla oleva rivi on siirron kohde`() {
        assertEquals(2, reorderTarget(visible, from = 3, pointerY = 118f, pinnedCount = 0, lastIndex = 13))
        assertEquals(4, reorderTarget(visible, from = 3, pointerY = 400f, pinnedCount = 0, lastIndex = 13))
    }

    @Test
    fun `oma rivi tai rivien ulkopuoli ei tuota siirtoa`() {
        assertNull(reorderTarget(visible, from = 3, pointerY = 200f, pinnedCount = 0, lastIndex = 13))
        assertNull(reorderTarget(visible, from = 3, pointerY = 900f, pinnedCount = 0, lastIndex = 13))
    }

    @Test
    fun `saapunut stoppi pysyy karjessa`() {
        // Kohde 2 rajataan pinnattujen alapuolelle; kun se osuu omaan riviin, siirtoa ei tule.
        assertNull(reorderTarget(visible, from = 3, pointerY = 118f, pinnedCount = 3, lastIndex = 13))
        assertEquals(3, reorderTarget(visible, from = 4, pointerY = 118f, pinnedCount = 3, lastIndex = 13))
    }

    @Test
    fun `vaihto ylimman nakyvan rivin kanssa vaatii vierityskohdan kiinnityksen`() {
        // Raahattu rivi 3 vaihtaa ylimmän näkyvän rivin 2 kanssa — kumpaankin suuntaan.
        assertTrue(swapMovesFirstVisible(firstVisibleIndex = 2, from = 3, to = 2))
        assertTrue(swapMovesFirstVisible(firstVisibleIndex = 2, from = 2, to = 3))
        // Nopea sormi: siirto 5 → 2 työntää välissä olevan ylimmän rivin 4 alemmas.
        assertTrue(swapMovesFirstVisible(firstVisibleIndex = 4, from = 5, to = 2))
    }

    @Test
    fun `vaihto ylimman rivin alapuolella ei koske vierityskohtaa`() {
        assertFalse(swapMovesFirstVisible(firstVisibleIndex = 2, from = 5, to = 3))
        assertFalse(swapMovesFirstVisible(firstVisibleIndex = 0, from = 10, to = 9))
    }
}
