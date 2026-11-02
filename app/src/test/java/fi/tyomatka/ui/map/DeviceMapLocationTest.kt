package fi.tyomatka.ui.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceMapLocationTest {
    @Test
    fun `viimeisin piste tulee heti ennen uusia näytteitä`() = runTest {
        val values = lastThenUpdates({ "vanha" }, flowOf("uusi")).toList()

        assertEquals(listOf<String?>("vanha", "uusi"), values)
    }

    @Test
    fun `ilman viimeisintä alkaa tyhjästä`() = runTest {
        val values = lastThenUpdates<String>({ null }, flowOf("uusi")).toList()

        assertEquals(listOf<String?>(null, "uusi"), values)
    }

    @Test
    fun `virhe jättää pisteen pois eikä kaada virtaa`() = runTest {
        val failing = flow<String> { throw IllegalStateException("sijaintipalvelu puuttuu") }

        assertEquals(listOf<String?>("vanha", null), lastThenUpdates({ "vanha" }, failing).toList())
    }

    @Test
    fun `viimeisimmän virhe ei estä uusia näytteitä`() = runTest {
        val values = lastThenUpdates<String>({ throw IllegalStateException("ei lupaa") }, flowOf("uusi")).toList()

        assertEquals(listOf<String?>(null, "uusi"), values)
    }

    @Test
    fun `tilaus alkaa vasta kerääjästä ja päättyy sekunnin kuluttua sen lopusta`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var started = 0
        var closed = 0
        val updates = callbackFlow<String> {
            started++
            awaitClose { closed++ }
        }
        val state = locationState({ "vanha" }, updates, { it.uppercase() }, scope, dispatcher)
        runCurrent()
        assertEquals(0, started, "ei tilausta ennen kerääjää")

        val collector = scope.launch { state.collect {} }
        runCurrent()
        assertEquals(1, started)
        assertEquals("VANHA", state.value)

        collector.cancel()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, closed, "lyhyt välilehden vaihto ei katkaise tilausta")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, closed)
        scope.cancel()
    }
}
