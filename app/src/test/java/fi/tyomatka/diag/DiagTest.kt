package fi.tyomatka.diag

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagTest {
    @AfterTest
    fun reset() = Diag.install(null)

    @Test
    fun `ilman asennusta tapahtuma ohitetaan`() {
        Diag.install(null)
        Diag.event("ajo", "aloitus")
    }

    @Test
    fun `asennettu kirjoitin saa tunnisteen ja rivin`() {
        val got = mutableListOf<Pair<String, String>>()
        Diag.install { tag, line -> got += tag to line }
        Diag.event("ajo", "aloitus")
        assertEquals(listOf("Diag" to "ajo: aloitus"), got)
    }

    @Test
    fun `install null lopettaa kirjoituksen`() {
        val got = mutableListOf<String>()
        Diag.install { _, line -> got += line }
        Diag.install(null)
        Diag.event("ajo", "aloitus")
        assertTrue(got.isEmpty())
    }
}
