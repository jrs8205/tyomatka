package fi.tyomatka.ui.drive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Perillä- ja Ohita-napit: toinen napautus sekunnin sisällä ei saa laukaista seuraavan tilan toimintoa. */
class StopActionGuardTest {

    @Test
    fun `ensimmainen napautus hyvaksytaan`() {
        val guard = StopActionGuard()
        assertTrue(guard.accept(nowMs = 10_000))
    }

    @Test
    fun `toinen napautus sekunnin sisalla ohitetaan`() {
        val guard = StopActionGuard()
        assertTrue(guard.accept(nowMs = 10_000))
        assertFalse(guard.accept(nowMs = 10_300))
        assertFalse(guard.accept(nowMs = 10_999))
    }

    @Test
    fun `napautus hyvaksytaan taas sekunnin kuluttua`() {
        val guard = StopActionGuard()
        assertTrue(guard.accept(nowMs = 10_000))
        assertTrue(guard.accept(nowMs = 11_000))
    }

    @Test
    fun `tilan vaihtuminen estaa napautuksen sekunnin ajan`() {
        val guard = StopActionGuard()
        guard.stateChanged(nowMs = 20_000)
        assertFalse(guard.accept(nowMs = 20_400))
        assertTrue(guard.accept(nowMs = 21_000))
    }

    @Test
    fun `tilan vaihtuminen pidentaa estoa hyvaksytyn napautuksen jalkeen`() {
        val guard = StopActionGuard()
        assertTrue(guard.accept(nowMs = 10_000))
        guard.stateChanged(nowMs = 10_800)
        assertFalse(guard.accept(nowMs = 11_500))
        assertTrue(guard.accept(nowMs = 11_800))
    }
}
