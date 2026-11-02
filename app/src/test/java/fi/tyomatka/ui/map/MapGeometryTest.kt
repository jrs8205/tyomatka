package fi.tyomatka.ui.map

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.data.db.StopEntity
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapGeometryTest {

    private fun stop(id: Long, lat: Double, lng: Double) =
        StopEntity(id = id, routeDayId = 1, label = "S$id", address = "", lat = lat, lng = lng, orderIndex = id.toInt())

    @Test
    fun `tyhjä lista ei tuota laatikkoa`() {
        assertNull(MapGeometry.boundingBoxOf(emptyList()))
    }

    @Test
    fun `laatikko kattaa kaikki pisteet länsi-etelä-itä-pohjoinen`() {
        val box = MapGeometry.boundingBoxOf(listOf(GeoPoint(60.1, 24.9), GeoPoint(60.3, 24.5)))!!
        assertEquals(24.5, box.west)
        assertEquals(60.1, box.south)
        assertEquals(24.9, box.east)
        assertEquals(60.3, box.north)
    }

    @Test
    fun `yksi piste saa pienen laatikon ympärilleen`() {
        val box = MapGeometry.boundingBoxOf(listOf(GeoPoint(60.0, 24.0)))!!
        assertTrue(box.north > box.south && box.east > box.west)
    }

    @Test
    fun `stopit numeroidaan ykkösestä ja koordinaatit ovat lon,lat`() {
        val fc = MapGeometry.stopFeatures(listOf(stop(7, 60.1, 24.9), stop(8, 60.2, 24.8)))
        assertEquals(2, fc.features.size)
        assertEquals("1", fc.features[0].properties["n"]!!.jsonPrimitive.content)
        assertEquals("2", fc.features[1].properties["n"]!!.jsonPrimitive.content)
        assertEquals(24.9, fc.features[0].geometry.coordinates.longitude)
        assertEquals(60.1, fc.features[0].geometry.coordinates.latitude)
    }

    @Test
    fun `reittiviiva vaatii vähintään kaksi pistettä`() {
        assertEquals(0, MapGeometry.routeFeature(listOf(GeoPoint(60.0, 24.0))).features.size)
        assertEquals(1, MapGeometry.routeFeature(listOf(GeoPoint(60.0, 24.0), GeoPoint(60.1, 24.1))).features.size)
        assertEquals(0, MapGeometry.originFeature(null).features.size)
    }
}
