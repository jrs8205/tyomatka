package fi.tyomatka.nav.ferrostar

import fi.tyomatka.core.nav.ArrivalSide
import uniffi.ferrostar.BoundingBox
import uniffi.ferrostar.DrivingSide
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RouteSpeechTest {

    private val origin = GeographicCoordinate(61.4977, 23.7610)
    private val corner = GeographicCoordinate(61.4980, 23.7660)
    private val end = GeographicCoordinate(61.4990, 23.7670)

    private fun spoken(text: String, trigger: Double) = SpokenInstruction(text, null, trigger, UUID.randomUUID())

    private fun visual(type: ManeuverType, modifier: ManeuverModifier?, text: String, trigger: Double) = VisualInstruction(
        VisualInstructionContent(text, type, modifier, null, null, emptyList()),
        null,
        null,
        trigger,
    )

    private fun step(
        geometry: List<GeographicCoordinate>,
        distance: Double,
        spoken: List<SpokenInstruction>,
        visuals: List<VisualInstruction> = emptyList(),
    ) = RouteStep(geometry, distance, distance / 10, "Hämeenkatu", emptyList(), "ohje", visuals, spoken, null, emptyList(), DrivingSide.RIGHT, null)

    private fun route(vararg steps: RouteStep) = Route(
        listOf(origin, corner, end),
        BoundingBox(origin, end),
        steps.sumOf { it.distance },
        emptyList(),
        steps.toList(),
    )

    @Test
    fun `puheohjeet muokataan ja muu reitti säilyy`() {
        val original = route(
            step(listOf(origin, corner), 405.0, listOf(spoken("Aja itään kadulla Hämeenkatu.", 405.0), spoken("Käänny oikealle kadulle 1605.", 60.0))),
            step(listOf(corner, end), 120.0, listOf(spoken("Määränpääsi on oikealla.", 40.0)), listOf(visual(ManeuverType.ARRIVE, ManeuverModifier.RIGHT, "Perillä", 120.0))),
        )
        val planned = RouteSpeech.apply(original, "fi-FI", rerouted = false)

        assertEquals("Aja kohti katua Hämeenkatu.", planned.steps[0].spokenInstructions[0].text)
        assertEquals("Käänny oikealle tielle 1605.", planned.steps[0].spokenInstructions[1].text)
        // Viimeisen askeleen 40 m:n saapumisohje jää pois: moottori puhuu oman Perillä-lauseen.
        assertTrue(planned.steps[1].spokenInstructions.isEmpty())
        assertEquals(original.geometry, planned.geometry)
        assertEquals(original.bbox, planned.bbox)
        assertEquals(original.distance, planned.distance)
        assertEquals(original.steps.map { it.geometry }, planned.steps.map { it.geometry })
        assertEquals(original.steps.map { it.distance }, planned.steps.map { it.distance })
        assertEquals(original.steps[1].visualInstructions, planned.steps[1].visualInstructions)
        val ids = planned.steps.flatMap { s -> s.spokenInstructions.map { it.utteranceId } }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { id -> original.steps.any { s -> s.spokenInstructions.any { it.utteranceId == id } } })
        assertNotEquals(original.steps[0].spokenInstructions[0].text, planned.steps[0].spokenInstructions[0].text)
    }

    @Test
    fun `vaihdetun reitin lähtöohje on jatka-muodossa`() {
        val original = route(
            step(listOf(origin, corner), 420.0, listOf(spoken("Aja itään kadulla Hämeenkatu.", 420.0), spoken("Käänny vasemmalle kadulle Pajatie.", 60.0))),
        )
        val planned = RouteSpeech.apply(original, "fi", rerouted = true)
        assertEquals("Jatka 400 metriä, sitten käänny vasemmalle tielle Pajatie.", planned.steps[0].spokenInstructions[0].text)
    }

    @Test
    fun `saapumisen puoli luetaan viimeisestä saapumisohjeesta`() {
        val withTrailingStep = route(
            step(listOf(origin, corner), 300.0, listOf(spoken("Määränpääsi on vasemmalla.", 40.0)), listOf(visual(ManeuverType.ARRIVE, ManeuverModifier.LEFT, "Perillä", 300.0))),
            step(listOf(corner, corner), 0.0, emptyList()),
        )
        assertEquals(ArrivalSide.LEFT, RouteSpeech.arrivalSide(withTrailingStep))
        val noSide = route(step(listOf(origin, corner), 300.0, listOf(spoken("Olet saapunut määränpäähäsi.", 40.0)), listOf(visual(ManeuverType.ARRIVE, null, "Perillä", 300.0))))
        assertEquals(ArrivalSide.UNKNOWN, RouteSpeech.arrivalSide(noSide))
        assertEquals(ArrivalSide.UNKNOWN, RouteSpeech.arrivalSide(route()))
    }
}
