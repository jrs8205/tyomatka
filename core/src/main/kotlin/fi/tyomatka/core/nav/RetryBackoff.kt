package fi.tyomatka.core.nav

/** Palvelinkatkon uusintaväli: 5, 10, 20 ja 40 sekuntia, sitten minuutin välein niin kauan kuin kohde on sama. */
class RetryBackoff {
    fun delaySec(attempt: Int): Long = STEPS_SEC[attempt.coerceIn(0, STEPS_SEC.lastIndex)]

    private companion object {
        val STEPS_SEC = longArrayOf(5, 10, 20, 40, 60)
    }
}
