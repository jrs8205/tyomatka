package fi.tyomatka.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Lisätiedot-tekstin normalisointi: sama sääntö lomakkeissa, repositoriossa ja tuonnissa. */
class NoteTextTest {

    @Test
    fun `reunojen valilyonnit ja CRLF siistitaan`() {
        assertEquals("moi \n ok", normalizeNote("  moi \r\n ok  "))
    }

    @Test
    fun `tyhja ja puuttuva teksti ovat tyhjia`() {
        assertEquals("", normalizeNote(null))
        assertEquals("", normalizeNote("   "))
        assertEquals("", normalizeNote("\r\n"))
    }

    @Test
    fun `teksti rajataan 500 merkkiin`() {
        val long = "x".repeat(600)
        assertEquals(NOTE_MAX_LENGTH, normalizeNote(long).length)
        assertEquals(500, NOTE_MAX_LENGTH)
    }

    @Test
    fun `alun valilyonnit eivat kuluta rajaa`() {
        assertEquals(500, normalizeNote("   " + "k".repeat(600)).length)
    }

    @Test
    fun `rajattu teksti ei paaty valilyontiin`() {
        // 499 merkkiä, välilyönti ja lisää: rajattu teksti ei pääty välilyöntiin.
        val text = "y".repeat(499) + " " + "z".repeat(50)
        assertEquals("y".repeat(499), normalizeNote(text))
    }
}
