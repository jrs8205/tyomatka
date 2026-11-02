package fi.tyomatka.core.nav

import kotlin.math.abs

/**
 * Live-ETA:n ja -matkan päivitysten harvennus: uusi arvo, kun aikaa on kulunut 30 s tai matka on
 * muuttunut 100 m. Alle 500 metrin päässä matka päivittyy 20 metrin välein, jotta Perillä aukeaa ajallaan.
 */
class ProgressThrottle {
    private var lastEmitMs: Long? = null
    private var lastRemainingM = 0.0

    fun reset() {
        lastEmitMs = null
    }

    fun shouldEmit(nowMs: Long, remainingM: Double): Boolean {
        // Epäkelpo matka ei päivitä eikä jää vertailuarvoksi.
        if (!remainingM.isFinite()) return false
        val previous = lastEmitMs
        val change = abs(remainingM - lastRemainingM)
        val emit = previous == null ||
            nowMs - previous >= INTERVAL_MS ||
            change >= STEP_M ||
            (remainingM < NEAR_M && change >= NEAR_STEP_M)
        if (emit) {
            lastEmitMs = nowMs
            lastRemainingM = remainingM
        }
        return emit
    }

    private companion object {
        const val INTERVAL_MS = 30_000L
        const val STEP_M = 100.0
        const val NEAR_M = 500.0
        const val NEAR_STEP_M = 20.0
    }
}
