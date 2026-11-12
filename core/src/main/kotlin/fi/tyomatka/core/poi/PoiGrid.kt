package fi.tyomatka.core.poi

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Kyselyikkunan pyöristys ruudukkoon: sama pyöristetty ikkuna osuu reunavälimuistiin ja laitteen omaan välimuistiin.
 * Zoomilla ≥ [FINE_ZOOM] ruudukko on [FINE_CELL_DEG], zoomilla ≥ [FINEST_ZOOM] [FINEST_CELL_DEG], muuten [CELL_DEG]:
 * kaupungin näkymässä neljännesasteen ruutuun mahtuu enemmän kuin palvelimen 300 kohdetta (Helsingin
 * latauspisteet), ja tiheimmän keskustan 0,05°:n solussa voi olla yli 300 latauspistettä, jolloin lähentäminen ei
 * muuten auttaisi.
 * Kohteita kysytään vasta zoomilla ≥ [MIN_ZOOM]; yli [MAX_SPAN_DEG]:n ikkunaa ei kysytä.
 */
object PoiGrid {
    const val CELL_DEG = 0.25
    const val FINE_CELL_DEG = 0.05
    const val FINE_ZOOM = 12.0
    const val FINEST_CELL_DEG = 0.01
    const val FINEST_ZOOM = 15.0
    const val MIN_ZOOM = 9.0
    const val MAX_SPAN_DEG = 2.0

    /** Ikkuna ulospäin ruudukkoon ja koordinaattien rajoihin; null, kun pyöristetty ikkuna on liian suuri. */
    fun snap(box: PoiBox, zoom: Double): PoiBox? {
        // Sadasosina ja pienellä toleranssilla: 24.35 / 0.05 on liukulukuna 486,999…, ja ruudukon viiva laajentaisi ikkunaa.
        val step = when {
            zoom >= FINEST_ZOOM -> FINEST_STEPS
            zoom >= FINE_ZOOM -> FINE_STEPS
            else -> STEPS
        }
        fun down(value: Double) = floor(value * 100 / step + EPSILON) * step / 100
        fun up(value: Double) = ceil(value * 100 / step - EPSILON) * step / 100
        val snapped = PoiBox(
            minLon = down(box.minLon).coerceAtLeast(-180.0),
            minLat = down(box.minLat).coerceAtLeast(-90.0),
            maxLon = up(box.maxLon).coerceAtMost(180.0),
            maxLat = up(box.maxLat).coerceAtMost(90.0),
        )
        if (snapped.maxLon - snapped.minLon > MAX_SPAN_DEG + 1e-9 || snapped.maxLat - snapped.minLat > MAX_SPAN_DEG + 1e-9) return null
        return snapped
    }

    private const val STEPS = 25.0
    private const val FINE_STEPS = 5.0
    private const val FINEST_STEPS = 1.0
    private const val EPSILON = 1e-6

    fun cacheKey(box: PoiBox, category: PoiCategory): String = "${bbox(box)}|${category.apiName}"

    /**
     * Ikkuna tekstinä `minLon,minLat,maxLon,maxLat` sadasosin. Negatiivinen nolla (Greenwichin meridiaani, `ceil` antaa
     * -0,0) kirjoitetaan nollana, jotta kartan ja kohdelistan sama solu saa saman avaimen.
     */
    fun bbox(box: PoiBox): String =
        String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", box.minLon + 0.0, box.minLat + 0.0, box.maxLon + 0.0, box.maxLat + 0.0)
}
