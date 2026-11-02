package fi.tyomatka.ui.history.forms

import kotlin.math.max
import kotlin.math.min

/** Näkymään sovitetun kuvan koko pikseleinä. */
data class FittedSize(val width: Float, val height: Float)

/**
 * `ContentScale.Fit`-sovitetun kuvan koko näkymässä: sama skaala molemmilla akseleilla ja pienempi suhde, joten kuva
 * mahtuu kokonaan ja toiselle akselille jää tyhjää. Siirtorajat lasketaan tästä eikä näkymän mitoista, ettei kuvaa
 * voi vetää tyhjän tilan verran näkyvistä.
 */
fun fittedSize(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): FittedSize {
    val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    return FittedSize(imageWidth * scale, imageHeight * scale)
}

/** Kuvan katselun suurennos ja siirto: 1 on koko kuva näkyvissä, siirto on nollassa. */
data class PhotoZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed: Boolean get() = scale > MIN

    /**
     * Sormien nipistys ja veto näkymässä, jonka koko on [width] × [height] ja jossa kuva on sovitettuna [fitted]n
     * kokoinen. Siirto rajataan niin, että suurennetun kuvan reuna pysyy näkymän reunan ulkopuolella: kuvaa ei voi
     * vetää kokonaan näkyvistä. Akselilla, jolla suurennettu kuva on näkymää pienempi, kuva pysyy keskellä.
     */
    fun pinched(zoom: Float, panX: Float, panY: Float, fitted: FittedSize, width: Int, height: Int): PhotoZoom {
        val next = (scale * zoom).coerceIn(MIN, MAX)
        if (next == MIN) return PhotoZoom()
        return PhotoZoom(next, clamp(x + panX, next * fitted.width, width), clamp(y + panY, next * fitted.height, height))
    }

    /** Kaksoisnapautus vaihtaa suurennoksen ja palauttaa siirron. */
    fun doubleTapped(): PhotoZoom = if (zoomed) PhotoZoom() else PhotoZoom(DOUBLE_TAP)

    private fun clamp(value: Float, scaledSize: Float, viewSize: Int): Float {
        val limit = max(0f, (scaledSize - viewSize) / 2f)
        return value.coerceIn(-limit, limit)
    }

    companion object {
        const val MIN = 1f
        const val MAX = 6f
        const val DOUBLE_TAP = 2.5f
    }
}
