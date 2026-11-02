package fi.tyomatka.ui.drive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArriveLockTest {

    @Test
    fun `ilman etaisyystietoa lukossa`() {
        assertFalse(isArriveUnlocked(null, null))
    }

    @Test
    fun `kaukana lukossa`() {
        assertFalse(isArriveUnlocked(ARRIVE_UNLOCK_DISTANCE_M + 1, null))
    }

    @Test
    fun `rajalla auki`() {
        assertTrue(isArriveUnlocked(ARRIVE_UNLOCK_DISTANCE_M, null))
    }

    @Test
    fun `perilla auki`() {
        assertTrue(isArriveUnlocked(0, null))
    }

    @Test
    fun `palvelinkatkossa linnuntie avaa lukon samalla rajalla`() {
        assertTrue(isArriveUnlocked(null, 250))
        assertFalse(isArriveUnlocked(null, 350))
    }

    @Test
    fun `reittietaisyys voittaa linnuntien`() {
        assertFalse(isArriveUnlocked(350, 100))
    }
}
