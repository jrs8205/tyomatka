package fi.tyomatka.ui.poi

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.ui.Tap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * Kohdekerros kartalle: luokkaa kohti oma lähde, värillinen ympyrä valkoisella reunuksella ja valkoinen kuvake.
 * Himmennetyt merkit (suljettu asema, Teboil) omana kerroksenaan peittävyydellä [DIMMED_OPACITY] tavallisten alla.
 * Lisätään ennen reitti- ja stoppikerroksia, jotta kohteet jäävät niiden alle. Napautus antaa kohteen tunnuksen.
 */
@Composable
@MaplibreComposable
fun PoiLayers(markers: List<PoiMarker>, onClick: (String) -> Unit) {
    val click: FeaturesClickHandler = { features ->
        features.firstNotNullOfOrNull { it.properties?.get("id")?.jsonPrimitive?.contentOrNull }?.let { id ->
            Tap.now()
            onClick(id)
        }
        ClickResult.Consume
    }
    listOf(true, false).forEach { dimmed ->
        PoiCategory.entries.forEach { category ->
            val painter = rememberVectorPainter(PoiCardText.categoryIcon(category))
            val items = markers.filter { it.dimmed == dimmed && it.item.category == category }.map { it.item }
            val source = rememberGeoJsonSource(GeoJsonData.Features(features(items)))
            val id = "poi-${category.apiName}" + if (dimmed) "-dim" else ""
            val opacity = if (dimmed) DIMMED_OPACITY else 1f
            CircleLayer(
                id = id,
                source = source,
                color = const(PoiCardText.categoryColor(category)),
                opacity = const(opacity),
                radius = const(12.dp),
                strokeColor = const(Color.White),
                strokeOpacity = const(opacity),
                strokeWidth = const(2.dp),
                onClick = click,
            )
            SymbolLayer(
                id = "$id-icon",
                source = source,
                // Material-kuvakkeet ovat mustia: kartalla ne piirretään valkoisina värillisen ympyrän päälle.
                iconImage = image(painter, DpSize(16.dp, 16.dp), colorFilter = ColorFilter.tint(Color.White)),
                iconOpacity = const(opacity),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
                onClick = click,
            )
        }
    }
}

/** Himmennetyn merkin peittävyys. */
const val DIMMED_OPACITY = 0.45f

private fun features(items: List<PoiItem>): FeatureCollection<Point, JsonObject> =
    FeatureCollection(
        items.map { item ->
            Feature(
                geometry = Point(Position(item.location.lng, item.location.lat)),
                properties = buildJsonObject { put("id", item.id) },
            )
        },
    )
