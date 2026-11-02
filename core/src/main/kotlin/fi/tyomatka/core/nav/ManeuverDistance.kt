package fi.tyomatka.core.nav

import kotlin.math.roundToLong

/**
 * Otsikon matka seuraavaan käännökseen pyöristettynä: alle 100 m alaspäin kymmeniin, jotta käännös ei näytä olevan
 * kauempana kuin on, alle kilometrin lähimpään 50 metriin ja siitä ylöspäin lähimpään 100 metriin. Epäkelpo tai
 * negatiivinen matka on nolla.
 */
fun roundManeuverDistanceM(meters: Double): Long = when {
    !meters.isFinite() || meters <= 0 -> 0
    meters < 100 -> (meters / 10).toLong() * 10
    meters < 1_000 -> (meters / 50).roundToLong() * 50
    else -> (meters / 100).roundToLong() * 100
}
