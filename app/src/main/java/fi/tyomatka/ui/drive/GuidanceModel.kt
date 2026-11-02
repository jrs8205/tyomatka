package fi.tyomatka.ui.drive

import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import fi.tyomatka.core.nav.roundManeuverDistanceM
import fi.tyomatka.ui.map.MapFollow
import uniffi.ferrostar.DeviationKind
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.VisualInstructionContent
import kotlin.math.roundToInt

/** Yksi kaista ohjeen otsikossa: suunnat (OSRM-nimet, esim. "left", "straight") ja onko kaista reitin mukainen. */
data class LaneUi(val directions: List<String>, val active: Boolean)

/**
 * Ajokartan otsikon ja mittarien tiedot Googlen navigointinäkymän tapaan: nuoli, matka käännökseen, kadunnimi,
 * kaistat, Sitten-rivi, poikkeama sekä nopeus ja rajoitus.
 */
data class GuidanceUi(
    val maneuver: VisualInstructionContent?,
    val distanceText: String?,
    val primaryText: String?,
    val secondaryText: String?,
    val lanes: List<LaneUi>,
    val thenManeuver: VisualInstructionContent?,
    val offRoute: Boolean,
    val speedKmh: Int?,
    val limitKmh: Int?,
)

/** Sitten-rivi näkyy, kun seuraava askel on enintään tämän pituinen: käännökset tulevat heti peräkkäin. */
const val THEN_MAX_STEP_M = 250.0

/** Puhdas muunnos Ferrostarin näkymätilasta; [formatDistance] saa pyöristetyn matkan metreinä. */
fun guidanceUi(state: NavigationUiState, formatDistance: (Long) -> String): GuidanceUi {
    val instruction = state.visualInstruction
    // Askel 0 on nykyinen; askeleen 1 oma ohje kertoo sen lopussa olevan käännöksen.
    val nextStep = state.remainingSteps?.getOrNull(1)
    val then = nextStep?.takeIf { it.distance <= THEN_MAX_STEP_M }?.visualInstructions?.lastOrNull()?.primaryContent
    val location = state.location
    return GuidanceUi(
        maneuver = instruction?.primaryContent,
        distanceText = state.progress?.let { formatDistance(roundManeuverDistanceM(it.distanceToNextManeuver)) },
        primaryText = instruction?.primaryContent?.text,
        secondaryText = instruction?.secondaryContent?.text,
        lanes = instruction?.subContent?.laneInfo.orEmpty().map { LaneUi(it.directions, it.active) },
        thenManeuver = then,
        // Askeleesta poikkeaminen reitin sisällä pitää ohjeensa eikä hae uutta reittiä; vain kokonaan reitiltä
        // poikkeaminen (ydin ei anna silloin ohjetta) ja uuden reitin haku näkyvät otsikossa.
        offRoute = (state.routeDeviation as? RouteDeviation.Deviation)?.kind is DeviationKind.CompletelyOffRoute ||
            state.isCalculatingNewRoute == true,
        speedKmh = MapFollow.speedKmh(location?.speed?.value, location?.horizontalAccuracy),
        limitKmh = state.currentAnnotation?.speedLimit?.value(MeasurementSpeedUnit.KilometersPerHour)?.roundToInt(),
    )
}
