package fi.tyomatka.ui.map

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Koko reitin sovitus jättää yläosan kortit (reittivalinta, valmistumisaika, vihje) reitin yläpuolelle. */
class FitPaddingTest {

    @Test
    fun `matala korttipino pitaa nykyisen ylavalin`() {
        assertEquals(220.dp, fitTopPadding(overlayBottom = 0.dp, mapHeight = 800.dp))
        assertEquals(220.dp, fitTopPadding(overlayBottom = 196.dp, mapHeight = 800.dp))
    }

    @Test
    fun `korkea korttipino siirtaa reitin korttien alle`() {
        assertEquals(324.dp, fitTopPadding(overlayBottom = 300.dp, mapHeight = 800.dp))
    }

    @Test
    fun `reitille jaa aina tilaa kartan alaosaan`() {
        // 800 - 120 (alareuna) - 160 (reitin vähimmäistila) = 520
        assertEquals(520.dp, fitTopPadding(overlayBottom = 700.dp, mapHeight = 800.dp))
    }

    @Test
    fun `ennen kartan mittausta kaytetaan nykyista ylavalia`() {
        assertEquals(220.dp, fitTopPadding(overlayBottom = 300.dp, mapHeight = 0.dp))
    }
}
