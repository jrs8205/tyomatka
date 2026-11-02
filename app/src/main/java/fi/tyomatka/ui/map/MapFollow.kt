package fi.tyomatka.ui.map

import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees
import kotlin.math.roundToInt

/** Kartta-välilehden kameratila: vapaa (sovitus reittiin) tai oman sijainnin seuranta ajosuunta ylös. */
enum class CameraMode { FREE, FOLLOW }

/** Seurantatilan ja nopeusnäytön säännöt ilman Compose-riippuvuuksia. */
object MapFollow {
    /** Vapaassa tilassa nopeus näytetään vasta liikkeessä, jotta paikallaan ei näy turhaa nollaa. */
    const val MOVING_KMH = 5

    /** Verkko- ja solupaikannus antaa satojen metrien tarkkuuden ja kohinanopeuksia; vain satelliittitason fix kelpaa. */
    const val MAX_ACCURACY_M = 50.0

    fun speedKmh(metersPerSecond: Double?, accuracyM: Double?): Int? {
        if (metersPerSecond == null || accuracyM == null || accuracyM > MAX_ACCURACY_M) return null
        return (metersPerSecond * 3.6).roundToInt().coerceAtLeast(0)
    }

    fun showSpeed(mode: CameraMode, speedKmh: Int?): Boolean =
        speedKmh != null && (mode == CameraMode.FOLLOW || speedKmh >= MOVING_KMH)

    fun afterCameraMove(mode: CameraMode, byGesture: Boolean): CameraMode =
        if (byGesture) CameraMode.FREE else mode

    /**
     * Kameran bearing on myötäpäivään pohjoisesta ajosuuntaan. maplibre-compose 0.13.1 laskee sijaintinuolen suunnan samoin
     * (`kurssi - pohjoinen`), joten nuolelle kurssi annetaan sellaisenaan; 0.13.0:n peilauskiertotie poistettiin.
     */
    fun cameraBearing(course: Bearing?): Double? = course?.let { Bearing.North.clockwiseRotationTo(it).inDegrees }
}
