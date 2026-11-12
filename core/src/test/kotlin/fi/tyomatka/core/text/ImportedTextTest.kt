package fi.tyomatka.core.text

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportedTextTest {

    private fun xlsx(sharedStrings: List<String>, sheetRows: List<List<Pair<String, String>>>): ByteArray {
        // Minimaalinen .xlsx: sharedStrings + yksi taulukko. Solu = (tyyppi, arvo): "s" viittaa jaettuihin merkkijonoihin.
        val shared = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            sharedStrings.forEach { append("<si><t xml:space=\"preserve\">$it</t></si>") }
            append("</sst>")
        }
        val sheet = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            sheetRows.forEachIndexed { r, cells ->
                append("<row r=\"${r + 1}\">")
                cells.forEachIndexed { c, (type, value) ->
                    val ref = "${'A' + c}${r + 1}"
                    when (type) {
                        "s" -> append("<c r=\"$ref\" t=\"s\"><v>$value</v></c>")
                        "inline" -> append("<c r=\"$ref\" t=\"inlineStr\"><is><t>$value</t></is></c>")
                        else -> append("<c r=\"$ref\"><v>$value</v></c>")
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("[Content_Types].xml", "<Types/>")
            put("xl/workbook.xml", "<workbook/>")
            put("xl/sharedStrings.xml", shared)
            put("xl/worksheets/sheet1.xml", sheet)
        }
        return out.toByteArray()
    }

    @Test
    fun `xlsx-rivit yhdistetään soluittain pilkulla ja tyhjät rivit ohitetaan`() {
        val bytes = xlsx(
            sharedStrings = listOf("Mannerheimintie 10", "Helsinki", "Kaivokatu 1"),
            sheetRows = listOf(
                listOf("s" to "0", "s" to "1"),
                emptyList(),
                listOf("s" to "2", "n" to "00100", "inline" to "Helsinki"),
            ),
        )
        assertEquals(
            listOf("Mannerheimintie 10, Helsinki", "Kaivokatu 1, 00100, Helsinki"),
            ImportedText.lines("stopit.xlsx", bytes),
        )
    }

    @Test
    fun `xlsx purkaa xml-entiteetit`() {
        val bytes = xlsx(listOf("Kauppa &amp; Kahvila"), listOf(listOf("s" to "0")))
        assertEquals(listOf("Kauppa & Kahvila"), ImportedText.lines("a.xlsx", bytes))
    }

    @Test
    fun `tekstitiedosto rivitetään ja csv-erottimet muutetaan pilkuiksi`() {
        val text = "Mannerheimintie 10;Helsinki\r\n\r\nKaivokatu 1\tHelsinki\nUnioninkatu 5\n"
        assertEquals(
            listOf("Mannerheimintie 10, Helsinki", "Kaivokatu 1, Helsinki", "Unioninkatu 5"),
            ImportedText.lines("stopit.csv", text.toByteArray()),
        )
    }

    @Test
    fun `tiedostovalitsin tarjoaa tuetut tyypit ja vanhat taulukot, joista annetaan ohje`() {
        val supported = listOf(
            "text/plain",
            "text/csv",
            "text/comma-separated-values",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream",
        )
        assertTrue(ImportedText.PICKER_MIME_TYPES.containsAll(supported))
        // Vanhan taulukon pitää olla valittavissa, jotta kuljettaja saa ohjeen tallentaa se toiseen muotoon.
        assertTrue("application/vnd.ms-excel" in ImportedText.PICKER_MIME_TYPES)
        assertTrue("application/vnd.oasis.opendocument.spreadsheet" in ImportedText.PICKER_MIME_TYPES)
    }

    @Test
    fun `pakattu tiedosto ilman xlsx-päätettä ei ole tuettu`() {
        // Tiedoston nimi voi puuttua (silloin tilalla on tunniste): pakattu taulukko luettaisiin tekstinä roskaksi.
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertFalse(ImportedText.isSupported("msf:123", zip))
        assertFalse(ImportedText.isSupported("lista.txt", zip))
        assertTrue(ImportedText.isSupported("lista.xlsx", zip))
        assertTrue(ImportedText.isSupported("LISTA.XLSX", zip))
        assertTrue(ImportedText.isSupported("msf:123", "Kauppakatu 5, Oulu".toByteArray()))
    }

    @Test
    fun `tuntematon pääte luetaan tekstinä`() {
        assertEquals(listOf("Kaivokatu 1"), ImportedText.lines("muistio", "Kaivokatu 1\n".toByteArray()))
    }
}
