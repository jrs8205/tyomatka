package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoiQueryGateTest {
    private val box = PoiBox(24.75, 60.25, 25.0, 60.5)
    private val cats = setOf(PoiCategory.FUEL)
    private val query = PoiQuery(box, cats)

    @Test
    fun `uusi ikkuna tai luokat haetaan heti`() {
        val gate = PoiQueryGate()
        assertTrue(gate.shouldLoad(query, nowSec = 1_000))
        gate.loaded(query, nowSec = 1_000, failed = false)
        assertTrue(gate.shouldLoad(PoiQuery(box, setOf(PoiCategory.REST)), nowSec = 1_001))
        assertTrue(gate.shouldLoad(PoiQuery(box.copy(maxLon = 25.25), cats), nowSec = 1_001))
    }

    @Test
    fun `lähennys tai siirto jo haetun katkaisemattoman ikkunan sisällä ei hae uudelleen`() {
        val gate = PoiQueryGate()
        gate.loaded(query, nowSec = 1_000, failed = false, truncated = false)
        assertFalse(gate.shouldLoad(PoiQuery(PoiBox(24.85, 60.30, 24.95, 60.40), cats), nowSec = 1_001))
        assertTrue(gate.shouldLoad(PoiQuery(PoiBox(24.95, 60.30, 25.05, 60.40), cats), nowSec = 1_001))
        assertTrue(gate.shouldLoad(PoiQuery(PoiBox(24.85, 60.30, 24.95, 60.40), setOf(PoiCategory.FUEL, PoiCategory.REST)), nowSec = 1_001))
    }

    @Test
    fun `katkaistun tai epäonnistuneen ikkunan sisältä haetaan tarkemmin`() {
        val inner = PoiQuery(PoiBox(24.85, 60.30, 24.95, 60.40), cats)
        val truncated = PoiQueryGate().apply { loaded(query, nowSec = 1_000, failed = false, truncated = true) }
        assertTrue(truncated.shouldLoad(inner, nowSec = 1_001))
        val failed = PoiQueryGate().apply { loaded(query, nowSec = 1_000, failed = true) }
        assertTrue(failed.shouldLoad(inner, nowSec = 1_001))
    }

    @Test
    fun `sama onnistunut haku ei toistu`() {
        val gate = PoiQueryGate()
        gate.loaded(query, nowSec = 1_000, failed = false)
        assertFalse(gate.shouldLoad(query, nowSec = 1_001))
        assertFalse(gate.shouldLoad(query, nowSec = 5_000))
    }

    @Test
    fun `epäonnistunut haku toistuu vasta minuutin kuluttua`() {
        val gate = PoiQueryGate()
        gate.loaded(query, nowSec = 1_000, failed = true)
        assertFalse(gate.shouldLoad(query, nowSec = 1_010))
        assertFalse(gate.shouldLoad(query, nowSec = 1_059))
        assertTrue(gate.shouldLoad(query, nowSec = 1_060))
    }

    @Test
    fun `nollaus unohtaa edellisen haun`() {
        val gate = PoiQueryGate()
        gate.loaded(query, nowSec = 1_000, failed = false)
        gate.reset()
        assertTrue(gate.shouldLoad(query, nowSec = 1_001))
    }
}
