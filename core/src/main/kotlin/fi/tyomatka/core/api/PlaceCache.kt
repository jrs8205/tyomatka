package fi.tyomatka.core.api

import fi.tyomatka.core.model.PlaceDetails

/**
 * Viimeisimpien hakujen paikat tunnisteen mukaan: hakupalvelut eivät tarjoa hakua
 * tunnisteella, joten [PlaceSearch.placeDetails] vastaa vain tuoreisiin ehdotuksiin.
 */
internal class PlaceCache(private val capacity: Int = 100) {
    private val map = object : LinkedHashMap<String, PlaceDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaceDetails>?) = size > capacity
    }

    fun putAll(places: List<PlaceDetails>) = synchronized(map) { places.forEach { map[it.placeId] = it } }

    fun get(placeId: String): PlaceDetails =
        synchronized(map) { map[placeId] } ?: throw StaleSuggestionException()
}
