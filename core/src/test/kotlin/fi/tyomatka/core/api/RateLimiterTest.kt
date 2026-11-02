package fi.tyomatka.core.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {

    @Test
    fun `toinen pyyntö samalle palvelimelle odottaa vähimmäisvälin`() = runTest {
        val limiter = RateLimiter(minIntervalMs = 1000) { testScheduler.currentTime }

        limiter.acquire("a.example")
        limiter.acquire("a.example")

        assertEquals(1000, testScheduler.currentTime)
    }

    @Test
    fun `eri palvelimet eivät odota toisiaan`() = runTest {
        val limiter = RateLimiter(minIntervalMs = 1000) { testScheduler.currentTime }

        limiter.acquire("a.example")
        limiter.acquire("b.example")

        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun `välin täytyttyä ei odoteta`() = runTest {
        val limiter = RateLimiter(minIntervalMs = 1000) { testScheduler.currentTime }

        limiter.acquire("a.example")
        testScheduler.advanceTimeBy(1500)
        limiter.acquire("a.example")

        assertEquals(1500, testScheduler.currentTime)
    }

    @Test
    fun `kellon hyppy taaksepäin ei pidennä odotusta yli vähimmäisvälin`() = runTest {
        var clock = 5_000L
        val limiter = RateLimiter(minIntervalMs = 1000) { clock }

        limiter.acquire("a.example")
        clock = 0
        limiter.acquire("a.example")

        assertEquals(1000, testScheduler.currentTime)
    }
}
