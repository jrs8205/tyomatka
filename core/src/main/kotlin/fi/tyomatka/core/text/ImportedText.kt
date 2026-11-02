package fi.tyomatka.core.text

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Osoitelistan tuonti tiedostosta tai liitetystä tekstistä (testattu erilaisilla tiedostoilla).
 * Xlsx puretaan ilman kirjastoja: työkirjan ensimmäinen taulukko (`workbook.xml` ja sen rels), jaetut ja
 * rivinsisäiset merkkijonot, rikastettu teksti yhdistettynä. Muut tiedostot luetaan tekstinä: UTF-8:n BOM poistetaan,
 * ja jos tavut eivät ole kelvollista UTF-8:aa, ne luetaan Windows-1252:na (Muistion vanha ANSI-tallennus). Csv:n
 * lainausmerkit puretaan, erottimina puolipiste ja sarkain (pilkku vain lainausmerkkirivillä, koska "Hämeenkatu 1,
 * Tampere" on yksi osoite). Rivin alun numerointi ja luettelomerkit poistetaan. Otsikkorivi (vain tunnettuja
 * otsikkosanoja) ohitetaan, ja sen jälkeen hakuun otetaan vain osoitesarakkeet: nimi, puhelin ja huomautus kaataisivat
 * geokoodauksen. Ilman otsikkoriviä solut liitetään pilkulla.
 */
object ImportedText {

    fun lines(fileName: String, bytes: ByteArray): List<String> =
        joinRows(if (fileName.lowercase().endsWith(".xlsx")) xlsxRows(bytes) else textRows(bytes))

    /** Liitetty teksti: sama siivous kuin tiedostolle (numerointi, otsikkorivi, tyhjät rivit). */
    fun fromText(text: String): List<String> {
        val lines = text.lines()
        val separator = fileSeparator(lines)
        return joinRows(lines.map { splitLine(it, separator) })
    }

