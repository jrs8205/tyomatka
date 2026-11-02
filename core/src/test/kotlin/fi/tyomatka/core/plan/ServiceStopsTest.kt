package fi.tyomatka.core.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceStopsTest {
    @Test
    fun `karttakohteen tunnus on huoltostoppi`() {
        assertTrue(ServiceStops.isServiceStop("service:osm:n123"))
        assertTrue(ServiceStops.isServiceStop("service:osm:w456"))
    }

    @Test
    fun `osoitehaun tunnus tai puuttuva tunnus ei ole huoltostoppi`() {
        // Photonin osoitteet saavat tunnuksen osm:W:123: ne ovat asiakasstoppeja.
        assertFalse(ServiceStops.isServiceStop("osm:W:123"))
        assertFalse(ServiceStops.isServiceStop("osm:n123"))
        assertFalse(ServiceStops.isServiceStop("ChIJabc"))
        assertFalse(ServiceStops.isServiceStop(null))
        assertFalse(ServiceStops.isServiceStop(""))
        assertFalse(ServiceStops.isServiceStop("OSM:n1"))
    }

    @Test
    fun `karttakohteen tunnus ja takaisin`() {
        assertEquals("service:osm:n123", ServiceStops.placeId("n123"))
        assertEquals("n123", ServiceStops.poiId("service:osm:n123"))
        assertEquals("w456", ServiceStops.poiId(ServiceStops.placeId("w456")))
        assertNull(ServiceStops.poiId("osm:W:1"))
        assertNull(ServiceStops.poiId("service:other:1"))
        assertNull(ServiceStops.poiId(null))
    }
}
