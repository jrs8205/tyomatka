package fi.tyomatka.ui.plan.nearby

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.data.repo.NearbyData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NearbySessionTest {
    private val home = GeoPoint(60.27, 24.84)
    private val elsewhere = GeoPoint(61.5, 23.8)

    private fun data(loading: Boolean, vararg categories: PoiCategory) =
        NearbyData(categories.associateWith { emptyList() }, emptyList(), loading)

    private class Fakes {
        var locations = ArrayDeque<GeoPoint?>()
        var locateCalls = 0
        val loads = mutableListOf<Pair<GeoPoint, PoiCategory>>()
        var flowFor: (GeoPoint) -> Flow<NearbyData> = { flow { } }

        suspend fun locate(): GeoPoint? {
            locateCalls++
            return locations.removeFirst()
        }

        fun load(center: GeoPoint, first: PoiCategory): Flow<NearbyData> {
            loads += center to first
            return flowFor(center)
        }
    }

    private fun TestScope.session(f: Fakes) = NearbySession(backgroundScope, f::locate, f::load)

    @Test
    fun `avaus lukee sijainnin ja näyttää latauksen vaiheet`() = runTest {
        val f = Fakes().apply {
            locations += home
            flowFor = { flow { emit(data(true, PoiCategory.GAS)); emit(data(false, *PoiCategory.entries.toTypedArray())) } }
        }
        val session = session(f)
        assertEquals(NearbySessionState.Idle, session.state.value)

        session.open(PoiCategory.GAS)
        runCurrent()

        assertEquals(NearbySessionState.Loaded(home, data(false, *PoiCategory.entries.toTypedArray())), session.state.value)
        assertEquals(listOf(home to PoiCategory.GAS), f.loads)
    }

    @Test
    fun `toinen avaus ennen sulkemista ei lue sijaintia uudelleen`() = runTest {
        val f = Fakes().apply { locations += home; locations += elsewhere }
        val session = session(f)

        session.open(PoiCategory.FUEL)
        runCurrent()
        session.open(PoiCategory.REST)
        runCurrent()

        assertEquals(1, f.locateCalls)
        assertEquals(listOf(home to PoiCategory.FUEL), f.loads)
    }

    @Test
    fun `sulkeminen kesken latauksen peruu keruun eikä myöhempi vaihe muuta tilaa`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val f = Fakes().apply {
            locations += home
            flowFor = {
                flow {
                    emit(data(true, PoiCategory.FUEL))
                    try {
                        gate.await()
                    } finally {
                        cancelled = true
                    }
                    emit(data(false, *PoiCategory.entries.toTypedArray()))
                }
            }
        }
        val session = session(f)
        session.open(PoiCategory.FUEL)
        runCurrent()
        assertEquals(NearbySessionState.Loaded(home, data(true, PoiCategory.FUEL)), session.state.value)

        session.close()
        gate.complete(Unit)
        runCurrent()

        assertTrue(cancelled)
        assertEquals(NearbySessionState.Idle, session.state.value)
    }

    @Test
    fun `uudelleenavaus lukee sijainnin uudelleen ja lataa uuden keskipisteen`() = runTest {
        val f = Fakes().apply {
            locations += home
            locations += elsewhere
            flowFor = { flow { emit(data(false, PoiCategory.FUEL)) } }
        }
        val session = session(f)

        session.open(PoiCategory.FUEL)
        runCurrent()
        session.close()
        session.open(PoiCategory.CHARGING)
        runCurrent()

        assertEquals(2, f.locateCalls)
        assertEquals(listOf(home to PoiCategory.FUEL, elsewhere to PoiCategory.CHARGING), f.loads)
        assertEquals(NearbySessionState.Loaded(elsewhere, data(false, PoiCategory.FUEL)), session.state.value)
    }

    @Test
    fun `ei sijaintia`() = runTest {
        val f = Fakes().apply { locations += null }
        val session = session(f)

        session.open(PoiCategory.FUEL)
        runCurrent()

        assertEquals(NearbySessionState.NoLocation, session.state.value)
        assertTrue(f.loads.isEmpty())
    }

    @Test
    fun `sulkeminen sijainnin luvun aikana peruu sen`() = runTest {
        var cancelled = false
        val session = NearbySession(
            backgroundScope,
            locate = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            load = { _, _ -> error("ei latausta") },
        )

        session.open(PoiCategory.FUEL)
        runCurrent()
        assertEquals(NearbySessionState.Locating, session.state.value)

        session.close()
        runCurrent()

        assertTrue(cancelled)
        assertEquals(NearbySessionState.Idle, session.state.value)
    }

    @Test
    fun `latauksen virhe ei jätä latausta päälle`() = runTest {
        val f = Fakes().apply {
            locations += home
            flowFor = { flow { emit(data(true, PoiCategory.FUEL)); error("kanta rikki") } }
        }
        val session = session(f)

        session.open(PoiCategory.FUEL)
        runCurrent()

        val state = session.state.value as NearbySessionState.Loaded
        assertFalse(state.data.loading)
        assertTrue(state.data.unavailable)
        assertEquals(setOf(PoiCategory.FUEL), state.data.items.keys)
    }

    @Test
    fun `sijainnin luvun virhe on sama kuin ei sijaintia`() = runTest {
        val session = NearbySession(backgroundScope, locate = { error("ei lupaa") }, load = { _, _ -> error("ei latausta") })

        session.open(PoiCategory.FUEL)
        runCurrent()

        assertEquals(NearbySessionState.NoLocation, session.state.value)
    }
}
