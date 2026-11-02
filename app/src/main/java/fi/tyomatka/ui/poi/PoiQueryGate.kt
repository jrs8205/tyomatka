package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory

/** Yksi haku: pyöristetty ikkuna ja päällä olevat luokat. */
data class PoiQuery(val box: PoiBox, val categories: Set<PoiCategory>)

/**
 * Estää saman haun toiston: onnistunut haku ei toistu samalle ikkunalle ja luokille; epäonnistunut (verkkovirhe,
 * palvelu ei saatavilla) toistuu vasta [RETRY_SEC]:n kuluttua, vaikka seurannan kamera liikkuisi joka sekunti.
 */
class PoiQueryGate {
    private var last: PoiQuery? = null
    private var lastAtSec = 0L
    private var lastFailed = false
    private var lastTruncated = false

    fun shouldLoad(query: PoiQuery, nowSec: Long): Boolean {
        val previous = last ?: return true
        if (query == previous) return lastFailed && nowSec - lastAtSec >= RETRY_SEC
        // Lähennys tai pieni siirto jo haetun, katkaisemattoman ikkunan sisällä: kaikki kohteet ovat jo laitteella, eikä
        // tiheämpi ruudukko saa moninkertaistaa pyyntöjä (palvelimen raja 60/min).
        return lastFailed || lastTruncated || query.categories != previous.categories || !previous.box.contains(query.box)
    }

    fun loaded(query: PoiQuery, nowSec: Long, failed: Boolean, truncated: Boolean = false) {
        last = query
        lastAtSec = nowSec
        lastFailed = failed
        lastTruncated = truncated
    }

    fun reset() {
        last = null
        lastFailed = false
        lastTruncated = false
    }

    companion object {
        const val RETRY_SEC = 60L
    }
}

private fun PoiBox.contains(other: PoiBox): Boolean =
    other.minLon >= minLon && other.maxLon <= maxLon && other.minLat >= minLat && other.maxLat <= maxLat
