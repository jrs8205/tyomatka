package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails

/** Koordinaateista lähin katuosoite; null, kun osoitetta ei ole [maxDistanceM]:n säteellä. */
interface ReverseGeocoder {
    suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails?
}
