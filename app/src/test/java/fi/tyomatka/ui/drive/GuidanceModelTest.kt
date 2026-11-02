package fi.tyomatka.ui.drive

import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.annotation.AnnotationWrapper
import com.stadiamaps.ferrostar.core.annotation.Speed
import com.stadiamaps.ferrostar.core.annotation.SpeedUnit
import fi.tyomatka.ui.formatKm
import uniffi.ferrostar.DeviationKind
import uniffi.ferrostar.DrivingSide
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.TripProgress
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ferrostarin näkymätila → ajokartan otsikon ja mittarien tiedot. Tietueet luodaan suoraan konstruktoreilla. */
class GuidanceModelTest {

    private fun content(
        text: String,
        modifier: ManeuverModifier = ManeuverModifier.LEFT,
        lanes: List<LaneInfo>? = null,
    ) = VisualInstructionContent(text, ManeuverType.TURN, modifier, null, lanes, emptyList())

    private fun step(distance: Double, instruction: VisualInstruction?) = RouteStep(
        emptyList(), distance, 60.0, null, emptyList(), "", listOfNotNull(instruction), emptyList(), null, emptyList(),
        DrivingSide.RIGHT, null,
    )

    private val turnLeft = VisualInstruction(content("Käänny vasemmalle kadulle Hämeenkatu"), null, null, 200.0)
    private val thenRight = VisualInstruction(content("Käänny oikealle", ManeuverModifier.RIGHT), null, null, 100.0)

    private fun state(
        nextStepM: Double = 500.0,
        toManeuverM: Double = 130.0,
        instruction: VisualInstruction? = turnLeft,
    ) = NavigationUiState.empty().copy(
        visualInstruction = instruction,
        progress = TripProgress(toManeuverM, 5_000.0, 600.0),
        remainingSteps = listOf(step(300.0, instruction), step(nextStepM, thenRight)),
    )

    private fun location(speedMps: Double, accuracyM: Double) = UserLocation(
        GeographicCoordinate(61.4978, 23.7610), accuracyM, null, Instant.ofEpochSecond(1_700_000_000),
        uniffi.ferrostar.Speed(speedMps, 0.5),
    )

    @Test
    fun `sitten-rivi nakyy kun seuraava askel on enintaan 250 metria`() {
        assertEquals("Käänny oikealle", guidanceUi(state(nextStepM = 250.0), ::formatKm).thenManeuver?.text)
        assertNull(guidanceUi(state(nextStepM = 251.0), ::formatKm).thenManeuver)
    }

    @Test
    fun `kaistat tulevat alaosasta ja ilman sita kaistoja ei ole`() {
        val lanes = listOf(
            LaneInfo(false, listOf("left"), null),
            LaneInfo(false, listOf("straight"), null),
            LaneInfo(false, listOf("straight"), null),
            LaneInfo(true, listOf("right"), "right"),
        )
        val withLanes = VisualInstruction(content("Käänny oikealle"), null, content("", lanes = lanes), 200.0)

        val ui = guidanceUi(state(instruction = withLanes), ::formatKm)

        assertEquals(4, ui.lanes.size)
        assertTrue(ui.lanes.last().active)
        assertFalse(ui.lanes.first().active)
        assertEquals(listOf("right"), ui.lanes.last().directions)
        assertEquals(emptyList<LaneUi>(), guidanceUi(state(), ::formatKm).lanes)
    }

    @Test
    fun `poikkeama ja uuden reitin haku nakyvat otsikossa`() {
        // Reitiltä kokonaan poikettaessa ydin ei anna ohjetta lainkaan; otsikon pitää silti kertoa uuden reitin hausta.
        val deviation = state(instruction = null).copy(
            routeDeviation = RouteDeviation.Deviation(DeviationKind.CompletelyOffRoute(deviationFromRouteLine = 80.0))
        )
        assertTrue(guidanceUi(deviation, ::formatKm).offRoute)
        assertTrue(guidanceUi(state().copy(isCalculatingNewRoute = true), ::formatKm).offRoute)
        assertFalse(guidanceUi(state().copy(routeDeviation = RouteDeviation.NoDeviation), ::formatKm).offRoute)
    }

    @Test
    fun `askeleesta poikkeaminen reitin sisalla ei ole poikkeama otsikossa`() {
        val offStep = state().copy(routeDeviation = RouteDeviation.Deviation(DeviationKind.OffStepOnRoute(deviationFromStepLine = 20.0)))

        assertFalse(guidanceUi(offStep, ::formatKm).offRoute)
    }

    @Test
    fun `matka pyoristetaan ja muotoillaan ja tekstit tulevat ohjeesta`() {
        val ui = guidanceUi(state(toManeuverM = 130.0), ::formatKm)

        assertEquals("150 m", ui.distanceText)
        assertEquals("1,2 km", guidanceUi(state(toManeuverM = 1_234.0), ::formatKm).distanceText)
        assertEquals("Käänny vasemmalle kadulle Hämeenkatu", ui.primaryText)
        assertEquals(ManeuverModifier.LEFT, ui.maneuver?.maneuverModifier)
        assertNull(ui.secondaryText)
    }

    @Test
    fun `nopeusrajoitus annotaatiosta ja nopeus vain tarkalla sijainnilla`() {
        val limit = AnnotationWrapper<Any>(null, Speed.Value(50.0, SpeedUnit.KILOMETERS_PER_HOUR))
        val accurate = state().copy(currentAnnotation = limit, location = location(13.9, 10.0))

        val ui = guidanceUi(accurate, ::formatKm)

        assertEquals(50, ui.limitKmh)
        assertEquals(50, ui.speedKmh)
        assertNull(guidanceUi(state().copy(location = location(13.9, 60.0)), ::formatKm).speedKmh)
        assertNull(guidanceUi(state().copy(currentAnnotation = AnnotationWrapper<Any>(null, Speed.NoLimit)), ::formatKm).limitKmh)
    }

    @Test
    fun `ilman ohjetta otsikko on tyhja`() {
        val ui = guidanceUi(NavigationUiState.empty(), ::formatKm)

        assertNull(ui.maneuver)
        assertNull(ui.distanceText)
        assertNull(ui.primaryText)
        assertEquals(emptyList<LaneUi>(), ui.lanes)
        assertNull(ui.thenManeuver)
        assertFalse(ui.offRoute)
    }
}
