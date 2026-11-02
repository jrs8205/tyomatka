package fi.tyomatka.core.text

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tuonti oikeita tiedostoja vastaavilla aineistoilla (`src/test/resources/tuonti`). */
class ImportedFilesTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/tuonti/$name")) { "puuttuu: $name" }.use { it.readBytes() }

    private fun lines(name: String) = ImportedText.lines(name, resource(name))

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `excelin xlsx ohittaa otsikkorivin ja huomautussarakkeen ja yhdistaa rikastetun tekstin`() {
        val result = lines("excel-osoitteet.xlsx")
        assertEquals(7, result.size)
        assertEquals("Tikkurilantie 10, Vantaa", result.first())
        assertEquals("Karakaari 7, Espoo", result[2])
        assertEquals("Pasilankatu 2, Helsinki", result.last())
    }

    @Test
    fun `libreofficen xlsx lukee tyokirjan ensimmaisen taulukon eika tiedostonimen mukaista`() {
        assertEquals(
            listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, Tampere", "Karakaari 7, Espoo", "Mannerheimintie 100, Helsinki"),
            lines("libreoffice-kaksi-taulukkoa.xlsx"),
        )
    }

    @Test
    fun `inlineStr-solut ilman otsikkoa liitetaan pilkulla`() {
        val result = lines("inline-strings.xlsx")
        assertEquals(4, result.size)
        assertEquals("Tikkurilantie 10, Vantaa, portti B", result.first())
        assertEquals("Kauppakatu 5, Jyväskylä, 40100", result.last())
    }

    @Test
    fun `openpyxl-kirjaston xlsx`() {
        assertEquals(
            listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, Tampere", "Karakaari 7, Espoo", "Mannerheimintie 100, Helsinki"),
            lines("openpyxl.xlsx"),
        )
    }

    @Test
    fun `utf-8-tiedoston bom poistetaan`() {
        val result = lines("utf8-bom.txt")
        assertEquals(6, result.size)
        assertEquals("Tikkurilantie 10, Vantaa", result.first())
    }

    @Test
    fun `windows-merkiston tiedosto luetaan oikein`() {
        val result = lines("windows-1252.txt")
        assertEquals(6, result.size)
        assertEquals("Hämeenkatu 1, Tampere", result[1])
        assertEquals("Åkerlundinkatu 2, Tampere", result[5])
    }

    @Test
    fun `puolipiste-csv purkaa lainausmerkit ja pitaa pilkun kentan sisalla`() {
        assertEquals(
            listOf("Hämeenkatu 1, 2. krs, Tampere", "Karakaari 7, Espoo", "Mannerheimintie 100, Helsinki", "Kirkkonummentie 3, Kirkkonummi"),
            lines("puolipiste-lainaus.csv"),
        )
    }

    @Test
    fun `sarkaimin erotettu tiedosto ilman otsikkoa`() {
        val result = lines("sarkain.txt")
        assertEquals(6, result.size)
        assertEquals("Tikkurilantie 10, Vantaa, portti B", result.first())
        assertEquals("Hämeenkatu 1, Tampere", result[1])
    }

    @Test
    fun `numerointi ja luettelomerkit poistetaan ja tyhjat rivit ohitetaan`() {
        assertEquals(
            listOf(
                "Stopit maanantai",
                "Tikkurilantie 10, Vantaa",
                "Hämeenkatu 1, Tampere",
                "Karakaari 7, Espoo",
                "Mannerheimintie 100, Helsinki",
                "Kirkkonummentie 3, Kirkkonummi",
            ),
            lines("numeroitu.txt"),
        )
    }

    @Test
    fun `utf-16-tekstitiedosto luetaan bomin mukaan`() {
        val text = "Tikkurilantie 10, Vantaa\r\nHämeenkatu 1, Tampere\r\n"
        assertEquals(listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, Tampere"), ImportedText.lines("lista.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, Tampere"), ImportedText.lines("lista.txt", byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `yksittainen jarjestysnumeroinen kadunnimi sailyy mutta numeroitu lista siivotaan`() {
        // "5. linja" on Helsingin katu: numerointi poistetaan vain, kun useampi rivi on numeroitu.
        assertEquals(listOf("5. linja 3, Helsinki", "Hämeenkatu 1, Tampere"), ImportedText.fromText("5. linja 3, Helsinki\nHämeenkatu 1, Tampere"))
        assertEquals(listOf("Tikkurilantie 10, Vantaa", "5. linja 3, Helsinki"), ImportedText.fromText("1. Tikkurilantie 10, Vantaa\n2. 5. linja 3, Helsinki"))
        assertEquals(listOf("Tikkurilantie 10, Vantaa"), ImportedText.fromText("- Tikkurilantie 10, Vantaa"))
    }

    @Test
    fun `pilkulla erotettu tiedosto tunnistetaan otsikkorivista`() {
        val csv = "Nimi,Osoite,Kaupunki\nMatti,Tikkurilantie 10,Vantaa\nLiisa,\"Hämeenkatu 1, 2. krs\",Tampere\n"
        assertEquals(listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, 2. krs, Tampere"), ImportedText.lines("lista.csv", csv.toByteArray()))
        // Ilman otsikkoa pilkku on osa osoitetta.
        assertEquals(listOf("Tikkurilantie 10, Vantaa"), ImportedText.lines("lista.txt", "Tikkurilantie 10, Vantaa\n".toByteArray()))
    }

    @Test
    fun `lainausmerkkien sisainen puolipiste ei vaihda tiedoston erotinta`() {
        // "soita; portti B" vaihtoi pilkku-csv:n erottimen puolipisteeksi ja hukkasi sarkainrivin.
        val comma = "Nimi,Osoite,Kaupunki,Huomautus\nMatti,Tikkurilantie 10,Vantaa,\"soita; portti B\"\nLiisa,Hämeenkatu 1,Tampere,\n"
        assertEquals(listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, Tampere"), ImportedText.lines("lista.csv", comma.toByteArray()))
        val tab = "Nimi\tOsoite\tKaupunki\tHuomautus\nMatti\tTikkurilantie 10\tVantaa\t\"soita; portti B\"\nLiisa\tHämeenkatu 1\tTampere\t\n"
        assertEquals(listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, Tampere"), ImportedText.lines("lista.txt", tab.toByteArray()))
    }

    @Test
    fun `jarjestysnumeroiset kadunnimet eivat ole numeroitu luettelo`() {
        // "5. linja 3" ja "3. linja 4" eivät ole luettelo; luettelon numerot alkavat ykkösestä ja kasvavat.
        assertEquals(listOf("5. linja 3, Helsinki", "3. linja 4, Helsinki"), ImportedText.fromText("5. linja 3, Helsinki\n3. linja 4, Helsinki"))
        assertEquals(listOf("Tikkurilantie 10, Vantaa", "5. linja 3, Helsinki", "Hämeenkatu 1, Tampere"), ImportedText.fromText("1. Tikkurilantie 10, Vantaa\n2. 5. linja 3, Helsinki\n3. Hämeenkatu 1, Tampere"))
        // Jo siivottu lista ei siivoudu toiseen kertaan.
        assertEquals(listOf("5. linja 3, Helsinki", "Hämeenkatu 1, Tampere"), ImportedText.fromText(ImportedText.fromText("1. 5. linja 3, Helsinki\n2. Hämeenkatu 1, Tampere").joinToString("\n")))
    }

    @Test
    fun `xlsx lukee vain valitun taulukon eika sailyta muita muistissa`() {
        // käyttämättömät välilehdet pidettiin muistissa. Muut taulukot ovat tässä roskaa, jota ei saa lukea.
        val junk = "<worksheet><sheetData>" + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>" + "x".repeat(2_000_000) + "</t></is></c></row>" + "</sheetData></worksheet>"
        val stops = "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Pasilankatu 2</t></is></c></row></sheetData></worksheet>"
        val file = zip(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="Stopit" sheetId="1" r:id="rId2"/><sheet name="Muu" sheetId="2" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to junk,
            "xl/worksheets/sheet3.xml" to junk,
            "xl/worksheets/sheet2.xml" to stops,
        )
        assertEquals(listOf("Pasilankatu 2"), ImportedText.lines("lista.xlsx", file))
        assertEquals("xl/worksheets/sheet2.xml", ImportedText.selectedSheet("lista.xlsx", file))
        assertNull(ImportedText.selectedSheet("lista.xlsx", zip("xl/sharedStrings.xml" to "<sst/>")))
    }

    @Test
    fun `liitetty teksti saa saman siivouksen`() {
        assertEquals(
            listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, Tampere"),
            ImportedText.fromText("Osoite\n1. Tikkurilantie 10, Vantaa\n\n- Hämeenkatu 1, Tampere  \n"),
        )
    }

    @Test
    fun `xls ja ods eivat ole tuettuja mutta xlsx txt ja csv ovat`() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(16)
        assertFalse(ImportedText.isSupported("lista.xls", ole))
        assertFalse(ImportedText.isSupported("lista.ods", zip("mimetype" to "application/vnd.oasis.opendocument.spreadsheet")))
        assertFalse(ImportedText.isSupported("lista.XLS", ole))
        assertTrue(ImportedText.isSupported("lista.xlsx", resource("openpyxl.xlsx")))
        assertTrue(ImportedText.isSupported("lista.txt", "Hämeenkatu 1".toByteArray()))
        assertTrue(ImportedText.isSupported("lista.csv", "a;b".toByteArray()))
    }

    @Test
    fun `xlsx tyhjat ja puuttuvat solut pitavat sarakkeet kohdallaan`() {
        // Excel kirjoittaa tyylitellyn tyhjän solun itsesulkeutuvana ja jättää tyhjät solut kokonaan pois.
        fun t(ref: String, text: String) = "<c r=\"$ref\" t=\"inlineStr\"><is><t>$text</t></is></c>"
        val sheet = "<worksheet><sheetData>" +
            "<row r=\"1\">" + t("A1", "Nimi") + t("B1", "Osoite") + t("C1", "Kaupunki") + "</row>" +
            "<row r=\"2\"><c r=\"A2\" s=\"1\"/>" + t("B2", "Tikkurilantie 10") + t("C2", "Vantaa") + "</row>" +
            "<row r=\"3\">" + t("B3", "Hämeenkatu 1") + t("C3", "Tampere") + "</row>" +
            "<row r=\"4\">" + t("A4", "Matti") + t("B4", "Karakaari 7") + t("C4", "Espoo") + "</row>" +
            "</sheetData></worksheet>"
        assertEquals(
            listOf("Tikkurilantie 10, Vantaa", "Hämeenkatu 1, Tampere", "Karakaari 7, Espoo"),
            ImportedText.lines("lista.xlsx", zip("xl/worksheets/sheet1.xml" to sheet)),
        )
    }

    @Test
    fun `xlsx ilman rels-tiedostoa kayttaa sheet1-taulukkoa ja ilman taulukkoa tulee tyhja lista`() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Pasilankatu 2</t></is></c></row></sheetData></worksheet>"""
        assertEquals(listOf("Pasilankatu 2"), ImportedText.lines("lista.xlsx", zip("xl/worksheets/sheet1.xml" to sheet)))
        assertEquals(emptyList(), ImportedText.lines("lista.xlsx", zip("xl/sharedStrings.xml" to "<sst/>")))
        val badRels = zip(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="A" sheetId="1" r:id="rId9"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet,
        )
        assertEquals(listOf("Pasilankatu 2"), ImportedText.lines("lista.xlsx", badRels))
    }
}
