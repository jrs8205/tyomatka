package fi.tyomatka.data.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoScalingTest {
    @Test
    fun `purku pienentää kahden potensseilla, mutta pitkä sivu jää vähintään tavoitteeseen`() {
        // 50 Mpx:n kuva ei saa purkautua täyteen kokoonsa muistiin.
        assertEquals(4, sampleSize(9248, 6936, 2048))
        assertEquals(1, sampleSize(4000, 3000, 2048))
        assertEquals(2, sampleSize(3000, 4096, 2048))
        assertEquals(1, sampleSize(1080, 2400, 2048))
        assertEquals(1, sampleSize(800, 600, 2048))
    }

    @Test
    fun `tallennettava kuva pienenee pitkän sivun mukaan eikä pientä suurenneta`() {
        assertEquals(0.512f, photoScale(4000, 3000, 2048), 1e-6f)
        assertEquals(2048f / 2400, photoScale(1080, 2400, 2048), 1e-6f)
        assertEquals(1f, photoScale(2048, 1536, 2048))
        assertEquals(1f, photoScale(800, 600, 2048))
    }

    @Test
    fun `EXIF-suunta on kääntö ja peilaus`() {
        assertEquals(ExifTransform(0, false), exifTransform(1))
        assertEquals(ExifTransform(0, true), exifTransform(2))
        assertEquals(ExifTransform(180, false), exifTransform(3))
        assertEquals(ExifTransform(180, true), exifTransform(4))
        assertEquals(ExifTransform(90, true), exifTransform(5))
        assertEquals(ExifTransform(90, false), exifTransform(6))
        assertEquals(ExifTransform(270, true), exifTransform(7))
        assertEquals(ExifTransform(270, false), exifTransform(8))
        // Tuntematon tai puuttuva suunta: kuva sellaisenaan.
        assertEquals(ExifTransform(0, false), exifTransform(0))
        assertTrue(exifTransform(1).isIdentity)
        assertFalse(exifTransform(6).isIdentity)
        assertFalse(exifTransform(2).isIdentity)
    }
}
