package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import kotlin.coroutines.cancellation.CancellationException

/**
 * Käänteishaku palvelu kerrallaan (Digitransit, sitten Suomeen rajattu varahaku): vain virhe siirtää haun seuraavalle.
 * Vastattu "ei osoitetta" (null) on lopullinen, koska varahaun talotason tulos voi olla kohde ilman talonumeroa, ja
 * stoppi saa silloin koordinaatit kuten ennenkin. Kun kukaan ei vastaa, viimeinen virhe nousee kutsujalle.
 */
class FallbackReverseGeocoder(private val geocoders: List<ReverseGeocoder>) : ReverseGeocoder {
    override suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails? {
        var failure: Exception? = null
        for (geocoder in geocoders) {
            try {
                return geocoder.reverse(point, maxDistanceM)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        if (failure != null) throw failure
        return null
    }
}
