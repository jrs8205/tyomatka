package fi.tyomatka.ui.map

import org.maplibre.spatialk.units.Bearing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapFollowTest {

    @Test
    fun `speed converts meters per second to whole km per hour`() {
        assertEquals(36, MapFollow.speedKmh(10.0, accuracyM = 10.0))
        assertEquals(83, MapFollow.speedKmh(23.0, accuracyM = 10.0))
    }

    @Test
    fun `speed is null without a fix and never negative`() {
        assertNull(MapFollow.speedKmh(null, accuracyM = 10.0))
        assertEquals(0, MapFollow.speedKmh(-0.4, accuracyM = 10.0))
    }

    @Test
    fun `speed is hidden when the fix is too inaccurate for a real speed`() {
        assertEquals(36, MapFollow.speedKmh(10.0, accuracyM = 20.0))
        assertNull(MapFollow.speedKmh(10.0, accuracyM = 120.0))
        assertNull(MapFollow.speedKmh(10.0, accuracyM = null))
    }

    @Test
    fun `speed is shown always while following`() {
        assertTrue(MapFollow.showSpeed(CameraMode.FOLLOW, 0))
    }

    @Test
    fun `speed is shown while free only when moving`() {
        assertFalse(MapFollow.showSpeed(CameraMode.FREE, null))
        assertFalse(MapFollow.showSpeed(CameraMode.FREE, 2))
        assertTrue(MapFollow.showSpeed(CameraMode.FREE, 5))
    }

    @Test
    fun `gesture ends following but programmatic moves do not`() {
        assertEquals(CameraMode.FREE, MapFollow.afterCameraMove(CameraMode.FOLLOW, byGesture = true))
        assertEquals(CameraMode.FOLLOW, MapFollow.afterCameraMove(CameraMode.FOLLOW, byGesture = false))
        assertEquals(CameraMode.FREE, MapFollow.afterCameraMove(CameraMode.FREE, byGesture = false))
    }

    @Test
    fun `camera bearing is measured clockwise from north to the course`() {
        assertEquals(90.0, MapFollow.cameraBearing(Bearing.East))
        assertEquals(270.0, MapFollow.cameraBearing(Bearing.West))
        assertEquals(0.0, MapFollow.cameraBearing(Bearing.North))
    }

    @Test
    fun `camera bearing is null without a course`() {
        assertNull(MapFollow.cameraBearing(null))
    }

}
