package fi.tyomatka.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x -kontrastisuhde (1–21) kahden peittävän värin välillä; järjestyksellä ei ole väliä. Läpikuultavan värin
 * kontrasti riippuu sen alla olevasta pohjasta, joten sitä ei voi laskea tästä.
 */
fun contrastRatio(foreground: Color, background: Color): Double {
    require(foreground.alpha == 1f && background.alpha == 1f) { "Kontrasti lasketaan vain peittäville väreille" }
    val a = relativeLuminance(foreground)
    val b = relativeLuminance(background)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

/** sRGB-värin suhteellinen luminanssi WCAG:n kaavalla. */
private fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}
