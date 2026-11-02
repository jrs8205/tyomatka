package fi.tyomatka.nav.ferrostar

import fi.tyomatka.core.nav.ArrivalSide
import fi.tyomatka.core.nav.PlannedInstruction
import fi.tyomatka.core.nav.PlannedStep
import fi.tyomatka.core.nav.SpeechPlan
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.Route
import uniffi.ferrostar.SpokenInstruction
import java.util.UUID

/**
 * Ferrostarin reitin puheohjeet [SpeechPlan]in läpi ennen opastuksen aloitusta tai reitin vaihtoa. Geometria, matkat
 * ja näkyvät ohjeet säilyvät; jokainen puheohje saa uuden tunnisteen, jotta ydin ei pidä sitä jo puhuttuna.
 */
object RouteSpeech {

    fun apply(route: Route, languageTag: String, rerouted: Boolean): Route {
        val planned = SpeechPlan.plan(
            route.steps.map { step ->
                PlannedStep(step.distance, step.spokenInstructions.map { PlannedInstruction(it.text, it.triggerDistanceBeforeManeuver) })
            },
            languageTag,
            rerouted,
            arrivalSide(route),
        )
        val steps = route.steps.zip(planned) { step, plan ->
            step.copy(spokenInstructions = plan.instructions.map { SpokenInstruction(it.text, null, it.triggerM, UUID.randomUUID()) })
        }
        return Route(route.geometry, route.bbox, route.distance, route.waypoints, steps)
    }

    /** Kohteen puoli viimeisestä saapumisohjeesta; reitin lopun tyhjä askel ei kanna ohjetta. */
    fun arrivalSide(route: Route): ArrivalSide {
        val arrive = route.steps.asReversed()
            .flatMap { it.visualInstructions.asReversed() }
            .map { it.primaryContent }
            .firstOrNull { it.maneuverType == ManeuverType.ARRIVE } ?: return ArrivalSide.UNKNOWN
        return when (arrive.maneuverModifier) {
            ManeuverModifier.LEFT, ManeuverModifier.SLIGHT_LEFT, ManeuverModifier.SHARP_LEFT -> ArrivalSide.LEFT
            ManeuverModifier.RIGHT, ManeuverModifier.SLIGHT_RIGHT, ManeuverModifier.SHARP_RIGHT -> ArrivalSide.RIGHT
            else -> ArrivalSide.UNKNOWN
        }
    }
}
