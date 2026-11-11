package fi.tyomatka.core.nav

/**
 * Kuljettajan reittivalinta: nopein on palvelimen arvion mukaan nopein reitti, lyhin voi kulkea pieniä teitä, jotka maalla
 * voivat olla oikeasti nopeampia kuin palvelimen arvio.
 */
enum class RouteChoice(val key: String) {
    NORMAL("normal"),
    SHORTEST("shortest");

    companion object {
        fun of(key: String?): RouteChoice = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/**
 * Voimassa oleva valinta: kartalla valittu lyhin koskee vain valintapäivää ([chosenOnDay], epoch-päivä), muuten ajetaan
 * nopeinta. Näin suoraan Ajo-sivulle menevä kuljettaja saa aina nopeimman, ellei ole valinnut tänään toisin.
 */
fun routeChoiceForDay(stored: RouteChoice, chosenOnDay: Long?, today: Long): RouteChoice =
    if (stored == RouteChoice.SHORTEST && chosenOnDay == today) RouteChoice.SHORTEST else RouteChoice.NORMAL
