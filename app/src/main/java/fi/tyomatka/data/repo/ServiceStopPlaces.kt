package fi.tyomatka.data.repo

import fi.tyomatka.core.api.ReverseGeocoder
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.plan.ServiceStops
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiServices
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Karttakohteesta stopin tiedot: tunnus `service:osm:<id>`, nimi kohteesta (varalla luokan nimi), osoite OSM:stä tai
 * lähin käänteishaun osoite 300 metrin säteeltä kahden sekunnin aikarajalla, muuten koordinaatit; sijainti on kohteen oma.
 */
class ServiceStopPlaces(private val reverse: ReverseGeocoder) {
    suspend fun place(item: PoiItem, fallbackName: String): PlaceDetails {
        val address = item.address
            ?: runCatching {
                withTimeoutOrNull(REVERSE_TIMEOUT_MS) { reverse.reverse(item.location, MAX_ADDRESS_M) }?.address?.takeIf { it.isNotBlank() }
            }.getOrNull()
            ?: String.format(Locale.ROOT, "%.4f, %.4f", item.location.lat, item.location.lng)
        return PlaceDetails(
            placeId = ServiceStops.placeId(item.id),
            name = PoiServices.displayName(item) ?: fallbackName,
            address = address,
            location = item.location,
        )
    }

    private companion object {
        /** Sama säde kuin yrityksen stopin käänteishaussa ([fi.tyomatka.core.api.BusinessPlaceResolver]). */
        const val MAX_ADDRESS_M = 300.0
        /** Ajossa lisäys ei saa odottaa hidasta verkkoa: koordinaatit kelpaavat osoitteeksi. */
        const val REVERSE_TIMEOUT_MS = 2_000L
    }
}
