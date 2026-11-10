package fi.tyomatka.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Teemojen kontrastit WCAG AAA:n mukaan: teksti vähintään 7:1, reunat ja muut käyttöliittymän osat vähintään 4,5:1.
 * Parit ovat määrittelyn kontrastitaulukosta molemmille teemoille.
 */
class ThemeContrastTest {

    private val schemes = mapOf("vaalea" to LightColors, "tumma" to DarkColors)

    private fun assertText(name: String, pick: (ColorScheme) -> Pair<Color, Color>) = assertAtLeast(7.0, name, pick)

    private fun assertUi(name: String, pick: (ColorScheme) -> Pair<Color, Color>) = assertAtLeast(4.5, name, pick)

    private fun assertAtLeast(min: Double, name: String, pick: (ColorScheme) -> Pair<Color, Color>) {
        schemes.forEach { (theme, scheme) ->
            val (fg, bg) = pick(scheme)
            val ratio = contrastRatio(fg, bg)
            assertTrue(ratio >= min, "$theme $name: $ratio < $min")
        }
    }

    @Test
    fun `musta valkoisella on 21`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
    }

    @Test
    fun `sovelluksen sininen valkoisella on 8_44`() {
        assertEquals(8.44, contrastRatio(Color(0xFF0B4A9E), Color.White), 0.01)
    }

    @Test
    fun `kontrasti ei riipu jarjestyksesta`() {
        assertEquals(contrastRatio(Color(0xFF0B4A9E), Color.White), contrastRatio(Color.White, Color(0xFF0B4A9E)), 1e-9)
    }

    @Test
    fun `pintojen tekstit ovat AAA`() {
        assertText("onSurface/surfaceContainerHigh") { it.onSurface to it.surfaceContainerHigh }
        assertText("onSurfaceVariant/surfaceContainerHigh") { it.onSurfaceVariant to it.surfaceContainerHigh }
        assertText("onSurfaceVariant/surfaceContainerHighest") { it.onSurfaceVariant to it.surfaceContainerHighest }
        assertText("onSurface/surfaceContainerLow") { it.onSurface to it.surfaceContainerLow }
        assertText("onSurfaceVariant/surfaceContainerLow") { it.onSurfaceVariant to it.surfaceContainerLow }
        assertText("onSurfaceVariant/surface") { it.onSurfaceVariant to it.surface }
    }

    @Test
    fun `korostusvarit valitsimen ja ikkunan pohjalla ovat AAA`() {
        assertText("primary/surfaceContainerHigh") { it.primary to it.surfaceContainerHigh }
        assertText("error/surfaceContainerHigh") { it.error to it.surfaceContainerHigh }
        assertText("tertiary/surfaceContainerHigh") { it.tertiary to it.surfaceContainerHigh }
    }

    private fun ColorScheme.pinned() = listOf(
        surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest,
        outlineVariant, inverseSurface, inverseOnSurface, inversePrimary,
    )

    @Test
    fun `vaalean teeman pintasavyt ovat kiinnitetyt`() {
        assertEquals(
            listOf(0xFFFFFFFF, 0xFFF3F5F9, 0xFFEDF0F5, 0xFFE8ECF2, 0xFFDCE1E9, 0xFFC9CDD5, 0xFF2A2C31, 0xFFF2F2F2, 0xFF9FC4FF)
                .map { Color(it) },
            LightColors.pinned(),
        )
    }

    @Test
    fun `tumman teeman pintasavyt ovat kiinnitetyt`() {
        assertEquals(
            listOf(0xFF121212, 0xFF1C1D21, 0xFF222428, 0xFF2A2C31, 0xFF33363C, 0xFF44474E, 0xFFF3F5F9, 0xFF111111, 0xFF0B4A9E)
                .map { Color(it) },
            DarkColors.pinned(),
        )
    }

    @Test
    fun `muodot ja pienin teksti ovat maarittelyn mukaiset`() {
        assertEquals(RoundedCornerShape(14.dp), AppShapes.extraSmall)
        assertEquals(RoundedCornerShape(percent = 50), AppShapes.small)
        assertEquals(RoundedCornerShape(22.dp), AppShapes.medium)
        assertEquals(RoundedCornerShape(24.dp), AppShapes.large)
        assertEquals(RoundedCornerShape(28.dp), AppShapes.extraLarge)
        assertEquals(14.sp, AppTypography.labelSmall.fontSize)
    }

    @Test
    fun `lapikuultava vari ei kelpaa kontrastiin`() {
        assertFailsWith<IllegalArgumentException> { contrastRatio(Color.Transparent, Color.White) }
    }

    @Test
    fun `korostusvarit kortilla ovat AAA`() {
        assertText("primary/surfaceContainerLow") { it.primary to it.surfaceContainerLow }
        assertText("tertiary/surfaceContainerLow") { it.tertiary to it.surfaceContainerLow }
        assertText("error/surfaceContainerLow") { it.error to it.surfaceContainerLow }
        assertText("primary/surface") { it.primary to it.surface }
    }

    @Test
    fun `varillisten pohjien tekstit ovat AAA`() {
        assertText("onPrimary/primary") { it.onPrimary to it.primary }
        assertText("onPrimaryContainer/primaryContainer") { it.onPrimaryContainer to it.primaryContainer }
        assertText("onSecondaryContainer/secondaryContainer") { it.onSecondaryContainer to it.secondaryContainer }
    }

    @Test
    fun `ilmoituksen tekstit ovat AAA`() {
        assertText("inverseOnSurface/inverseSurface") { it.inverseOnSurface to it.inverseSurface }
        assertText("inversePrimary/inverseSurface") { it.inversePrimary to it.inverseSurface }
    }

    @Test
    fun `vaarallisen napin teksti on AAA`() {
        assertText("surfaceContainerHigh/error") { it.surfaceContainerHigh to it.error }
    }

    @Test
    fun `reunat erottuvat`() {
        assertUi("outline/surfaceContainerLow") { it.outline to it.surfaceContainerLow }
        assertUi("outline/surface") { it.outline to it.surface }
    }

    @Test
    fun `tanaan-kortin taytetty nappi erottuu ja sen teksti on AAA`() {
        assertTrue(contrastRatio(LightColors.primary, LightColors.primaryContainer) >= 4.5)
        assertTrue(contrastRatio(TodayFilledDark, DarkColors.primaryContainer) >= 4.5)
        assertTrue(contrastRatio(OnTodayFilledDark, TodayFilledDark) >= 7.0)
    }
}
