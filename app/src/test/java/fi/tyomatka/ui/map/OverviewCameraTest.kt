package fi.tyomatka.ui.map

import fi.tyomatka.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverviewCameraTest {

    private val a = GeoPoint(60.20, 24.65)
    private val b = GeoPoint(60.22, 24.70)
    private val origin = GeoPoint(60.19, 24.60)
    private val me = GeoPoint(60.21, 24.66)

    @Test
    fun `avaus sovittaa stopit ja lahtopaikan`() {
        val camera = OverviewCamera()
        assertEquals(Fit(listOf(a, b, origin)), camera.onData(listOf(a, b), origin, location = null))
        assertEquals(CameraMode.FREE, camera.mode)
    }

    @Test
    fun `ensimmainen sijainti sovitetaan mukaan vain kerran`() {
        val camera = OverviewCamera()
        camera.onData(listOf(a, b), origin, location = null)
        assertEquals(Fit(listOf(a, b, origin, me)), camera.onFirstLocation(me, listOf(a, b), origin))
        assertNull(camera.onFirstLocation(GeoPoint(60.3, 24.7), listOf(a, b), origin))
    }

    @Test
    fun `datan muutos sovittaa myos eleen ja seurannan jalkeen ja paattaa seurannan`() {
        val camera = OverviewCamera()
        camera.onData(listOf(a), origin, null)
        camera.onGesture()
        assertEquals(Fit(listOf(a, b, origin, me)), camera.onData(listOf(a, b), origin, me))
        assertEquals(CameraMode.FREE, camera.mode)
        camera.onFollow()
        assertEquals(CameraMode.FOLLOW, camera.mode)
        assertEquals(Fit(listOf(b, a, origin, me)), camera.onData(listOf(b, a), origin, me))
        assertEquals(CameraMode.FREE, camera.mode)
    }

    @Test
    fun `ele tai seuranta ennen ensimmaista sijaintia estaa sen sovituksen`() {
        val gestured = OverviewCamera()
        gestured.onData(listOf(a), origin, null)
        gestured.onGesture()
        assertNull(gestured.onFirstLocation(me, listOf(a), origin))
        val following = OverviewCamera()
        following.onFollow()
        assertNull(following.onFirstLocation(me, listOf(a), origin))
    }

    @Test
    fun `taustalla paivittyva lahtopaikka ja reittiviiva eivat paata seurantaa eivatka siirra raahattua karttaa`() {
        // stopin tilan vaihto haki reitin uudelleen, ja origin/routePoints-muutos lopetti seurannan.
        val following = OverviewCamera()
        following.onData(listOf(a, b), origin, null)
        following.onFollow()
        assertNull(following.onBackgroundData(listOf(a, b), GeoPoint(60.21, 24.61), me))
        assertEquals(CameraMode.FOLLOW, following.mode)
        val dragged = OverviewCamera()
        dragged.onData(listOf(a, b), origin, null)
        dragged.onGesture()
        assertNull(dragged.onBackgroundData(listOf(a, b), GeoPoint(60.21, 24.61), me))
        // Koskematon vapaa kartta seuraa reittiviivan päivitystä.
        val untouched = OverviewCamera()
        untouched.onData(listOf(a, b), origin, null)
        assertEquals(Fit(listOf(a, b, GeoPoint(60.21, 24.61), me)), untouched.onBackgroundData(listOf(a, b), GeoPoint(60.21, 24.61), me))
        assertEquals(CameraMode.FREE, untouched.mode)
    }

    @Test
    fun `ilman stoppeja kartta seuraa sijaintia heti avattaessa`() {
        // Kartta-välilehti näyttää sijainnin ja seuraa sitä aina avattaessa, kun päivällä ei ole stoppeja.
        val camera = OverviewCamera()
        assertNull(camera.onData(emptyList(), null, null))
        assertEquals(CameraMode.FOLLOW, camera.mode)
        // Seuranta hoitaa sijainnin: ensimmäistä sijaintia ei sovitata erikseen eikä lähtöpaikan päivitys siirrä karttaa.
        assertNull(camera.onFirstLocation(me, emptyList(), null))
        assertNull(camera.onBackgroundData(emptyList(), origin, me))
    }

    @Test
    fun `stoppien lisays tyhjaan paivaan vaihtaa seurannan koko reittiin`() {
        val camera = OverviewCamera()
        camera.onData(emptyList(), null, null)
        assertEquals(Fit(listOf(a, origin)), camera.onData(listOf(a), origin, location = null))
        assertEquals(CameraMode.FREE, camera.mode)
    }
}
