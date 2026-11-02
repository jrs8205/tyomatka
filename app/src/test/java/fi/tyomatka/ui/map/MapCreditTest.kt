package fi.tyomatka.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapCreditTest {
    private val twoLines = "© OpenMapTiles\n© OpenStreetMap"
    private val oneLine = "© OpenMapTiles © OpenStreetMap"

    @Test
    fun `kapealla näytöllä merkintä on kahdella rivillä`() {
        assertEquals(twoLines, mapCreditText(320))
        assertEquals(twoLines, mapCreditText(360))
        assertEquals(twoLines, mapCreditText(399))
    }

    @Test
    fun `leveällä näytöllä merkintä on yhdellä rivillä`() {
        assertEquals(oneLine, mapCreditText(400))
        assertEquals(oneLine, mapCreditText(411))
    }
}
