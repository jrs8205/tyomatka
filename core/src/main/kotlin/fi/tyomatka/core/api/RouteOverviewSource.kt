package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlannedStop
import fi.tyomatka.core.model.RouteOverview

/**
 * Koko päivän reitti annetussa ajojärjestyksessä: polyline kartalle sekä
 * legien kestot ja matkat ETA-laskentaa varten. Yksi kutsu per reittimuutos.
 */
interface RouteOverviewSource {
    /** Hakee koko reitin polylinen ja legien kestot annetussa järjestyksessä. */
    suspend fun computeRouteOverview(origin: GeoPoint, orderedStops: List<PlannedStop>): RouteOverview
}
