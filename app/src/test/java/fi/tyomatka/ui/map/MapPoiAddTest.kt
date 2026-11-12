package fi.tyomatka.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapPoiAddTest {
    @Test
    fun `ilman ajoa kohde lisätään suoraan`() {
        assertEquals(MapPoiAddStep.ADD, mapPoiAddStep(driving = false))
    }

    @Test
    fun `ajon aikana lisäys kysytään ensin`() {
        assertEquals(MapPoiAddStep.ASK, mapPoiAddStep(driving = true))
    }
}