    /** Vanhat .xls- ja .ods-taulukot eivät ole tuettuja: pääte tai OLE-alku kertoo sen. */
    fun isSupported(fileName: String, bytes: ByteArray): Boolean {
        val lower = fileName.lowercase()
        if (lower.endsWith(".xls") || lower.endsWith(".ods")) return false
        return !bytes.startsWith(OLE_MAGIC)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Solurivit tekstiriveiksi: otsikkorivin jälkeen vain osoitesarakkeet, muuten kaikki solut pilkulla. */
    private fun joinRows(rows: List<List<String>>): List<String> {
        // Numerointi ("1.", "2)") poistetaan vain luettelosta: numerot alkavat ykkösestä ja kasvavat järjestyksessä,
        // ja numeroituja tai merkittyjä rivejä on vähintään kaksi. "5. linja 3" ja "3. linja 4" ovat kadunnimiä.
        val firsts = rows.mapNotNull { row -> row.firstOrNull()?.trim() }
        val numbers = firsts.mapNotNull { NUMBERING.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val bullets = firsts.count { BULLET.containsMatchIn(it) }
        val numberedList = numbers.isNotEmpty() && numbers == (1..numbers.size).toList() && numbers.size + bullets >= 2
        val cleaned = rows.map { row -> row.map { cleanCell(it, numberedList) } }
        val headerIndex = cleaned.indexOfFirst { it.any(String::isNotEmpty) }
        val header = cleaned.getOrNull(headerIndex)
        val addressColumns = header?.let { addressColumns(it) }
        val body = if (addressColumns != null) cleaned.drop(headerIndex + 1) else cleaned
        return body.map { row ->
            val cells = if (addressColumns != null) addressColumns.mapNotNull { row.getOrNull(it) } else row
            cells.filter(String::isNotEmpty).joinToString(", ")
        }.filter(String::isNotBlank)
    }

    /** Osoitesarakkeiden indeksit, jos rivi on otsikkorivi (vain tunnettuja sanoja, vähintään yksi osoitesarake). */
    private fun addressColumns(row: List<String>): List<Int>? {
        val words = row.map { it.lowercase().trim() }
        if (words.none(String::isNotEmpty) || words.any { it.isNotEmpty() && it !in HEADER_WORDS }) return null
        val columns = words.withIndex().filter { it.value in ADDRESS_WORDS }.map { it.index }
        return columns.ifEmpty { null }
    }

    /** Solun alun luettelomerkit (ja numeroidussa listassa numerointi) pois, reunavälilyönnit pois. */
    private fun cleanCell(cell: String, numberedList: Boolean): String {
        val trimmed = BULLET.replace(cell.trim(), "").trim()
        return if (numberedList) NUMBERING.replace(trimmed, "").trim() else trimmed
    }

    private fun textRows(bytes: ByteArray): List<List<String>> {
        val lines = decode(bytes).removePrefix("\uFEFF").lines()
        val separator = fileSeparator(lines)
        return lines.map { splitLine(it, separator) }
    }

    /**
     * Erotin koko tiedostolle: puolipiste tai sarkain, jos jokin rivi sisältää sen; pilkku vain, kun ensimmäinen rivi
     * on pilkuilla erotettu otsikkorivi (Google Sheetsin ja englantilaisen Excelin vienti). Muuten rivi on yksi
     * osoite, jossa pilkku erottaa kadun ja kaupungin.
     */
    private fun fileSeparator(lines: List<String>): Set<Char>? {
        // Lainausmerkkien sisällä oleva merkki ("soita; portti B") ei ole erotin.
        if (lines.any { line -> outsideQuotes(line).let { ';' in it || '\t' in it } }) return setOf(';', '\t')
        val first = lines.firstOrNull { it.isNotBlank() } ?: return null
        val header = splitLine(first, setOf(','))
        return if (header.size >= 2 && addressColumns(header) != null) setOf(',') else null
    }

    /** Rivi ilman lainausmerkkien sisältöä erottimen päättelyyn. */
    private fun outsideQuotes(line: String): String = QUOTED.replace(line, "")

    /** UTF-16 BOM:n mukaan, UTF-8, jos tavut kelpaavat; muuten Windows-1252. */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, WINDOWS_1252)
        }
    }

    /** Rivi soluiksi tiedoston erottimilla; lainausmerkit puretaan. Ilman erotinta rivi on yksi solu. */
    private fun splitLine(line: String, fileSeparators: Set<Char>?): List<String> {
        if (line.isBlank()) return emptyList()
        val separators = fileSeparators ?: if ('"' in line) setOf(',') else return listOf(line.trim())
        if ('"' !in line) return line.split(*separators.toCharArray()).map(String::trim)
        val bare = outsideQuotes(line)
        val separator = separators.firstOrNull { it in bare } ?: separators.first()
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == separator && !quoted -> { cells += current.toString().trim(); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString().trim()
        return cells
    }

    /** Valitun taulukon polku xlsx:ssä (työkirja ja rels), tai null, kun taulukkoa ei ole. */
    fun selectedSheet(fileName: String, bytes: ByteArray): String? {
        if (!fileName.lowercase().endsWith(".xlsx")) return null
        return runCatching { firstSheetPath(readZipEntries(bytes, WORKBOOK_ENTRIES)) }.getOrNull()
            ?.takeIf { path -> runCatching { readZipEntries(bytes, setOf(path)).containsKey(path) }.getOrDefault(false) }
    }

    /**
     * Kaksi lukukertaa: ensin työkirja ja rels (pienet), sitten vain valittu taulukko ja jaetut merkkijonot. Muiden
     * välilehtien sisältöä ei pidetä muistissa (iso käyttämätön välilehti kaatoi tuonnin).
     */
    private fun xlsxRows(bytes: ByteArray): List<List<String>> {
        val path = firstSheetPath(readZipEntries(bytes, WORKBOOK_ENTRIES))
        val entries = readZipEntries(bytes, setOf(path, "xl/sharedStrings.xml"))
        val shared = entries["xl/sharedStrings.xml"]?.let { xml ->
            SI.findAll(xml).map { si -> T.findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) } }.toList()
        }.orEmpty()
        val sheet = entries[path] ?: return emptyList()
        return ROW.findAll(sheet).map { row -> rowCells(row.groupValues[1], shared) }.toList()
    }

    /** Vain pyydetyt zip-jäsenet tekstinä; muut ohitetaan lukematta. */
    private fun readZipEntries(bytes: ByteArray, wanted: Set<String>): Map<String, String> {
        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix("/")
                if (name in wanted) entries[name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                if (entries.size == wanted.size) break
            }
        }
        return entries
    }

    /**
     * Rivin solut sarakkeen mukaan: Excel jättää tyhjät solut pois ja kirjoittaa tyylitellyn tyhjän solun
     * itsesulkeutuvana (`<c r="A2" s="1"/>`), joten sarake luetaan `r`-viitteestä ja välit täytetään tyhjillä.
     */
    private fun rowCells(rowXml: String, shared: List<String>): List<String> {
        val cells = ArrayList<String>()
        CELL.findAll(rowXml).forEach { cell ->
            val attrs = cell.groupValues[1]
            val inner = cell.groupValues[2]
            val value = when (TYPE.find(attrs)?.groupValues?.get(1)) {
                "s" -> V.find(inner)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }
                "inlineStr" -> T.findAll(inner).joinToString("") { unescape(it.groupValues[1]) }
                else -> V.find(inner)?.groupValues?.get(1)?.let(::unescape)
            }.orEmpty()
            val column = REF.find(attrs)?.groupValues?.get(1)?.let(::columnIndex) ?: cells.size
            while (cells.size <= column) cells.add("")
            cells[column] = value
        }
        return cells
    }

    /** "A" → 0, "Z" → 25, "AA" → 26. */
    private fun columnIndex(letters: String): Int = letters.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1

    /** Työkirjan ensimmäinen taulukko rels-tiedoston kautta; ilman niitä tai viittauksen puuttuessa `sheet1.xml`. */
    private fun firstSheetPath(entries: Map<String, String>): String {
        val fallback = "xl/worksheets/sheet1.xml"
        val workbook = entries["xl/workbook.xml"] ?: return fallback
        val rels = entries["xl/_rels/workbook.xml.rels"] ?: return fallback
        val relId = SHEET_REL.find(workbook)?.groupValues?.get(1) ?: return fallback
        val target = Regex("""<Relationship\b[^>]*\bId="${Regex.escape(relId)}"[^>]*\bTarget="([^"]+)"""").find(rels)
            ?.groupValues?.get(1)
            ?: Regex("""<Relationship\b[^>]*\bTarget="([^"]+)"[^>]*\bId="${Regex.escape(relId)}"""").find(rels)?.groupValues?.get(1)
            ?: return fallback
        return if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val BULLET = Regex("""^[-•*–]\s+""")
    private val NUMBERING = Regex("""^(\d{1,3})[.)]\s+""")
    private val QUOTED = Regex(""""(?:[^"]|"")*"""")
    private val WORKBOOK_ENTRIES = setOf("xl/workbook.xml", "xl/_rels/workbook.xml.rels")

    private val ADDRESS_WORDS = setOf(
        "osoite", "katuosoite", "lähiosoite", "osoite 1", "address", "street", "street address", "adress", "gatuadress",
        "postinumero", "postinro", "postnummer", "postcode", "zip", "zip code", "kaupunki", "paikkakunta", "kunta", "city",
        "town", "stad", "ort", "postitoimipaikka",
    )
    private val HEADER_WORDS = ADDRESS_WORDS + setOf(
        "nimi", "name", "namn", "nro", "no", "huomautus", "huom", "huomautukset", "puhelin", "phone", "telefon",
        "lisätiedot", "asiakas", "yritys", "company", "kommentti", "comment", "sähköposti", "email", "aika", "time",
    )

    private val SI = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val T = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("<row(?:\\s[^>]*)?>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    /** Solu avaavana ja sulkevana tagina tai itsesulkeutuvana (`<c r="A2" s="1"/>`). */
    private val CELL = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val REF = Regex("\\sr=\"([A-Z]+)\\d+\"")
    private val TYPE = Regex("\\st=\"([^\"]+)\"")
    private val V = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)

    /** Työkirjan ensimmäisen taulukon viittaus (`r:id`), attribuuttien järjestyksestä riippumatta. */
    private val SHEET_REL = Regex("""<sheet\b[^>]*\br:id="([^"]+)"""")
}
