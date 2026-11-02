package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeHintTest {

    @Test
    fun `alle 80 prosentin mediataso varoittaa`() {
        assertTrue(VolumeHint.shouldWarn(level = 12, max = 15))
        assertTrue(VolumeHint.shouldWarn(level = 0, max = 15))
        assertTrue(VolumeHint.shouldWarn(level = 11, max = 15))
    }

    @Test
    fun `yli 80 prosentin taso ei varoita`() {
        assertFalse(VolumeHint.shouldWarn(level = 13, max = 15))
        assertFalse(VolumeHint.shouldWarn(level = 15, max = 15))
        assertFalse(VolumeHint.shouldWarn(level = 9, max = 10))
        assertTrue(VolumeHint.shouldWarn(level = 8, max = 10))
    }

    @Test
    fun `epakelpo maksimi ei varoita`() {
        assertFalse(VolumeHint.shouldWarn(level = 0, max = 0))
    }
}
