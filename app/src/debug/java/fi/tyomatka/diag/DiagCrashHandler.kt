package fi.tyomatka.diag

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Kirjoittaa kaatumisen pinon lokitiedostoon ennen edellistä käsittelijää: logcat-kaappaus ei ehdi lukea kaatumista
 * ennen prosessin päättymistä.
 */
class DiagCrashHandler(
    private val writer: DiagWriter,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching {
            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
            writer.eventNow(Diag.TAG, "[crash] ${thread.name}: $stack", level = 'E')
            writer.close()
        }
        previous?.uncaughtException(thread, error)
    }
}
