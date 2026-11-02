package fi.tyomatka.ui.drive

/**
 * Perillä-, Valmis- ja Ohita-nappien tuplanapautuksen esto: hyväksytyn napautuksen ja tilan
 * vaihtumisen jälkeen seuraava napautus ohitetaan [windowMs] ajan, jottei Perillä → Valmis lähde stopilta heti.
 */
class StopActionGuard(private val windowMs: Long = WINDOW_MS) {
    private var blockedUntilMs = Long.MIN_VALUE

    fun stateChanged(nowMs: Long) {
        blockedUntilMs = maxOf(blockedUntilMs, nowMs + windowMs)
    }

    fun accept(nowMs: Long): Boolean {
        if (nowMs < blockedUntilMs) return false
        blockedUntilMs = nowMs + windowMs
        return true
    }

    companion object {
        const val WINDOW_MS = 1000L
    }
}
