package fi.tyomatka.ui.poi

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.data.repo.PoiLoad
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoiTruncationTest {
    private fun item(category: PoiCategory, hgv: Boolean) = PoiItem("n1", category, GeoPoint(60.2, 24.9), null, null, null, hgv, emptyMap())

    private fun load(category: PoiCategory, truncated: Boolean, vararg hgv: Boolean) =
        category to PoiLoad(hgv.map { item(category, it) }, truncated = truncated)

    @Test
    fun `katkaistu luokka näyttää vihjeen`() {
        assertTrue(poiWindowTruncated(listOf(load(PoiCategory.CHARGING, true, false, false)), heavyOnly = false))
        assertFalse(poiWindowTruncated(listOf(load(PoiCategory.CHARGING, false, false)), heavyOnly = false))
    }

    @Test
    fun `rekkasuodattimella katkaisu ei haittaa, jos mukana on muitakin kuin rekka-asemia`() {
        // Palvelin järjestää raskaan kaluston ensin: muu kuin rekka-asema mukana tarkoittaa, että kaikki rekka-asemat mahtuivat.
        assertFalse(poiWindowTruncated(listOf(load(PoiCategory.FUEL, true, true, false)), heavyOnly = true))
        assertTrue(poiWindowTruncated(listOf(load(PoiCategory.FUEL, true, true, true)), heavyOnly = true))
        assertTrue(poiWindowTruncated(listOf(load(PoiCategory.FUEL, true, true, false)), heavyOnly = false))
    }

    @Test
    fun `rekkasuodatin ei koske latausta eikä kaasua`() {
        assertTrue(poiWindowTruncated(listOf(load(PoiCategory.CHARGING, true, true, false)), heavyOnly = true))
        assertTrue(poiWindowTruncated(listOf(load(PoiCategory.FUEL, false, true), load(PoiCategory.GAS, true, false)), heavyOnly = true))
    }
}
