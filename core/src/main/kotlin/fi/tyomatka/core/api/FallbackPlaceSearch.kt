package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.model.PlaceSuggestion
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kysyy palveluita järjestyksessä, kunnes jokin löytää osumia: tyhjä tulos tai virhe
 * (esim. täysi päiväkiintiö) siirtää haun seuraavalle. Jos kaikki epäonnistuvat,
 * viimeisen palvelun virhe nousee kutsujalle.
 */
class FallbackPlaceSearch(private val searches: List<PlaceSearch>) : PlaceSearch {

    override suspend fun autocomplete(input: String, biasLocation: GeoPoint?): List<PlaceSuggestion> =
        firstFound(emptyList(), { it.isNotEmpty() }) { it.autocomplete(input, biasLocation) }

    override suspend fun placeDetails(placeId: String): PlaceDetails {
        var last: Exception? = null
        for (search in searches) {
            try {
                return search.placeDetails(placeId)
            } catch (e: ApiException) {
                last = e
            }
        }
        throw last ?: StaleSuggestionException()
    }

    override suspend fun geocodeText(addressLine: String, biasLocation: GeoPoint?): PlaceDetails? =
        firstFound(null, { it != null }) { it.geocodeText(addressLine, biasLocation) }

    private suspend fun <T> firstFound(none: T, found: (T) -> Boolean, query: suspend (PlaceSearch) -> T): T {
        var failure: Exception? = null
        var anyAnswered = false
        for (search in searches) {
            try {
                val result = query(search)
                if (found(result)) return result
                anyAnswered = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        if (!anyAnswered && failure != null) throw failure
        return none
    }
}

/** Näkyvien ehdotusten lähdemaininnat yhdellä rivillä, kukin kerran; null jos ei mainittavaa. */
fun List<PlaceSuggestion>.sources(): String? =
    flatMap { it.source.split(" · ") }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        .joinToString(" · ").ifBlank { null }
