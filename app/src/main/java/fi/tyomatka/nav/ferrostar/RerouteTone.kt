package fi.tyomatka.nav.ferrostar

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import fi.tyomatka.diag.Diag

/**
 * Lyhyt kaksoispiippaus, kun uusi reitti otetaan käyttöön (reitin päivitys ei kuulunut). Soitetaan
 * mediavirtaan kuten puheohjeet, jotta se kuuluu samasta paikasta ja samalla voimakkuudella. Äänigeneraattori luodaan
 * tarvittaessa ja vapautetaan session lopussa; sen luonnin epäonnistuminen ei saa kaataa opastusta.
 */
class RerouteTone {
    private var generator: ToneGenerator? = null

    /** Kutsu tulee ytimen säikeestä ja [release] pääsäikeestä: lukko ja kiinniotto, ettei vapautettu generaattori kaada. */
    @Synchronized
    fun play() {
        Diag.event("ääni", "piippaus reitin vaihdosta")
        val tone = generator ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME) }
            .onFailure { Log.w(TAG, "Merkkiääntä ei voitu luoda", it) }
            .getOrNull()
            ?.also { generator = it }
            ?: return
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, DURATION_MS) }
            .onFailure { Log.w(TAG, "Merkkiääni epäonnistui", it) }
    }

    @Synchronized
    fun release() {
        generator?.release()
        generator = null
    }

    private companion object {
        const val TAG = "RerouteTone"
        /** Täysi voimakkuus mediavirran tasoon nähden: 80 ei kuulunut ajossa. */
        const val VOLUME = 100
        const val DURATION_MS = 300
    }
}
