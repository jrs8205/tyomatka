package fi.tyomatka.ui.drive

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedPanelTest {

    private fun textToBoxRatio(density: Density): Float = with(density) { 9.sp.toPx() / 60.dp.toPx() }

    @Test
    fun `kyltin teksti ja ympyra kasvavat samassa suhteessa fonttikoon mukana`() {
        val normal = signDensity(Density(3f, 1f))
        val large = signDensity(Density(3f, 1.5f))
        assertEquals(textToBoxRatio(normal), textToBoxRatio(large), 0.0001f)
    }

    @Test
    fun `iso fontti kasvattaa koko kylttia`() {
        val large = signDensity(Density(3f, 1.5f))
        assertEquals(270f, with(large) { 60.dp.toPx() }, 0.0001f)
        assertEquals(1f, large.fontScale, 0.0001f)
    }

    @Test
    fun `kartan rivi varaa koko paneelin ja valin fonttikoon mukaan`() {
        assertEquals(12f + 64f, bottomRowHeight(1f).value, 0.0001f)
        assertEquals(12f + 96f, bottomRowHeight(1.5f).value, 0.0001f)
        assertEquals(12f + 64f, bottomRowHeight(0.85f).value, 0.0001f)
    }

    @Test
    fun `kartan rivi on vahintaan skaalatun paneelin korkuinen`() {
        for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 2f)) {
            val real = Density(3f, scale)
            val panelPx = with(signDensity(real)) { SpeedPanelSize.toPx() }
            val reservedPx = with(real) { (bottomRowHeight(scale) - 12.dp).toPx() }
            assertEquals(reservedPx, panelPx, 0.01f)
        }
    }

    @Test
    fun `pieni fontti ei pienenna kylttia`() {
        val small = signDensity(Density(3f, 0.85f))
        assertEquals(180f, with(small) { 60.dp.toPx() }, 0.0001f)
    }
}
