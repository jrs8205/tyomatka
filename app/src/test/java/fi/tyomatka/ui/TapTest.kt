package fi.tyomatka.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TapTest {
    private var vibrations = 0
    private var actions = 0

    private fun install(enabled: Boolean) {
        Tap.perform = { vibrations++ }
        Tap.enabled = enabled
    }

    @AfterTest
    fun restore() {
        Tap.perform = {}
        Tap.enabled = true
    }

    @Test
    fun `napautus värähtää ja tekee toiminnon`() {
        install(enabled = true)

        Tap { actions++ }()

        assertEquals(1, vibrations)
        assertEquals(1, actions)
    }

    @Test
    fun `asetus pois päältä jättää värinän pois mutta toiminto tehdään`() {
        install(enabled = false)

        Tap { actions++ }()
        Tap.now()

        assertEquals(0, vibrations)
        assertEquals(1, actions)
    }
}
