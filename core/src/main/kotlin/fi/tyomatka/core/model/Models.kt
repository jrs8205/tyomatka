package fi.tyomatka.core.model

/** Koordinaattipari ilman Android-riippuvuuksia. */
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * Suunniteltu stoppi optimointia ja ETA-laskentaa varten.
 *
 * @param id vakaa tunniste (Room-rivin id)
 * @param location sijainti
 * @param deadlineEpochSec "perillä viimeistään" -aikaraja epoch-sekunteina, tai null
 * @param serviceSec arvioitu stopilla vietettävä aika sekunteina (null = käytetään opittua oletusta)
 */
data class PlannedStop(
    val id: Long,
    val label: String,
    val location: GeoPoint,
    val deadlineEpochSec: Long? = null,
    val serviceSec: Long? = null,
)

/** Aikarajan tila liikennevaloväreille. */
enum class DeadlineStatus {
    /** Ei aikarajaa. */
    NONE,

    /** Ehtii hyvin (pelivaraa yli varoitusrajan). */
    OK,

    /** Ehtii, mutta pelivara on pieni. */
    TIGHT,

    /** Ennusteen mukaan myöhästyy. */
    LATE,
}

/** Yhden stopin ennuste. */
data class StopEta(
    val stop: PlannedStop,
    val arrivalEpochSec: Long,
    val departureEpochSec: Long,
    /** Pelivara aikarajaan sekunteina (negatiivinen = myöhässä), null jos ei aikarajaa. */
    val deadlineSlackSec: Long?,
    val deadlineStatus: DeadlineStatus,
)

/** Koko jäljellä olevan päivän ennuste. */
data class ScheduleSnapshot(
    val computedAtEpochSec: Long,
    val stopEtas: List<StopEta>,
    /** Aika, jolloin viimeinen stoppi on hoidettu (= päivä valmis). */
    val finishEpochSec: Long,
) {
    val hasLateDeadlines: Boolean get() = stopEtas.any { it.deadlineStatus == DeadlineStatus.LATE }
}

/** Historiasta opitut estimaatit, joilla ennusteita korjataan. */
data class LearnedStats(
    /**
     * Kerroin, jolla päivän suunnitelman (OSRM) legien ajat kerrotaan (1.0 = ajaa kuten ennuste). Opitaan
     * suunnitelman ennusteita vasten.
     */
    val speedFactor: Double = 1.0,
    /** Opittu keskimääräinen stoppikesto sekunteina. */
    val avgServiceSec: Long = DEFAULT_SERVICE_SEC,
    /** Näytteiden määrä (luotettavuuden arviointiin). */
    val serviceSamples: Int = 0,
    val legSamples: Int = 0,
    /**
     * Kerroin, jolla navigoinnin (Valhalla) jäljellä oleva aika kerrotaan. Opitaan Valhallan ennusteita vasten:
     * Valhalla ja OSRM arvioivat saman matkan eri pituiseksi, joten yhteinen kerroin vääristäisi toista.
     */
    val navSpeedFactor: Double = 1.0,
    val navLegSamples: Int = 0,
) {
    companion object {
        const val DEFAULT_SERVICE_SEC = 180L
    }
}

/** Hakulistan osio: osoitehaun osuma vai yrityshaun yritys. */
enum class SuggestionKind { ADDRESS, BUSINESS }

/**
 * Osoitehaun ehdotus. [source] on palvelun lähdemaininta, joka näytetään tulosten alla. [location] on osuman
 * sijainti lähiosuman päättelyyn, kun palvelu sen antaa.
 */
data class PlaceSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String,
    val source: String = "",
    val location: GeoPoint? = null,
    val kind: SuggestionKind = SuggestionKind.ADDRESS,
)

/** Haetun paikan tiedot. */
data class PlaceDetails(
    val placeId: String,
    val name: String,
    val address: String,
    val location: GeoPoint,
)

/** Reitin yleiskatsaus kartalle: legien kestot ja koko reitin polyline. */
data class RouteOverview(
    val encodedPolyline: String,
    val legDurationsSec: List<Long>,
    val legDistancesM: List<Long>,
    val totalDurationSec: Long,
)
