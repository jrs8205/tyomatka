package fi.tyomatka.data.repo

import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.NearbyCells
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiPart
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationReport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NearbyRepositoryTest {
    private val center = GeoPoint(60.2748, 24.8384)

    /** Kohde [km]:n päässä pohjoiseen keskipisteestä. */
    private fun item(id: String, category: PoiCategory, km: Double = 5.0) =
        PoiItem(id, category, GeoPoint(center.lat + km / 111.195, center.lng), id, null, null, false, emptyMap())

    /** Funktiofeikit: kirjaavat kutsut ja vastaavat annetuilla säännöillä. */
    private class Fakes {
        val calls = mutableListOf<List<PoiPart>>()
        var answer: (PoiPart) -> PoiLoad = { PoiLoad(emptyList()) }
        val cachedCalls = mutableListOf<Pair<PoiBox, PoiCategory>>()
        var cached: (PoiCategory) -> CachedNear = { CachedNear(emptyList(), emptyMap()) }
        val remoteCalls = mutableListOf<PoiBox>()
        var remote: (PoiBox) -> RemoteReports = { RemoteReports(emptyList(), null, fresh = true) }

        suspend fun loadMany(parts: List<PoiPart>): Map<PoiPart, PoiLoad> {
            calls += parts
            return parts.associateWith(answer)
        }

        suspend fun cachedNear(area: PoiBox, category: PoiCategory): CachedNear {
            cachedCalls += area to category
            return cached(category)
        }

        suspend fun remote(box: PoiBox): RemoteReports {
            remoteCalls += box
            return remote.invoke(box)
        }
    }

    private fun repository(f: Fakes) = NearbyRepository(f::loadMany, f::cachedNear, f::remote)

    private val cells = NearbyCells.cells(center, NEARBY_RADIUS_KM)

    @Test
    fun `valittu laji ensin, sitten muut lajit, ja viimeinen tila ei lataa`() = runTest {
        val f = Fakes().apply { answer = { PoiLoad(listOf(item("${it.category.apiName}-1", it.category))) } }

        val emits = repository(f).load(center, PoiCategory.GAS).toList()

        assertEquals(setOf(PoiCategory.GAS), emits[0].items.keys)
        assertTrue(emits[0].loading)
        assertEquals(PoiCategory.entries.toSet(), emits[1].items.keys)
        assertFalse(emits.last().loading)
        assertTrue(emits.dropLast(1).all { it.loading })
        assertEquals(cells.map { PoiPart(it, PoiCategory.GAS) }, f.calls[0])
        assertEquals(PoiCategory.entries.filter { it != PoiCategory.GAS }.toSet(), f.calls[1].map { it.category }.toSet())
        assertEquals(cells.size * 3, f.calls[1].size)
        assertEquals(2, f.calls.size)
        assertEquals(listOf("gas-1"), emits.last().items.getValue(PoiCategory.GAS).map { it.id })
    }

    @Test
    fun `katkenneesta palasta haetaan vain sen ympyrään osuvat alisolut`() = runTest {
        val edge = PoiBox(25.5, 60.5, 25.75, 60.75)
        val f = Fakes().apply { answer = { PoiLoad(emptyList(), truncated = it == PoiPart(edge, PoiCategory.CHARGING)) } }

        val emits = repository(f).load(center, PoiCategory.FUEL).toList()
        val last = emits.last()

        // Alisolujen haun ajan lataus jatkuu.
        assertTrue(emits.dropLast(1).all { it.loading })
        assertEquals(3, f.calls.size)
        assertEquals(NearbyCells.subcells(edge, center, NEARBY_RADIUS_KM).map { PoiPart(it, PoiCategory.CHARGING) }, f.calls[2])
        assertTrue(f.calls[2].size in 1 until 25)
        assertEquals(emptySet(), last.truncated)
        assertFalse(last.loading)
    }

    @Test
    fun `alisolu katkeaa edelleen, laji on katkaistu`() = runTest {
        val edge = PoiBox(25.5, 60.5, 25.75, 60.75)
        val f = Fakes().apply {
            answer = { PoiLoad(emptyList(), truncated = it.category == PoiCategory.CHARGING && (it.box == edge || it.box.maxLon - it.box.minLon < 0.1)) }
        }

        assertEquals(setOf(PoiCategory.CHARGING), repository(f).load(center, PoiCategory.FUEL).toList().last().truncated)
    }

    @Test
    fun `sama kohde kahdessa palassa tulee kerran`() = runTest {
        val f = Fakes().apply { answer = { PoiLoad(listOf(item("n1", it.category))) } }

        val last = repository(f).load(center, PoiCategory.FUEL).toList().last()

        assertEquals(listOf("n1"), last.items.getValue(PoiCategory.FUEL).map { it.id })
    }

    @Test
    fun `toisen haun verkkokatko, muut lajit välimuistista ja lataus päättyy`() = runTest {
        val f = Fakes().apply {
            answer = { part ->
                if (part.category == PoiCategory.FUEL) PoiLoad(listOf(item("n1", part.category))) else PoiLoad(emptyList(), offline = true)
            }
            cached = { category -> CachedNear(listOf(item("c-${category.apiName}", category)), mapOf("c-${category.apiName}" to 3)) }
        }

        val last = repository(f).load(center, PoiCategory.FUEL).toList().last()

        assertTrue(last.offline)
        assertFalse(last.unavailable)
        assertFalse(last.loading)
        assertEquals(3, last.cacheAgeDays)
        assertEquals(listOf("n1"), last.items.getValue(PoiCategory.FUEL).map { it.id })
        assertEquals(listOf("c-gas"), last.items.getValue(PoiCategory.GAS).map { it.id })
        assertEquals(setOf(PoiCategory.GAS, PoiCategory.REST, PoiCategory.CHARGING), f.cachedCalls.map { it.second }.toSet())
        // Välimuistihaun alue kattaa koko ympyrän.
        f.cachedCalls.forEach { (area, _) ->
            assertTrue(area.minLat <= center.lat - 0.449 && area.maxLat >= center.lat + 0.449, "$area")
            assertTrue(area.minLon <= center.lng - 0.906 && area.maxLon >= center.lng + 0.906, "$area")
        }
    }

    @Test
    fun `tuore pala voittaa välimuistihaun ja välimuistihaku vanhan palan`() = runTest {
        val (first, second) = cells
        val f = Fakes().apply {
            answer = { part ->
                when {
                    part == PoiPart(first, PoiCategory.FUEL) -> PoiLoad(listOf(item("s1", part.category).copy(name = "Vanha 20 pv")), cacheAgeDays = 20, offline = true)
                    part == PoiPart(second, PoiCategory.FUEL) -> PoiLoad(listOf(item("s3", part.category).copy(name = "Verkko")))
                    else -> PoiLoad(emptyList())
                }
            }
            cached = { category ->
                if (category != PoiCategory.FUEL) CachedNear(emptyList(), emptyMap()) else CachedNear(
                    listOf(item("s1", category).copy(name = "Uusi 1 pv"), item("s3", category).copy(name = "Välimuisti")),
                    mapOf("s1" to 1, "s3" to 4),
                )
            }
        }

        val last = repository(f).load(center, PoiCategory.FUEL).toList().last()

        assertEquals(listOf("s1" to "Uusi 1 pv", "s3" to "Verkko"), last.items.getValue(PoiCategory.FUEL).map { it.id to it.name }.sortedBy { it.first })
        // Ikä näkyvistä kohteista: s1 välimuistihausta (1 pv); verkosta tullut s3 ei ole vanha.
        assertEquals(1, last.cacheAgeDays)
    }

    @Test
    fun `välimuistin ikä vain näkyvistä kohteista`() = runTest {
        val f = Fakes().apply {
            answer = { PoiLoad(emptyList(), offline = true) }
            cached = { category ->
                CachedNear(listOf(item("lähellä-${category.apiName}", category), item("kaukana-${category.apiName}", category, km = 60.0)),
                    mapOf("lähellä-${category.apiName}" to 1, "kaukana-${category.apiName}" to 29))
            }
        }

        assertEquals(1, repository(f).load(center, PoiCategory.FUEL).toList().last().cacheAgeDays)
    }

    @Test
    fun `odottamaton virhe ilmoitushaussa tai latauksessa ei jätä latausta päälle`() = runTest {
        val north = GeoPoint(69.9, 27.0)
        val windows = NearbyCells.windows(north, NEARBY_RADIUS_KM)
        val f = Fakes().apply {
            remote = { box ->
                if (box == windows[0]) error("kanta rikki")
                RemoteReports(listOf(StationReport("n-toinen", ReportField.CLOSED, "yes", 20_750)), version = 1, fresh = true)
            }
        }
        val last = repository(f).load(north, PoiCategory.FUEL).toList().last()
        assertFalse(last.loading)
        assertEquals(listOf("n-toinen"), last.reports.map { it.poiId })

        var calls = 0
        val failing = NearbyRepository(
            loadMany = { parts -> if (++calls == 2) error("kanta rikki") else parts.associateWith { PoiLoad(listOf(item("n1", it.category))) } },
            cachedNear = { _, _ -> CachedNear(emptyList(), emptyMap()) },
            remote = { RemoteReports(emptyList(), null, fresh = true) },
        )
        val end = failing.load(center, PoiCategory.FUEL).toList().last()
        assertFalse(end.loading)
        assertTrue(end.unavailable)
        assertEquals(listOf("n1"), end.items.getValue(PoiCategory.FUEL).map { it.id })
    }

    @Test
    fun `palvelin ei saatavilla kerrotaan erikseen`() = runTest {
        val f = Fakes().apply { answer = { PoiLoad(emptyList(), unavailable = true) } }

        val last = repository(f).load(center, PoiCategory.FUEL).toList().last()

        assertTrue(last.unavailable)
        assertFalse(last.offline)
    }

    @Test
    fun `yli 50 kmn kohde jää pois, 50 kmn sisällä oleva näkyy`() = runTest {
        val f = Fakes().apply { answer = { PoiLoad(listOf(item("lähellä", it.category, km = 49.9), item("kaukana", it.category, km = 50.5))) } }

        val last = repository(f).load(center, PoiCategory.FUEL).toList().last()

        assertTrue(distanceKm(center, item("x", PoiCategory.FUEL, km = 50.5).location) > 50.0)
        assertEquals(listOf("lähellä"), last.items.getValue(PoiCategory.FUEL).map { it.id })
    }

    @Test
    fun `kahden ilmoitusikkunan tulokset tulevat molemmat mukaan`() = runTest {
        val north = GeoPoint(69.9, 27.0)
        val f = Fakes().apply {
            remote = { box -> RemoteReports(listOf(StationReport("n-${box.minLon}", ReportField.CLOSED, "yes", 20_750)), version = 1, fresh = true) }
        }

        val last = repository(f).load(north, PoiCategory.FUEL).toList().last()

        val windows = NearbyCells.windows(north, NEARBY_RADIUS_KM)
        assertEquals(2, windows.size)
        assertEquals(windows, f.remoteCalls)
        assertEquals(windows.map { "n-${it.minLon}" }.toSet(), last.reports.map { it.poiId }.toSet())
    }

    @Test
    fun `verkkokatkossa kartan uudempi välimuistitieto voittaa listan vanhemman tuoreen palan`() = runTest {
        val (first, second) = cells
        val f = Fakes().apply {
            answer = { part ->
                when (part) {
                    PoiPart(first, PoiCategory.FUEL) -> PoiLoad(listOf(item("s1", part.category).copy(name = "Lista 23 h")), freshness = PoiFreshness(0, 1_000L))
                    PoiPart(second, PoiCategory.FUEL) -> PoiLoad(emptyList(), offline = true)
                    else -> PoiLoad(emptyList())
                }
            }
            cached = { category ->
                if (category != PoiCategory.FUEL) {
                    CachedNear(emptyList(), emptyMap())
                } else {
                    CachedNear(listOf(item("s1", category).copy(name = "Kartta 1 h")), mapOf("s1" to 0), fetched = mapOf("s1" to PoiFreshness(0, 80_000L)))
                }
            }
        }

        val last = repository(f).load(center, PoiCategory.FUEL).toList().last()

        assertEquals(listOf("Kartta 1 h"), last.items.getValue(PoiCategory.FUEL).map { it.name })
    }

    @Test
    fun `tämän käynnistyksen verkkotieto voittaa välimuistihaun rivin, vaikka sen kellonaika on myöhempi`() = runTest {
        val (first, second) = cells
        val f = Fakes().apply {
            answer = { part ->
                when (part) {
                    PoiPart(first, PoiCategory.FUEL) -> PoiLoad(listOf(item("s1", part.category).copy(name = "Verkko")), freshness = PoiFreshness(1, 1_000L))
                    PoiPart(second, PoiCategory.FUEL) -> PoiLoad(emptyList(), unavailable = true)
                    else -> PoiLoad(emptyList())
                }
            }
            cached = { category ->
                if (category != PoiCategory.FUEL) {
                    CachedNear(emptyList(), emptyMap())
                } else {
                    CachedNear(listOf(item("s1", category).copy(name = "Vanha kartta")), mapOf("s1" to 0), fetched = mapOf("s1" to PoiFreshness(0, 1_004L)))
                }
            }
        }

        val last = repository(f).load(center, PoiCategory.FUEL).toList().last()

        assertEquals(listOf("Verkko"), last.items.getValue(PoiCategory.FUEL).map { it.name })
    }
}
