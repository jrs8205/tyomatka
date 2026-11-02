package fi.tyomatka.core.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enintään yksi pyyntö per [minIntervalMs] per palvelin. Lukko pidetään
 * odotuksen yli, jotta samanaikaiset kutsujat jonottavat järjestyksessä
 * eivätkä laske väliä samasta edellisestä leimasta. Kello on monotoninen,
 * jotta seinäkellon siirto ei pysäytä pyyntöjä; odotus on silti rajattu
 * väliin, jos kello antaa aiempaa pienemmän arvon.
 */
class RateLimiter(
    private val minIntervalMs: Long = 1000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private val lastByHost = HashMap<String, Long>()

    suspend fun acquire(host: String) {
        mutex.withLock {
            val last = lastByHost[host]
            if (last != null) {
                val wait = (last + minIntervalMs - now()).coerceAtMost(minIntervalMs)
                if (wait > 0) delay(wait)
            }
            lastByHost[host] = now()
        }
    }
}
