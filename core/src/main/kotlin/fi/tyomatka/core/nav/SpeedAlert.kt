package fi.tyomatka.core.nav

/**
 * Nopeusmittarin tila Googlen navigointinäkymän oletusten mukaan: mittari avautuu rajoituskyltin viereen vasta
 * ylityksessä, lievä varoitus 10 km/h ja selvä 20 km/h yli rajoituksen. Tuntematon rajoitus (puuttuva, nolla tai
 * negatiivinen) ei varoita.
 */
enum class SpeedAlert { NONE, OVER, MINOR, MAJOR }

fun speedAlert(speedKmh: Int?, limitKmh: Int?): SpeedAlert {
    if (speedKmh == null || limitKmh == null || limitKmh <= 0) return SpeedAlert.NONE
    val over = speedKmh - limitKmh
    return when {
        over >= MAJOR_OVER_KMH -> SpeedAlert.MAJOR
        over >= MINOR_OVER_KMH -> SpeedAlert.MINOR
        over > 0 -> SpeedAlert.OVER
        else -> SpeedAlert.NONE
    }
}

private const val MINOR_OVER_KMH = 10
private const val MAJOR_OVER_KMH = 20
