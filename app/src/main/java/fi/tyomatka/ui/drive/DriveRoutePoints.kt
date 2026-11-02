package fi.tyomatka.ui.drive

import fi.tyomatka.core.model.GeoPoint
import uniffi.ferrostar.GeographicCoordinate

/**
 * Ajettavan legin jäljellä olevat askeleet "Vain reitin varrella" -suodattimelle: nykyisen askeleen alusta loppuun,
 * peräkkäisten askelten yhteinen liitospiste kerran. Ferrostarin askelindeksi on suhteessa nykyiseen askeleeseen, joten
 * rajaus tehdään askelista eikä koko reitin geometriasta; enintään nykyisen askeleen alku jää taakse. Alle kaksi pistettä
 * on tyhjä lista (suodatin ei rajaa).
 */
fun driveRoutePoints(stepGeometries: List<List<GeographicCoordinate>>?): List<GeoPoint> {
    if (stepGeometries.isNullOrEmpty()) return emptyList()
    val points = ArrayList<GeoPoint>(stepGeometries.sumOf { it.size })
    for (step in stepGeometries) {
        for (coordinate in step) {
            val point = GeoPoint(coordinate.lat, coordinate.lng)
            if (points.lastOrNull() != point) points += point
        }
    }
    return if (points.size >= 2) points else emptyList()
}

/** Muuttuu vain askeleen tai reitin vaihtuessa: askelten ja pisteiden määrä sekä ensimmäinen ja viimeinen piste. */
fun driveRouteKey(stepGeometries: List<List<GeographicCoordinate>>?): Any? = stepGeometries?.let { steps ->
    listOf(steps.size, steps.sumOf { it.size }, steps.firstOrNull()?.firstOrNull(), steps.lastOrNull()?.lastOrNull())
}
