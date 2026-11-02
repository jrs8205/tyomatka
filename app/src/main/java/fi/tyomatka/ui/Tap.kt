package fi.tyomatka.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Napautuksen värinäpalaute. Sovelluksessa [install] kytkee laitteen
 * värinämoottorin; testeissä [perform] vaihdetaan. Kääre `Tap { … }` antaa
 * värinän ennen toimintoa, jotta palaute tuntuu heti sormen alla.
 */
object Tap {
    @Volatile
    var perform: () -> Unit = {}

    /** Asetuksista: napautusvärinä pois päältä ei estä toimintoa. */
    @Volatile
    var enabled = true

    operator fun invoke(action: () -> Unit): () -> Unit = {
        now()
        action()
    }

    fun now() {
        if (enabled) perform()
    }

    fun install(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        perform = { vibrator.vibrate(effect) }
    }
}
