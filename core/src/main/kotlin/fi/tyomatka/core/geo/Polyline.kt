package fi.tyomatka.core.geo

import fi.tyomatka.core.model.GeoPoint

/** Googlen encoded polyline -muodon purku (Routes API:n vastauksista). */
object Polyline {

    /** [factor]: OSRM ja Google käyttävät viittä desimaalia, Valhallan oma muoto kuutta. */
    fun decode(encoded: String, factor: Double = 1e5): List<GeoPoint> {
        val points = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(GeoPoint(lat / factor, lng / factor))
        }
        return points
    }
}
