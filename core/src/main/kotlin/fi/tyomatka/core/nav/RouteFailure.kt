package fi.tyomatka.core.nav

/** Syy, miksi reittiä ei saatu. Ajosessio valitsee tästä virhetekstin ja uuden yrityksen tavan. */
sealed interface RouteFailure {
    /** Verkko, aikakatkaisu tai palvelinvirhe: yritetään automaattisesti uudelleen. */
    data object Unavailable : RouteFailure

    /** Reittiä ei löydy (`NoRoute`, `NoSegment`): uusi yritys vasta, kun on liikuttu tai stoppia muokattu. */
    data object NoRoute : RouteFailure

    /** Reitti on palvelimen rajaa pidempi (`DistanceExceeded`). */
    data object TooLong : RouteFailure

    /** Pyyntö tai vastaus ei kelpaa: väärä palvelin tai asetus. */
    data object BadConfig : RouteFailure

    /** Sijaintia ei saatu: yritetään uudelleen, kun sijainti tulee. */
    data object NoLocation : RouteFailure

    /** Palvelin rajoitti pyyntöjä (HTTP 429); seuraava yritys aikaisintaan [retryAfterSec] sekunnin päästä. */
    data class RateLimited(val retryAfterSec: Long) : RouteFailure
}
