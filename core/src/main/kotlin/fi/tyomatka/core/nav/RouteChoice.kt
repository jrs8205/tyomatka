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
