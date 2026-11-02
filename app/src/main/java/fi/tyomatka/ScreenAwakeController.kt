package fi.tyomatka

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Portti ikkunaan ja valoanturiin — tilakone pysyy puhtaana ja testattavana. */
internal interface ScreenAwakePort {
    fun setKeepScreenOn(on: Boolean)
    fun setDimmed(dimmed: Boolean)
    fun startLightListening()
    fun stopLightListening()
}

/**
 * Google Maps -tyylinen näytönhallinta ajossa: näyttö ei sammu itsestään,
 * ja pimennys seuraa valoanturia — pimeässä (puhelin kolossa, yöajo)
 * ruudun peittää musta verho ja ajokartta käy lepotilassa (dimmedFlow),
 * valossa kaikki palautuu heti. Napautus kirkastaa aina ja pitää
 * näytön kirkkaana hetken, vaikka olisi pimeää. Virtanappi lukitsee
 * normaalisti — lippu ei estä manuaalista lukitusta.
 *
 * TYPE_LIGHT on on-change-anturi: samaa arvoa ei toisteta, joten viimeisin
 * lux muistetaan ja arvioidaan uudelleen kosketuspidon päättyessä.
 *
 * Kaikki kutsut oletetaan päälangalta (lifecycleScope + dispatchTouchEvent).
 */
internal class ScreenAwakeController(
    private val port: ScreenAwakePort,
    private val scope: CoroutineScope,
) {
    private var driving = false
    private var foreground = false
    private var dimEnabled = true
    private var listening = false
    private var holding = false
    private var lastLux: Float? = null
    private var darkJob: Job? = null
    private var holdJob: Job? = null

    private val _dimmedFlow = MutableStateFlow(false)

    /** Himmennystila UI:lle: verhon ajaksi ajokartta viedään lepotilaan. */
    val dimmedFlow: StateFlow<Boolean> = _dimmedFlow.asStateFlow()

    /** Sisäinen tila elää flow'ssa, jotta UI näkee saman totuuden. */
    private var dimmed: Boolean
        get() = _dimmedFlow.value
        set(value) { _dimmedFlow.value = value }

    fun onDrivingChanged(active: Boolean) {
        if (driving == active) return
        driving = active
        port.setKeepScreenOn(active)
        updateListening()
    }

    /** Pois päältä ruutu ei pimene, mutta näyttö pysyy ajossa hereillä. */
    fun setDimEnabled(enabled: Boolean) {
        dimEnabled = enabled
        updateListening()
    }

    fun onForeground() {
        foreground = true
        updateListening()
    }

    fun onBackground() {
        foreground = false
        updateListening()
    }

    /** @return true jos kosketusele pitää kuluttaa (näyttö oli himmennetty). */
    fun onTouch(): Boolean {
        if (!listening) return false
        val consume = dimmed
        wake()
        holding = true
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(TOUCH_HOLD_MS)
            holding = false
            holdJob = null
            // Pimeys on voinut jatkua koko pidon ajan ilman uutta tapahtumaa.
            evaluate()
        }
        return consume
    }

    fun onLux(lux: Float) {
        lastLux = lux
        evaluate()
    }

    private fun evaluate() {
        if (!listening) return
        val lux = lastLux ?: return
        when {
            lux >= LIGHT_LUX -> wake()

            lux <= DARK_LUX -> if (!dimmed && !holding && darkJob == null) {
                darkJob = scope.launch {
                    delay(DARK_DEBOUNCE_MS)
                    darkJob = null
                    dimmed = true
                    port.setDimmed(true)
                }
            }

            // Hystereesin välialue: alkanut pimeysjakso katkeaa, mutta
            // himmennetty näyttö ei vielä kirkastu.
            else -> {
                darkJob?.cancel()
                darkJob = null
            }
        }
    }

    private fun updateListening() {
        val should = driving && foreground && dimEnabled
        if (should == listening) return
        listening = should
        if (should) {
            lastLux = null
            port.startLightListening()
        } else {
            port.stopLightListening()
            holdJob?.cancel()
            holdJob = null
            holding = false
            wake()
        }
    }

    private fun wake() {
        darkJob?.cancel()
        darkJob = null
        if (dimmed) {
            dimmed = false
            port.setDimmed(false)
        }
    }

    companion object {
        /** Tätä hämärämpi = pimeää (kolo, tasku, yö) — himmennys alkaa. */
        const val DARK_LUX = 8f

        /** Tätä valoisampi = valoisaa — kirkastus heti. Väli on hystereesiä. */
        const val LIGHT_LUX = 40f

        /** Pimeyden pitää kestää tämän ennen himmennystä (sillat, varjot). */
        const val DARK_DEBOUNCE_MS = 3_000L

        /** Napautuksen jälkeen näyttö pysyy kirkkaana tämän ajan pimeässäkin. */
        const val TOUCH_HOLD_MS = 60_000L

        /** Ikkunan kirkkaus himmennettynä: minimi. Musta verho peittää koko
         *  ruudun, joten OLED-pikselit eivät valaise lainkaan — näyttö
         *  näyttää sammuneelta mutta EI sammu (FLAG_KEEP_SCREEN_ON), ja
         *  valo kirkastaa heti kolosta nostettaessa. */
        const val DIM_BRIGHTNESS = 0f
    }
}
