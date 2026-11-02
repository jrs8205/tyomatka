package fi.tyomatka.nav.ferrostar

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Mediavirran äänenvoimakkuus, jossa puheohjeet kuuluvat. [raise] nostaa tason täysille kuljettajan pyynnöstä ja
 * [restore] palauttaa aiemman tason session lopussa, jotta käyttäjän oma asetus ei muutu pysyvästi.
 */
class GuidanceVolume(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var raisedFrom: Int? = null

    fun level(): Int = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

    fun max(): Int = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0

    fun raise() {
        val manager = audio ?: return
        if (raisedFrom == null) raisedFrom = level()
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, max(), AudioManager.FLAG_SHOW_UI) }
            .onFailure { Log.w(TAG, "Äänenvoimakkuuden nosto epäonnistui", it) }
    }

    /** Palautetaan vain, jos taso on yhä nostettu maksimi: kuljettajan oma säätö ajon aikana jää voimaan. */
    fun restore() {
        val previous = raisedFrom ?: return
        raisedFrom = null
        if (level() != max()) return
        runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0) }
            .onFailure { Log.w(TAG, "Äänenvoimakkuuden palautus epäonnistui", it) }
    }

    private companion object {
        const val TAG = "GuidanceVolume"
    }
}
