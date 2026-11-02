package fi.tyomatka.core.poi

import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Kohdelistan palat puhelimen ympäriltä: ruudukon solut, jotka osuvat ympyrään, katkenneen solun alisolut ja
 * ilmoitusten haun ikkunat. Koordinaatit ovat ruudukon viivoilla, joten palan välimuistiavain ([PoiGrid.cacheKey]) on
 * sama kuin kartalla samalla ikkunalla.
 */
object NearbyCells {
    /**
     * Pala osuu ympyrään, kun sen lähin piste (leveys ja pituus erikseen rajattuina palaan) on enintään säde + vara.
     * Erikseen rajattu piste ei ole pallolla aivan lähin (pohjoisessa ero on metrejä), joten ilman varaa palan reunan
     * kohde jäisi pois. Ylimääräiset kohteet rajataan lopuksi säteeseen.
     */
    const val MARGIN_KM = 1.0

    private const val EARTH_RADIUS_KM = 6371.0

    /** Rajaavan suorakulmion pyöristysvara asteina. */
    private const val ROUNDING_DEG = 0.001

    /** Ilmoitusikkunan enimmäisleveys alisolun askelina: 39 × 0,05° = 1,95°, jottei liukulukuero ylitä palvelimen 2°:ta. */
    private const val MAX_WINDOW_STEPS = 39

    private const val EPSILON = 1e-6

    /** [step]-ruudukon solut (oletus 0,25°), jotka osuvat [radiusKm]:n ympyrään; etelästä pohjoiseen, lännestä itään. */
    fun cells(center: GeoPoint, radiusKm: Double, step: Double = PoiGrid.CELL_DEG): List<PoiBox> =
        gridCells(boundingBox(center, radiusKm + MARGIN_KM), step).filter { touches(it, center, radiusKm) }

    /** Solun 0,05°:n alisolut, jotka osuvat ympyrään (enintään 25). */
    fun subcells(cell: PoiBox, center: GeoPoint, radiusKm: Double): List<PoiBox> =
        gridCells(cell, PoiGrid.FINE_CELL_DEG).filter { touches(it, center, radiusKm) }

    /**
     * Ilmoitusten haun ikkunat: ympyrän tarkka rajaava suorakulmio ilman varaa (kohteet rajataan säteeseen), ulospäin
     * 0,05°:n ruudukkoon ja jaettuna tasaisesti alle 2°:n osiin, koska palvelin ei hyväksy suurempaa ikkunaa.
     */
    fun windows(center: GeoPoint, radiusKm: Double): List<PoiBox> {
        val area = boundingBox(center, radiusKm)
        val step = PoiGrid.FINE_CELL_DEG
        val lons = split(down(area.minLon, step), up(area.maxLon, step))
        val lats = split(down(area.minLat, step), up(area.maxLat, step))
        return lats.flatMap { (south, north) ->
            lons.map { (west, east) -> PoiBox(coord(west, step), coord(south, step), coord(east, step), coord(north, step)) }
        }
    }

    private fun touches(box: PoiBox, center: GeoPoint, radiusKm: Double): Boolean {
        val nearest = GeoPoint(center.lat.coerceIn(box.minLat, box.maxLat), center.lng.coerceIn(box.minLon, box.maxLon))
        return distanceKm(center, nearest) <= radiusKm + MARGIN_KM
    }

    /** Ympyrän rajaava suorakulmio: leveys ± r/R, pituus ± asin(sin(r/R) / cos φ), pyöristysvaran kanssa. */
    private fun boundingBox(center: GeoPoint, radiusKm: Double): PoiBox {
        val angle = radiusKm / EARTH_RADIUS_KM
        val dLat = Math.toDegrees(angle) + ROUNDING_DEG
        val ratio = sin(angle) / cos(Math.toRadians(center.lat))
        val dLon = if (ratio >= 1.0 || ratio < 0.0) 180.0 else Math.toDegrees(asin(ratio)) + ROUNDING_DEG
        return PoiBox(
            minLon = (center.lng - dLon).coerceAtLeast(-180.0),
            minLat = (center.lat - dLat).coerceAtLeast(-90.0),
            maxLon = (center.lng + dLon).coerceAtMost(180.0),
            maxLat = (center.lat + dLat).coerceAtMost(90.0),
        )
    }

    /** Kaikki [step]-ruudukon solut, jotka leikkaavat aluetta; koordinaatit kokonaislukuindekseistä. */
    private fun gridCells(area: PoiBox, step: Double): List<PoiBox> {
        val lonRange = down(area.minLon, step) until up(area.maxLon, step)
        val latRange = down(area.minLat, step) until up(area.maxLat, step)
        return latRange.flatMap { row ->
            lonRange.map { col -> PoiBox(coord(col, step), coord(row, step), coord(col + 1, step), coord(row + 1, step)) }
        }
    }

    // Sadasosina ja pienellä toleranssilla kuten PoiGrid.snap: 24.35 / 0.05 on liukulukuna 486,999…
    private fun hundredths(step: Double) = (step * 100).roundToInt()

    private fun down(value: Double, step: Double) = floor(value * 100 / hundredths(step) + EPSILON).toInt()

    private fun up(value: Double, step: Double) = ceil(value * 100 / hundredths(step) - EPSILON).toInt()

    private fun coord(index: Int, step: Double) = index * hundredths(step) / 100.0

    /** Väli [from, to) tasaisiin osiin, joista jokainen on enintään [MAX_WINDOW_STEPS] askelta. */
    private fun split(from: Int, to: Int): List<Pair<Int, Int>> {
        val steps = (to - from).coerceAtLeast(1)
        val count = (steps + MAX_WINDOW_STEPS - 1) / MAX_WINDOW_STEPS
        val size = (steps + count - 1) / count
        return (0 until count).map { i -> from + i * size to minOf(from + (i + 1) * size, from + steps) }
    }
}
