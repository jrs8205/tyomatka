package fi.tyomatka.ui.history.forms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoZoomTest {
    /** Näytön muotoinen kuva täyttää näkymän: rajaus kuten ennen sovitusta. */
    private val screen = FittedSize(1000f, 2000f)

    private fun PhotoZoom.pinched(zoom: Float, panX: Float, panY: Float) =
        pinched(zoom, panX, panY, fitted = screen, width = 1000, height = 2000)

    @Test
    fun `nipistys suurentaa rajojen sisällä ja veto siirtää`() {
        val zoomed = PhotoZoom().pinched(zoom = 2f, panX = 0f, panY = 0f)
        assertEquals(PhotoZoom(scale = 2f), zoomed)
        assertTrue(zoomed.zoomed)

        val panned = zoomed.pinched(zoom = 1f, panX = 100f, panY = -50f)
        assertEquals(PhotoZoom(scale = 2f, x = 100f, y = -50f), panned)

        val max = PhotoZoom(scale = 5f).pinched(zoom = 10f, panX = 0f, panY = 0f)
        assertEquals(PhotoZoom.MAX, max.scale)
    }

    @Test
    fun `siirto ei vie kuvaa näkyvistä`() {
        // Kaksinkertainen kuva: reuna pysyy näkyvissä, kun siirto on enintään puolet alueesta.
        val pushed = PhotoZoom(scale = 2f).pinched(zoom = 1f, panX = 5000f, panY = -5000f)
        assertEquals(PhotoZoom(scale = 2f, x = 500f, y = -1000f), pushed)
    }

    @Test
    fun `pienennys koko kuvaan palauttaa siirron`() {
        val back = PhotoZoom(scale = 1.2f, x = 40f, y = 40f).pinched(zoom = 0.5f, panX = 10f, panY = 10f)
        assertEquals(PhotoZoom(), back)
        assertFalse(back.zoomed)
    }

    @Test
    fun `suurennoksen pienentäminen kiristää siirtoa`() {
        val tightened = PhotoZoom(scale = 2f, x = 500f, y = -1000f).pinched(zoom = 0.75f, panX = 0f, panY = 0f)
        assertEquals(PhotoZoom(scale = 1.5f, x = 250f, y = -500f), tightened)
    }

    @Test
    fun `kaksoisnapautus vaihtaa suurennoksen ja palauttaa siirron`() {
        assertEquals(PhotoZoom(scale = PhotoZoom.DOUBLE_TAP), PhotoZoom().doubleTapped())
        assertEquals(PhotoZoom(), PhotoZoom(scale = 3f, x = 20f, y = 30f).doubleTapped())
    }

    @Test
    fun `sovitettu koko käyttää pienempää suhdetta molemmilla akseleilla`() {
        assertEquals(FittedSize(1080f, 270f), fittedSize(imageWidth = 2048, imageHeight = 512, viewWidth = 1080, viewHeight = 2400))
        assertEquals(FittedSize(1080f, 1440f), fittedSize(imageWidth = 1536, imageHeight = 2048, viewWidth = 1080, viewHeight = 2208))
        assertEquals(FittedSize(1000f, 2000f), fittedSize(imageWidth = 500, imageHeight = 1000, viewWidth = 1000, viewHeight = 2000))
    }

    @Test
    fun `leveä kuva pysyy pystysuunnassa keskellä ja siirtyy vaakasuunnassa sovitetun leveyden mukaan`() {
        // Katselmoijan esimerkki: 2048 × 512 näkymässä 1080 × 2400 suurennoksella 2,5.
        val fitted = fittedSize(2048, 512, 1080, 2400)
        val pushed = PhotoZoom(scale = 2.5f).pinched(zoom = 1f, panX = 5000f, panY = 5000f, fitted = fitted, width = 1080, height = 2400)
        assertEquals(PhotoZoom(scale = 2.5f, x = 810f, y = 0f), pushed)
    }

    @Test
    fun `pystykuvan siirtorajat tulevat sovitetusta koosta`() {
        val fitted = fittedSize(1536, 2048, 1080, 2208)
        val pushed = PhotoZoom(scale = 2.5f).pinched(zoom = 1f, panX = -5000f, panY = -5000f, fitted = fitted, width = 1080, height = 2208)
        assertEquals(PhotoZoom(scale = 2.5f, x = -810f, y = -696f), pushed)
    }
}
