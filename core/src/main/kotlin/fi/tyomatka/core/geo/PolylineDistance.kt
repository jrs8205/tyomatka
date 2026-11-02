package fi.tyomatka.core.geo

import fi.tyomatka.core.model.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

private const val KM_PER_DEG = 111.195

/**
 * Pisteen etäisyys murtoviivaan kilometreinä: tasoprojektio pisteen leveyspiirillä ja lähin piste janalla. Riittää
 * "reitin varrella" -suodattimen kilometriluokan rajalle. Tyhjä viiva on äärettömän kaukana.
 */
fun distanceToPolylineKm(point: GeoPoint, line: List<GeoPoint>): Double {
    if (line.isEmpty()) return Double.POSITIVE_INFINITY
    val lonScale = cos(Math.toRadians(point.lat))
    fun x(p: GeoPoint) = (p.lng - point.lng) * lonScale * KM_PER_DEG
    fun y(p: GeoPoint) = (p.lat - point.lat) * KM_PER_DEG
    var best = Double.POSITIVE_INFINITY
    for (i in line.indices) {
        val ax = x(line[i])
        val ay = y(line[i])
        val d = if (i + 1 < line.size) {
            val bx = x(line[i + 1])
            val by = y(line[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq == 0.0) 0.0 else ((-ax * dx - ay * dy) / lengthSq).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            sqrt(px * px + py * py)
        } else {
            sqrt(ax * ax + ay * ay)
        }
        if (d < best) best = d
    }
    return best
}
