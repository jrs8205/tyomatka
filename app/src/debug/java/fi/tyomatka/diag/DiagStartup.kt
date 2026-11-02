package fi.tyomatka.diag

import android.app.Application
import android.util.Log
import java.io.File
import javax.inject.Inject

/**
 * Debug-buildin diagnostiikkalokin käynnistys: tiedostokirjoitin kansioon `files/diag`, vanhojen päivien siivous,
 * omat tapahtumat suoraan tiedostoon ja Androidin lokiin, kaatumiskäsittelijä ja oman prosessin logcat-kaappaus.
 * Haku: `adb exec-out run-as fi.tyomatka cat files/diag/YYYY-MM-DD.log`. Mikään tässä ei saa kaataa sovellusta.
 */
class DiagStartup @Inject constructor() : AppStartup {
    override fun start(app: Application) {
        runCatching {
            val dir = File(app.filesDir, "diag")
            val writer = DiagWriter(dir)
            writer.prune()
            Diag.clock = android.os.SystemClock::elapsedRealtime
            Diag.install { tag, line ->
                runCatching {
                    writer.event(tag, line)
                    Log.i(tag, line)
                }
            }
            Thread.setDefaultUncaughtExceptionHandler(DiagCrashHandler(writer, Thread.getDefaultUncaughtExceptionHandler()))
            LogcatCapture(writer, android.os.Process.myPid(), stateDir = dir).start()
        }.onFailure { Log.w(Diag.TAG, "Diagnostiikkalokia ei voitu käynnistää", it) }
    }
}
