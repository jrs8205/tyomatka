package fi.tyomatka.core.poi

import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NearbyCellsTest {
    private val martinlaakso = GeoPoint(60.2748, 24.8384)

    /** Palan lähin piste: leveys ja pituus rajattuna palaan. */
    private fun clampedKm(box: PoiBox, center: GeoPoint) =
        distanceKm(center, GeoPoint(center.lat.coerceIn(box.minLat, box.maxLat), center.lng.coerceIn(box.minLon, box.maxLon)))

    private fun onGrid(value: Double, step: Double) = abs(value / step - round(value / step)) < 1e-9

    private fun assertOnGrid(box: PoiBox, step: Double) {
        listOf(box.minLon, box.minLat, box.maxLon, box.maxLat).forEach { assertTrue(onGrid(it, step), "$it ei ole $step°:n viivalla: $box") }
        assertEquals(step, box.maxLon - box.minLon, 1e-9)
        assertEquals(step, box.maxLat - box.minLat, 1e-9)
    }

    @Test
    fun `ympyrän palat ovat ruudukon soluja, jotka osuvat ympyrään varan kanssa`() {
        val cells = NearbyCells.cells(martinlaakso, 50.0)
        assertTrue(cells.size in 20..40, "palat: ${cells.size}")
        assertEquals(cells.size, cells.toSet().size)
        cells.forEach { cell ->
            assertOnGrid(cell, PoiGrid.CELL_DEG)
            assertTrue(clampedKm(cell, martinlaakso) <= 50.0 + NearbyCells.MARGIN_KM, "$cell")
        }
        assertFalse(PoiBox(23.75, 59.75, 24.0, 60.0) in cells)
        // Jokainen ympyrään osuva solu on mukana.
        for (row in 59 * 4 until 62 * 4) for (col in 22 * 4 until 28 * 4) {
            val cell = PoiBox(col / 4.0, row / 4.0, (col + 1) / 4.0, (row + 1) / 4.0)
            if (clampedKm(cell, martinlaakso) <= 50.0) assertTrue(cell in cells, "puuttuu $cell")
        }
        assertEquals("24.75,60.25,25.00,60.50|fuel", PoiGrid.cacheKey(cells.first { it.minLon == 24.75 && it.minLat == 60.25 }, PoiCategory.FUEL))
    }

    @Test
    fun `pohjoisessa palan reunan kohde ei jää pois, vaikka rajattu piste on hieman yli säteen`() {
        val center = GeoPoint(69.9, 27.191474691)
        val cell = PoiBox(28.5, 69.75, 28.75, 70.0)
        // Lähtöoletus: kohde on säteen sisällä, mutta erikseen rajattu piste ei.
        assertTrue(distanceKm(center, GeoPoint(69.904822185, 28.50001)) < 50.0)
        assertTrue(clampedKm(cell, center) > 50.0)
        assertTrue(cell in NearbyCells.cells(center, 50.0))
    }

    @Test
    fun `alisolut ovat solun sisällä 0,05 asteen viivoilla ja osuvat ympyrään`() {
        val inside = PoiBox(24.75, 60.25, 25.0, 60.5)
        val all = NearbyCells.subcells(inside, martinlaakso, 50.0)
        assertEquals(25, all.size)
        assertEquals(25, all.toSet().size)
        val edge = PoiBox(25.5, 60.5, 25.75, 60.75)
        val some = NearbyCells.subcells(edge, martinlaakso, 50.0)
        assertTrue(some.isNotEmpty() && some.size < 25, "alisolut: ${some.size}")
        (all + some).forEach { assertOnGrid(it, PoiGrid.FINE_CELL_DEG) }
        all.forEach { assertTrue(it.minLon >= inside.minLon - 1e-9 && it.maxLon <= inside.maxLon + 1e-9 && it.minLat >= inside.minLat - 1e-9 && it.maxLat <= inside.maxLat + 1e-9) }
        some.forEach {
            assertTrue(it.minLon >= edge.minLon - 1e-9 && it.maxLon <= edge.maxLon + 1e-9 && it.minLat >= edge.minLat - 1e-9 && it.maxLat <= edge.maxLat + 1e-9)
            assertTrue(clampedKm(it, martinlaakso) <= 50.0 + NearbyCells.MARGIN_KM)
        }
        // Jokainen ympyrään osuva alisolu on mukana.
        for (row in 0 until 5) for (col in 0 until 5) {
            val sub = PoiBox(25.5 + col * 0.05, 60.5 + row * 0.05, 25.5 + (col + 1) * 0.05, 60.5 + (row + 1) * 0.05)
            if (clampedKm(sub, martinlaakso) <= 50.0) assertTrue(some.any { abs(it.minLon - sub.minLon) < 1e-9 && abs(it.minLat - sub.minLat) < 1e-9 }, "puuttuu $sub")
        }
    }

    @Test
    fun `ilmoitusten ikkunat pohjoisessa jaetaan enintään 2 asteen osiin`() {
        val center = GeoPoint(69.9, 27.0)
        val windows = NearbyCells.windows(center, 50.0)
        assertEquals(2, windows.size)
        windows.forEach {
            assertTrue(it.maxLon - it.minLon <= PoiGrid.MAX_SPAN_DEG, "$it")
            assertTrue(it.maxLat - it.minLat <= PoiGrid.MAX_SPAN_DEG, "$it")
        }
        val (west, east) = windows.sortedBy { it.minLon }
        assertEquals(west.maxLon, east.minLon, 1e-9)
        assertEquals(west.minLat, east.minLat, 1e-9)
        assertEquals(west.maxLat, east.maxLat, 1e-9)
        // Ympyrän rajaava suorakulmio: leveys ±0,4497°, pituus ±1,3085° (69,9° N).
        assertTrue(west.minLon <= 27.0 - 1.3085 && east.maxLon >= 27.0 + 1.3085, "$windows")
        assertTrue(west.minLat <= 69.9 - 0.4497 && west.maxLat >= 69.9 + 0.4497, "$windows")
    }

    /** Piste [km]:n päässä suuntimaan [bearingDeg] isoympyrää pitkin. */
    private fun destination(from: GeoPoint, km: Double, bearingDeg: Double): GeoPoint {
        val d = km / 6371.0
        val lat1 = Math.toRadians(from.lat)
        val theta = Math.toRadians(bearingDeg)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(theta))
        val lon2 = Math.toRadians(from.lng) + atan2(sin(theta) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun PoiBox.has(p: GeoPoint) = p.lat in minLat..maxLat && p.lng in minLon..maxLon

    @Test
    fun `jokainen ympyrän kehän piste on jonkin palan ja jonkin ikkunan sisällä`() {
        val centers = listOf(GeoPoint(55.0, 12.6), GeoPoint(62.0, 25.7), GeoPoint(69.9, 27.0), GeoPoint(71.0, 25.8), martinlaakso)
        for (center in centers) {
            val cells = NearbyCells.cells(center, 50.0)
            val windows = NearbyCells.windows(center, 50.0)
            val km = 50.0 * (1 - 1e-9)
            // Pituussuunnan ääripisteet (tangenttipisteet) ja kehä puolen asteen välein.
            val d = km / 6371.0
            val lat = Math.toRadians(center.lat)
            val tangentLat = Math.toDegrees(asin(sin(lat) / cos(d)))
            val tangentLon = Math.toDegrees(asin(sin(d) / cos(lat)))
            val points = List(720) { destination(center, km, it * 0.5) } +
                listOf(GeoPoint(tangentLat, center.lng + tangentLon), GeoPoint(tangentLat, center.lng - tangentLon))
            points.forEach { p ->
                assertTrue(cells.any { it.has(p) }, "$center: $p ei ole palassa")
                assertTrue(windows.any { it.has(p) }, "$center: $p ei ole ikkunassa")
            }
        }
    }

    @Test
    fun `ikkunan leveys on palvelimen laskennassa enintään 2 astetta`() {
        // Palvelin vähentää %.2f-merkkijonoista jäsennetyt luvut: 16.10 - 14.10 = 2,0000000000000018 > 2.
        fun served(value: Double) = String.format(Locale.ROOT, "%.2f", value).toDouble()
        assertEquals(2, NearbyCells.windows(GeoPoint(61.72, 15.10), 50.0).size)
        for (lat in 55..71) for (step in 0..1000) {
            NearbyCells.windows(GeoPoint(lat.toDouble(), -10.0 + step * 0.05), 50.0).forEach {
                assertTrue(served(it.maxLon) - served(it.minLon) <= PoiGrid.MAX_SPAN_DEG, "$lat: $it")
                assertTrue(served(it.maxLat) - served(it.minLat) <= PoiGrid.MAX_SPAN_DEG, "$lat: $it")
            }
        }
    }

    @Test
    fun `Greenwichin solun avain on sama kartalla ja listalla`() {
        val map = PoiGrid.snap(PoiBox(-0.20, 51.30, -0.05, 51.45), zoom = 11.0)!!
        val list = NearbyCells.cells(GeoPoint(51.4, -0.1), 50.0).single { it.minLon == -0.25 && it.minLat == 51.25 }

        assertEquals(PoiGrid.cacheKey(list, PoiCategory.FUEL), PoiGrid.cacheKey(map, PoiCategory.FUEL))
        assertEquals("-0.25,51.25,0.00,51.50|fuel", PoiGrid.cacheKey(map, PoiCategory.FUEL))
    }

    @Test
    fun `ilmoitusten ikkuna Martinlaaksossa on yksi`() {
        val windows = NearbyCells.windows(martinlaakso, 50.0)
        assertEquals(1, windows.size)
        val window = windows.single()
        assertTrue(window.minLon <= 24.8384 - 0.9069 && window.maxLon >= 24.8384 + 0.9069, "$window")
        assertTrue(window.minLat <= 60.2748 - 0.4497 && window.maxLat >= 60.2748 + 0.4497, "$window")
        assertTrue(window.maxLon - window.minLon <= PoiGrid.MAX_SPAN_DEG)
    }
}
