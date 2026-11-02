package fi.tyomatka.core.nav

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Yksi sijaintinäyte saapumisen arviointiin. Ajat ovat laitteen käynnistyksestä kuluneita millisekunteja
 * (`elapsedRealtime`): [nowMs] on käsittelyhetki ja [fixTimeMs] sijainnin oma aika, jolloin kellon siirto ei vanhenna
 * eikä nuorenna näytettä. [remainingRouteM] puuttuu, kun reittiä ei ole.
 */
data class ArrivalFix(
    val nowMs: Long,
    val fixTimeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val speedMps: Double?,
    val remainingRouteM: Double?,
    val straightLineM: Double,
)

/**
 * Saapumisen tunnistus stopille. Ferrostarin oma "matka valmis" vaatii kaksi kelpaavaa sijaintia aivan reitin
 * lopussa, mutta jakoauto pysähtyy usein kadun varteen ennen sitä, joten saapuminen hyväksytään kahdella tavalla:
 * reitin loppu hitaasti ajaen tai pysähdys lähelle stoppia. Pysähdys ei kelpaa, jos reittiä on vielä jäljellä yli
 * [NEAR_ROUTE_M]: ruuhkassa mateleva auto voi olla linnuntietä lähellä stoppia, joka on vasta korttelin kierron
 * päässä. Vanha, tulevaisuuteen leimattu tai epätarkka sijainti (tunneli, katveet) ei kelpaa kumpaankaan.
 * Saapuminen ilmoitetaan kerran legiä kohden; kynnykset säädetään ajotestissä.
 */
class ArrivalDetector {
    private var legStartMs = 0L
    private var arrived = false
    private var lastFixTimeMs: Long? = null

    /** Ferrostarin "matka valmis" nähty ennen legin vähimmäiskestoa: saapuminen laukeaa ensimmäisellä näytteellä sen jälkeen. */
    private var tripCompleteSeen = false

    /** Hitaan ja läheisen jakson ensimmäinen näyte: viipyminen mitataan sen sijaintiajasta ja siirtymä sen paikasta. */
    private var dwellAnchor: ArrivalFix? = null

    fun startLeg(nowMs: Long) {
        legStartMs = nowMs
        arrived = false
        lastFixTimeMs = null
        dwellAnchor = null
        tripCompleteSeen = false
    }

    /**
     * Ferrostarin oma "matka valmis": kaksi näytettä aivan reitin lopussa. Lyhyellä legillä se tulee heti aloituksen
     * jälkeen, joten legin vähimmäiskesto pätee siihenkin: saapuminen heti, jos kesto täyttyy, muuten seuraavalla
     * näytteellä sen jälkeen.
     */
    fun onTripComplete(nowMs: Long): Boolean {
        if (arrived) return false
        if (nowMs - legStartMs >= MIN_LEG_MS) {
            arrived = true
            return true
        }
        tripCompleteSeen = true
        return false
    }

    fun onFix(fix: ArrivalFix): Boolean {
        if (arrived) return false
        // Sama näyte uudelleen luettuna ei ole uutta tietoa: se ei etene eikä nollaa viipymistä.
        if (lastFixTimeMs?.let { fix.fixTimeMs <= it } == true) return false
        lastFixTimeMs = fix.fixTimeMs
        val ageMs = fix.nowMs - fix.fixTimeMs
        val valid = ageMs in -MAX_FUTURE_MS..MAX_FIX_AGE_MS && fix.accuracyM <= MAX_ACCURACY_M
        if (!valid) {
            dwellAnchor = null
            return false
        }
        val speed = fix.speedMps
        // Reittiprojektio voi näyttää nollaa sivutieltä, vaikka kohde on kaukana: linnuntien pitää täsmätä.
        val routeEnd = fix.remainingRouteM != null && speed != null &&
            fix.remainingRouteM <= ROUTE_END_M && speed <= ROUTE_END_MPS && fix.straightLineM <= ROUTE_END_MAX_STRAIGHT_M
        val dwelled = updateDwell(fix)
        // Juuri alkanut legi ei saavu: edellisen stopin vieressä oleva seuraava stoppi täyttäisi pysähdysehdon heti
        // lähtöä painettaessa. Kymmenen sekuntia riittää, koska pysähdys vaatii lisäksi oman viipymisensä.
        if (fix.nowMs - legStartMs < MIN_LEG_MS) return false
        arrived = routeEnd || dwelled || tripCompleteSeen
        return arrived
    }

    /** Pysähdys lähelle stoppia: yhtäjaksoisesti hidas ja lähellä [DWELL_MS]:n ajan sijaintien omalla kellolla. */
    private fun updateDwell(fix: ArrivalFix): Boolean {
        val anchor = dwellAnchor
        val near = fix.straightLineM <= NEAR_M && fix.accuracyM <= NEAR_ACCURACY_M &&
            (fix.remainingRouteM == null || fix.remainingRouteM <= NEAR_ROUTE_M)
        // Paikallaan oleva laite ei usein ilmoita nopeutta: silloin hitaus päätellään siirtymästä jakson alusta.
        val slow = when {
            fix.speedMps != null -> fix.speedMps <= NEAR_MPS
            anchor == null -> true
            else -> distanceM(anchor, fix) <= NEAR_DRIFT_M
        }
        if (!near || !slow) {
            dwellAnchor = null
            return false
        }
        val start = anchor ?: fix.also { dwellAnchor = it }
        return fix.fixTimeMs - start.fixTimeMs >= DWELL_MS
    }

    /** Tasokoordinaattien likiarvo riittää kymmenien metrien siirtymiin. */
    private fun distanceM(a: ArrivalFix, b: ArrivalFix): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        return EARTH_RADIUS_M * sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        /** Perillä 50 m:ssä ajovauhdissa: ilmoitus ehtii ennen taloa, ja kohteen puoli kerrotaan. */
        const val ROUTE_END_M = 50.0
        const val ROUTE_END_MPS = 8.0

        /** Reitin loppu kelpaa vain, kun kohde on myös linnuntietä lähellä. */
        const val ROUTE_END_MAX_STRAIGHT_M = 100.0
        const val NEAR_M = 40.0
        const val NEAR_MPS = 2.0
        const val DWELL_MS = 8_000L

        /** Pysähdys kelpaa vain, kun reittiä on jäljellä enintään tämän verran tai reittiä ei ole. */
        const val NEAR_ROUTE_M = 100.0

        /** Pysähdyksen 40 metrin säde vaatii tarkemman sijainnin kuin reitin loppu. */
        const val NEAR_ACCURACY_M = 25.0

        /** Ilman nopeustietoa paikallaanolo: siirtymä jakson alusta enintään tämän verran. */
        const val NEAR_DRIFT_M = 15.0

        const val MIN_LEG_MS = 10_000L
        const val MAX_FIX_AGE_MS = 10_000L

        /** Käsittelyhetkeä uudempi sijainti on kellovirhe; sekunnin verran sallitaan. */
        const val MAX_FUTURE_MS = 1_000L
        const val MAX_ACCURACY_M = 50.0

        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
