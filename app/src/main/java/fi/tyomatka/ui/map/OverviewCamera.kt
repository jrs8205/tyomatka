package fi.tyomatka.ui.map

import fi.tyomatka.core.model.GeoPoint

/** Kameran sovitus näihin pisteisiin (stopit, lähtöpaikka, oma sijainti). */
data class Fit(val points: List<GeoPoint>)

/**
 * Kartta-välilehden kameran tila ilman Compose-riippuvuuksia: välilehti avautuu vapaana ja näyttää
 * koko reitin; ensimmäinen sijainti avauksen jälkeen sovitetaan mukaan kerran, ellei karttaan ole jo koskettu tai
 * seurantaa aloitettu. Stoppien muutos sovittaa reitin aina uudelleen ja päättää seurannan.
 */
class OverviewCamera {
    var mode: CameraMode = CameraMode.FREE
        private set

    /** Ele tai seuranta avauksen jälkeen: ensimmäistä sijaintia ei enää sovitata. */
    private var touched = false
    private var firstLocationDone = false

    /**
     * Avaus ja stoppien muutos. Ilman stoppeja kartta näyttää oman sijainnin ja seuraa sitä heti (
     * "haluan vain katsoa kartalta, missä menen"); stopit avaavat koko reitin vapaana.
     */
    fun onData(stops: List<GeoPoint>, origin: GeoPoint?, location: GeoPoint?): Fit? {
        if (stops.isEmpty()) {
            mode = CameraMode.FOLLOW
            return null
        }
        mode = CameraMode.FREE
        return Fit(stops + listOfNotNull(origin, location))
    }

    /**
     * Lähtöpaikka tai reittiviiva päivittyi taustalla (stopin tila vaihtui, reitti haettiin uudelleen): sovitetaan
     * vain koskematon vapaa kartta; seuranta ja raahattu näkymä säilyvät.
     */
    fun onBackgroundData(stops: List<GeoPoint>, origin: GeoPoint?, location: GeoPoint?): Fit? {
        if (mode != CameraMode.FREE || touched) return null
        val points = stops + listOfNotNull(origin, location)
        return if (points.isEmpty()) null else Fit(points)
    }

    /** Seurannassa sijainti hoituu seurannalla, joten ensimmäistä sijaintia ei sovitata erikseen. */
    fun onFirstLocation(location: GeoPoint, stops: List<GeoPoint>, origin: GeoPoint?): Fit? {
        if (firstLocationDone || touched || mode == CameraMode.FOLLOW) return null
        firstLocationDone = true
        return Fit(stops + listOfNotNull(origin) + location)
    }

    fun onGesture() {
        touched = true
        mode = CameraMode.FREE
    }

    fun onFollow() {
        touched = true
        mode = CameraMode.FOLLOW
    }
}
