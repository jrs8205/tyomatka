package fi.tyomatka.ui.map

import fi.tyomatka.core.api.RoutePreview
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteChoice
import kotlin.math.floor

/**
 * Kartan reittivertailu: valittu reitti sinisenä, toinen harmaana, ja molemmista matka ja aika. Reitit ovat samalta
 * palvelimelta ja samoilla valinnoilla kuin ajo, joten kartta näyttää sen, mitä ajossa opastetaan.
 */
data class RouteComparison(
    val choice: RouteChoice = RouteChoice.NORMAL,
    val previews: Map<RouteChoice, RoutePreview> = emptyMap(),
) {
    /** Valinta näytetään vasta, kun molemmat reitit on saatu. */
    val available: Boolean get() = RouteChoice.entries.all { it in previews }

    /** Valitun reitin viiva; ilman sitä suunnitelman oma reitti, jolloin kartta toimii kuten ennen. */
    fun drawnRoute(fallback: List<GeoPoint>): List<GeoPoint> = previews[choice]?.points ?: fallback

    /** Toinen reitti harmaana; sama viiva ei piirry kahdesti. */
    val otherRoute: List<GeoPoint>
        get() {
            if (!available) return emptyList()
            val selected = previews.getValue(choice).points
            val other = previews.entries.first { it.key != choice }.value.points
            return if (other == selected) emptyList() else other
        }

    /** Päivän valmistuminen muuttuu valitun reitin ja nopeimman reitin ajoaikojen erolla. */
    fun finishEpochSec(base: Long?): Long? {
        if (base == null || !available) return base
        return base + previews.getValue(choice).durationSec - previews.getValue(RouteChoice.NORMAL).durationSec
    }

    /** Stopin saapumisaika valitulla reitillä: osuuksien erot nopeimpaan kertyvät stopilta toiselle. */
    fun arrivalEpochSec(index: Int, base: Long?): Long? {
        if (base == null || !available) return base
        val selected = previews.getValue(choice).legDurationsSec
        val normal = previews.getValue(RouteChoice.NORMAL).legDurationsSec
        if (index >= selected.size || selected.size != normal.size) return base
        return base + (0..index).sumOf { selected[it] - normal[it] }
    }

    companion object {
        /** Epäonnistunut suunnitelma ei kerro lähtöpaikkaa: silloin juuri saatu sijainti, ei edellisen haun paikka. */
        fun previewOrigin(plan: Result<GeoPoint?>, start: GeoPoint?): GeoPoint? = plan.getOrElse { start }

        /** Reitit haetaan uudelleen vasta, kun järjestys, stopin paikka tai lähtöpaikka noin 100 m:n tarkkuudella muuttuu. */
        fun key(origin: GeoPoint, stops: List<Pair<Long, GeoPoint>>): String =
            "${floor(origin.lat * 1000).toLong()},${floor(origin.lng * 1000).toLong()}|${stopsKey(stops)}"

        fun stopsKey(stops: List<Pair<Long, GeoPoint>>): String =
            stops.joinToString(";") { (id, point) -> "$id:${point.lat},${point.lng}" }
    }
}
