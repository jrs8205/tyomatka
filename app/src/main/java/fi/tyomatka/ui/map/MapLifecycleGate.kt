package fi.tyomatka.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Kartan oma elinkaari. maplibre-compose sitoo MapView'n LocalLifecycleOwneriin ja poistaa
 * tarkkailijansa kompositiosta poistuttaessa ilman onDestroy-kutsua, jolloin natiivikartta jää
 * vapauttamatta välilehteä vaihdettaessa. Portti toistaa vanhemman tilat ja ajetaan
 * [destroy]lla DESTROYED-tilaan, jolloin tarkkailija saa ON_DESTROYn ennen poistoaan.
 *
 * [cap] antaa katon: portin tila on vanhemman ja katon alempi. Ajokartta lepää CREATED-katolla, kun
 * välilehti ei ole näkyvissä tai verho on päällä, ja jatkaa samasta tilasta katon noustessa.
 */
class MapLifecycleGate(enforceMainThread: Boolean = true) : LifecycleOwner {
    private val registry =
        if (enforceMainThread) LifecycleRegistry(this) else LifecycleRegistry.createUnsafe(this)
    private var destroyed = false
    private var parentState = Lifecycle.State.INITIALIZED
    private var capState = Lifecycle.State.RESUMED

    override val lifecycle: Lifecycle get() = registry

    private val parentObserver = LifecycleEventObserver { _, event ->
        parentState = event.targetState
        apply()
    }

    fun follow(parent: Lifecycle) = parent.addObserver(parentObserver)

    fun unfollow(parent: Lifecycle) = parent.removeObserver(parentObserver)

    /** Korkein tila, johon portti saa nousta; [Lifecycle.State.CREATED] pysäyttää kartan piirron ja sijainnin. */
    fun cap(state: Lifecycle.State) {
        capState = state
        apply()
    }

    private fun apply() {
        // Ennen vanhemman ensimmäistä tapahtumaa portti pysyy INITIALIZED-tilassa: sitä ei voi asettaa takaisin.
        if (destroyed || parentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = minOf(parentState, capState)
    }

    fun destroy() {
        destroyed = true
        // INITIALIZED-tilasta ei ole laskevaa tapahtumaa; tarkkailijat eivät ole vielä käynnistyneet.
        if (registry.currentState != Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

/** Portti, joka seuraa nykyistä LocalLifecycleOwneria niin kauan kuin kutsuja on kompositiossa. */
@Composable
fun rememberMapLifecycleGate(): MapLifecycleGate {
    val parent = LocalLifecycleOwner.current.lifecycle
    val gate = remember { MapLifecycleGate() }
    DisposableEffect(parent, gate) {
        gate.follow(parent)
        onDispose { gate.unfollow(parent) }
    }
    return gate
}

/**
 * Kutsutaan kartan JÄLKEEN samassa kompositiossa: efektit puretaan käänteisessä järjestyksessä,
 * joten tämä ajaa portin DESTROYED-tilaan ennen kuin kartta poistaa oman tarkkailijansa.
 */
@Composable
fun DestroyMapOnDispose(gate: MapLifecycleGate) {
    DisposableEffect(gate) {
        onDispose { gate.destroy() }
    }
}
