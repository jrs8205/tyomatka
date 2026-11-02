package fi.tyomatka.ui.map

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.data.db.StopEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Kartan GeoJSON-geometria stoppilistasta — ei Compose- eikä MapLibre-riippuvuuksia, joten testattavissa JVM:ssä. */
object MapGeometry {

    /** Yhden pisteen ympärille jätetään ~200 m, ettei kamera zoomaa äärettömän lähelle. */
    private const val SINGLE_POINT_PAD_DEG = 0.002

    fun boundingBoxOf(points: List<GeoPoint>): BoundingBox? {
        if (points.isEmpty()) return null
        var west = points.minOf { it.lng }
        var east = points.maxOf { it.lng }
        var south = points.minOf { it.lat }
        var north = points.maxOf { it.lat }
        if (east - west < SINGLE_POINT_PAD_DEG) {
            west -= SINGLE_POINT_PAD_DEG
            east += SINGLE_POINT_PAD_DEG
        }
        if (north - south < SINGLE_POINT_PAD_DEG) {
            south -= SINGLE_POINT_PAD_DEG
            north += SINGLE_POINT_PAD_DEG
        }
        return BoundingBox(west, south, east, north)
    }

    fun stopFeatures(stops: List<StopEntity>): FeatureCollection<Point, JsonObject> =
        FeatureCollection(
            stops.mapIndexed { index, stop ->
                Feature(
                    geometry = Point(Position(stop.lng, stop.lat)),
                    properties = buildJsonObject {
                        put("n", (index + 1).toString())
                        put("label", stop.label)
                    },
                )
            }
        )

    fun originFeature(origin: GeoPoint?): FeatureCollection<Point, JsonObject> =
        FeatureCollection(
            listOfNotNull(origin?.let { Feature(Point(Position(it.lng, it.lat)), buildJsonObject {}) })
        )

    fun routeFeature(points: List<GeoPoint>): FeatureCollection<LineString, JsonObject> =
        if (points.size < 2) {
            FeatureCollection(emptyList())
        } else {
            FeatureCollection(listOf(Feature(LineString(points.map { Position(it.lng, it.lat) }), buildJsonObject {})))
        }
}
