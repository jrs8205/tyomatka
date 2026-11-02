package fi.tyomatka.ui.map

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class MapLifecycleGateTest {

    private class Parent : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun gate() = MapLifecycleGate(enforceMainThread = false)

    @Test
    fun `portti seuraa vanhemman tilaa`() {
        val parent = Parent()
        val gate = gate()
        gate.follow(parent.lifecycle)

        parent.registry.currentState = Lifecycle.State.RESUMED

        assertEquals(Lifecycle.State.RESUMED, gate.lifecycle.currentState)
    }

    @Test
    fun `destroy antaa tarkkailijalle ON_DESTROYn vaikka vanhempi on yhä resumed`() {
        val parent = Parent()
        val gate = gate()
        gate.follow(parent.lifecycle)
        parent.registry.currentState = Lifecycle.State.RESUMED
        val events = mutableListOf<Lifecycle.Event>()
        gate.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event })

        gate.destroy()

        assertEquals(Lifecycle.Event.ON_DESTROY, events.last())
        assertEquals(Lifecycle.State.RESUMED, parent.lifecycle.currentState)
    }

    @Test
    fun `vanhemman muutokset destroyn jälkeen eivät nosta porttia takaisin`() {
        val parent = Parent()
        val gate = gate()
        gate.follow(parent.lifecycle)
        parent.registry.currentState = Lifecycle.State.CREATED
        gate.destroy()

        parent.registry.currentState = Lifecycle.State.RESUMED

        assertEquals(Lifecycle.State.DESTROYED, gate.lifecycle.currentState)
    }

    @Test
    fun `destroy ennen ensimmäistäkään tapahtumaa ei kaadu`() {
        gate().destroy()
    }

    @Test
    fun `katto laskee portin ja sen nosto palauttaa vanhemman tilan`() {
        val parent = Parent()
        val gate = gate()
        gate.follow(parent.lifecycle)
        parent.registry.currentState = Lifecycle.State.RESUMED

        gate.cap(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, gate.lifecycle.currentState)

        gate.cap(Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.RESUMED, gate.lifecycle.currentState)
    }

    @Test
    fun `portti on vanhemman ja katon alempi tila`() {
        val parent = Parent()
        val gate = gate()
        gate.follow(parent.lifecycle)
        gate.cap(Lifecycle.State.RESUMED)
        parent.registry.currentState = Lifecycle.State.STARTED

        assertEquals(Lifecycle.State.STARTED, gate.lifecycle.currentState)

        gate.cap(Lifecycle.State.CREATED)
        parent.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(Lifecycle.State.CREATED, gate.lifecycle.currentState, "vanhemman nousu ei ohita kattoa")
    }

    @Test
    fun `destroy katon jälkeen vie DESTROYED-tilaan`() {
        val parent = Parent()
        val gate = gate()
        gate.follow(parent.lifecycle)
        parent.registry.currentState = Lifecycle.State.RESUMED
        gate.cap(Lifecycle.State.CREATED)

        gate.destroy()

        assertEquals(Lifecycle.State.DESTROYED, gate.lifecycle.currentState)
        gate.cap(Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.DESTROYED, gate.lifecycle.currentState, "katto ei herätä tuhottua porttia")
    }
}
