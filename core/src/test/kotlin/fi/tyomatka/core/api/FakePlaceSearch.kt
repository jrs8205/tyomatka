package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.model.PlaceSuggestion

internal fun suggestion(id: String) = PlaceSuggestion(id, id, "")
internal fun details(id: String) = PlaceDetails(id, id, id, GeoPoint(0.0, 0.0))

/** Muistinvarainen haku: palauttaa annetut osumat ja kirjaa kutsut. */
internal class FakeSearch(
    private val suggestions: List<PlaceSuggestion> = emptyList(),
    private val geocoded: PlaceDetails? = null,
    private val failure: Exception? = null,
) : PlaceSearch {
    val queries = mutableListOf<String>()

    override suspend fun autocomplete(input: String, biasLocation: GeoPoint?): List<PlaceSuggestion> {
        queries += input
        failure?.let { throw it }
        return suggestions
    }

    override suspend fun placeDetails(placeId: String): PlaceDetails =
        listOfNotNull(geocoded).firstOrNull { it.placeId == placeId }
            ?: suggestions.firstOrNull { it.placeId == placeId }?.let { details(it.placeId) }
            ?: throw ApiException("tuntematon $placeId")

    override suspend fun geocodeText(addressLine: String, biasLocation: GeoPoint?): PlaceDetails? {
        queries += addressLine
        failure?.let { throw it }
        return geocoded
    }
}
