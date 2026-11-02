package fi.tyomatka.nav

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteFailure
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Käännös käännökseltä -navigoinnin portti, jonka takana on varsinainen
 * navigointimoottori. [fi.tyomatka.drive.DriveSessionManager] ajaa päivän
 * tilakoneen tämän rajapinnan yli, joten se testataan JVM:ssä ilman moottoria.
 *
 * Kaikki kutsut ja tilamuutokset tehdään Main-säikeessä; moottorin omat
 * verkko- ja laskentatyöt eivät näy kutsujalle.
 */
interface NavigationEngine {
    /**
     * Avaa navigointisession (ääni, sijainti). Idempotentti. [simulate] ajaa sijainnin reittiä pitkin pöytätestissä
     * (vain debug-versio käyttää).
     */
    suspend fun startSession(simulate: Boolean = false)

    /** Hakee reitin kohteeseen — vain verkko, ei vielä opastusta. Heittää [RoutingException]. */
    suspend fun prepareRoute(target: NavTarget): PreparedRoute

    /** Aloittaa tai vaihtaa opastuksen valmiiksi haetulle reitille. */
    fun startGuidance(route: PreparedRoute)

    /**
     * Saapuminen tai kohteenvaihto: ääni ja uudelleenreititys pois, sijainti
     * jatkuu. Turvallinen kutsua myös ilman käynnissä olevaa opastusta.
     */
    fun suspendGuidance()

    /** Sulkee session ja vapauttaa moottorin. Turvallinen kutsua ilman avointa sessiota. */
    fun endSession()

    /** Nykyisen legin eteneminen; null kun opastus ei ole käynnissä. */
    val progress: StateFlow<LegProgress?>

    /** Saapumiset kohteeseen, kerran per aloitettu opastus. */
    val arrivals: SharedFlow<NavTarget>

    /** Viimeisin raaka sijainti (linnuntie-varmistuksiin). */
    val location: StateFlow<GeoPoint?>

    /**
     * Poikkeaman jälkeinen uuden reitin haku epäonnistui käynnissä olevassa opastuksessa. Moottori jatkaa
     * vanhalla reitillä ja yrittää itse uudelleen; eteneminen on nollattu, koska vanhan reitin etäisyys ei kuvaa mitään.
     */
    val rerouteFailures: SharedFlow<RouteFailure>
}

/** Navigoinnin kohde: stoppi, jota kohti ajetaan. */
data class NavTarget(val stopId: Long, val location: GeoPoint, val label: String)

/**
 * Moottorin hakema reitti kohteeseen. [payload] on moottorin oma reittiolio,
 * jota vain moottori tulkitsee.
 */
class PreparedRoute(
    val target: NavTarget,
    val predictedSec: Long,
    val distanceM: Double,
    internal val payload: Any,
    /** Saako legin toteumasta oppia ennusteisiin; lyhimmän reitin toteuma ei kerro tavallisen reitin ajasta. */
    val learnable: Boolean = true,
)

/**
 * Legin eteneminen; [generation] erottaa peräkkäiset opastukset toisistaan. [offRoute]: lukemat ovat vanhan reitin
 * projektiosta poikkeaman aikana, eivät uuden reitin.
 */
data class LegProgress(
    val target: NavTarget,
    val generation: Int,
    val remainingSec: Long,
    val remainingM: Int,
    val offRoute: Boolean = false,
)

/** Reitin haku epäonnistui; [failure] kertoo syyn ja sen, miten uudelleen yritetään. */
class RoutingException(
    val failure: RouteFailure,
    message: String = failure.toString(),
    cause: Throwable? = null,
) : Exception(message, cause)
