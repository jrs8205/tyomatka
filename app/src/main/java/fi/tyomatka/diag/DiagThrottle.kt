package fi.tyomatka.diag

/** Harventaa toistuvat tapahtumat (sijainti) enintään yhteen [minIntervalMs]-välein; ensimmäinen sallitaan aina. */
class DiagThrottle(private val minIntervalMs: Long = 10_000, private val now: () -> Long = { Diag.clock() }) {
    private var last: Long? = null

    @Synchronized
    fun allow(): Boolean {
        val t = now()
        val previous = last
        if (previous != null && t - previous < minIntervalMs) return false
        last = t
        return true
    }
}
