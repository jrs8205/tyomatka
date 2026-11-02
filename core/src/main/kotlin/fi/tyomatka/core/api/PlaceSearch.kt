package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.model.PlaceSuggestion

/** Osoitehaku: ehdotukset kirjoitettaessa, paikan tiedot ja vapaan osoiterivin geokoodaus. */
interface PlaceSearch {
    /**
     * Osoite-ehdotukset kirjoitetulle tekstille. [biasLocation] painottaa
     * tuloksia kuljettajan nykyisen sijainnin lähelle.
     */
    suspend fun autocomplete(input: String, biasLocation: GeoPoint? = null): List<PlaceSuggestion>

    /** Paikan koordinaatit ja osoite ehdotuksen tunnisteella. */
    suspend fun placeDetails(placeId: String): PlaceDetails

    /**
     * Geokoodaa vapaamuotoisen osoiterivin (esim. liitetystä listasta) parhaaseen
     * osumaan; null jos osumaa ei löydy. [biasLocation] painottaa lähimpiä osumia.
     */
    suspend fun geocodeText(addressLine: String, biasLocation: GeoPoint? = null): PlaceDetails?
}
