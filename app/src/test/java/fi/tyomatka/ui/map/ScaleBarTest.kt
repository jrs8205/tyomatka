package fi.tyomatka.ui.map

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaleBarTest {
    @Test
    fun `mittakaava näkyy vain, kun kartan palkki on metrinen`() {
        val shown = listOf("fi-FI", "sv-FI", "en-GB", "en", "sv-SE", "en-US", "en-LR", "my-MM")
            .associateWith { scaleBarIsMetric(Locale.forLanguageTag(it)) }

        assertEquals(
            mapOf(
                "fi-FI" to true, "sv-FI" to true, "en-GB" to true, "en" to true, "sv-SE" to true,
                "en-US" to false, "en-LR" to false, "my-MM" to false,
            ),
            shown,
        )
    }
}
