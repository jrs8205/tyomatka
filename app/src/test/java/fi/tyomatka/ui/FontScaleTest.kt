package fi.tyomatka.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FontScaleTest {
    @Test
    fun `fonttikoko rajataan 150 prosenttiin`() {
        assertEquals(1.5f, cappedFontScale(2.0f))
        assertEquals(1.5f, cappedFontScale(1.5f))
        assertEquals(1.3f, cappedFontScale(1.3f))
        assertEquals(0.85f, cappedFontScale(0.85f))
    }
}
