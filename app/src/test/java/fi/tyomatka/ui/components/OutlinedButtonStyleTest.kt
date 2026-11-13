package fi.tyomatka.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ääriviivanapit näyttävät samalta koko sovelluksessa: reuna ja värit tulevat [AppButtonDefaults]ista. Asettelua ei
 * voi piirtää näissä testeissä, joten sääntö tarkistetaan lähdekoodin kutsuista. Testi kattaa `OutlinedButton`-kutsut;
 * muun napin käsin tehty reuna ja ääriviivalliset kuvakenapit eivät kuulu siihen.
 */
class OutlinedButtonStyleTest {

    private data class Call(val path: String, val line: Int, val args: String, val content: String) {
        val place get() = "${path.substringAfterLast('/')}:$line"
    }

    /**
     * Lähde, josta kommentit, merkkijonot ja merkkivakiot on korvattu välilyönneillä. Paikat ja rivit säilyvät, ja
     * merkkijonomallin `${ }` sisältö jää koodiksi.
     */
    private class Masker(private val source: String, private val name: String) {
        private val out = StringBuilder(source)
        private var i = 0

        fun masked(): String {
            code(inTemplate = false)
            return out.toString()
        }

        private fun at(text: String) = source.startsWith(text, i)

        private fun blank(count: Int) {
            repeat(count) {
                if (i < out.length) {
                    if (out[i] != '\n') out[i] = ' '
                    i++
                }
            }
        }

        private fun open(what: String): Nothing = error("$name: $what jää auki")

        private fun code(inTemplate: Boolean) {
            var depth = 0
            while (i < source.length) {
                when {
                    at("//") -> while (i < source.length && source[i] != '\n') blank(1)
                    at("/*") -> blockComment()
                    at("\"\"\"") -> { blank(3); string(raw = true) }
                    at("\"") -> { blank(1); string(raw = false) }
                    at("'") -> charLiteral()
                    // Takahipsuissa oleva nimi saa sisältää lainausmerkkejä ja sulkuja; tavallinen nimi jää näkyviin.
                    at("`") -> {
                        val end = source.indexOf('`', i + 1).takeIf { it >= 0 } ?: open("takahipsunimi")
                        val plain = Regex("""[A-Za-z_]\w*""").matches(source.substring(i + 1, end))
                        i++
                        if (plain) i = end else while (i < end) blank(1)
                        i++
                    }
                    at("{") -> { depth++; i++ }
                    at("}") -> {
                        if (inTemplate && depth == 0) {
                            blank(1)
                            return
                        }
                        depth--
                        i++
                    }
                    else -> i++
                }
            }
            if (inTemplate) open("merkkijonomalli")
        }

        private fun blockComment() {
            var depth = 0
            while (i < source.length) {
                when {
                    at("/*") -> { depth++; blank(2) }
                    at("*/") -> {
                        blank(2)
                        if (--depth == 0) return
                    }
                    else -> blank(1)
                }
            }
            open("kommentti")
        }

        private fun charLiteral() {
            blank(1)
            while (i < source.length) {
                when {
                    at("\\") -> blank(2)
                    at("'") -> {
                        blank(1)
                        return
                    }
                    else -> blank(1)
                }
            }
            open("merkkivakio")
        }

        private fun string(raw: Boolean) {
            while (i < source.length) {
                when {
                    !raw && at("\\") -> blank(2)
                    at("\${") -> { blank(2); code(inTemplate = true) }
                    raw && at("\"\"\"") -> {
                        blank(3)
                        // Lopun ylimääräiset lainausmerkit kuuluvat raakamerkkijonon sisältöön.
                        while (at("\"")) blank(1)
                        return
                    }
                    !raw && at("\"") -> {
                        blank(1)
                        return
                    }
                    else -> blank(1)
                }
            }
            open("merkkijono")
        }
    }

    /** Sulkua [open] vastaavan sulun paikka peitetyssä lähteessä. */
    private fun closing(code: String, open: Int, name: String): Int {
        val opener = code[open]
        val closer = if (opener == '(') ')' else '}'
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == opener) depth++
            if (code[i] == closer && --depth == 0) return i
        }
        error("$name: sulku jää auki riviltä ${code.take(open).count { it == '\n' } + 1}")
    }

    /** Kutsu: nimen ja avaussulun välissä saa olla samalla rivillä välejä tai kommentti. Muut muodot ilmoittaa [unparsed]. */
    private val call = Regex("""(?<!\w)OutlinedButton[ \t]*\(""")
    private val mention = Regex("""(?<!\w)OutlinedButton(?!\w)""")
    private val plainImport = "import androidx.compose.material3.OutlinedButton"

    private fun calls(path: String, source: String): List<Call> {
        val code = Masker(source, path).masked()
        return call.findAll(code).map { match ->
            val open = match.range.last
            val close = closing(code, open, path)
            val next = (close + 1 until code.length).firstOrNull { !code[it].isWhitespace() }
            val content = if (next != null && code[next] == '{') code.substring(next, closing(code, next, path) + 1) else ""
            Call(path, code.take(open).count { it == '\n' } + 1, code.substring(open, close + 1), content)
        }.toList()
    }

    /**
     * Paikat, joissa nimi esiintyy koodissa muuten kuin tunnistettuna kutsuna tai tavallisena tuontina. Toisella
     * nimellä tuotu nappi jäisi kutsujen haulta näkemättä, joten sekin ilmoitetaan.
     */
    private fun unparsed(path: String, source: String): List<String> {
        val code = Masker(source, path).masked()
        val known = call.findAll(code).map { it.range.first }.toSet()
        fun lineOf(start: Int): String {
            val end = code.indexOf('\n', start).let { if (it < 0) code.length else it }
            return code.substring(code.lastIndexOf('\n', start) + 1, end).trim().replace(Regex("""\s+"""), " ")
        }
        return mention.findAll(code).map { it.range.first }
            .filterNot { it in known }
            .filterNot { lineOf(it) == plainImport }
            .map { start -> "${path.substringAfterLast('/')}:${code.take(start).count { it == '\n' } + 1}" }
            .toList()
    }

    private fun sources(): List<File> = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun outlinedButtons(): List<Call> = sources().flatMap { calls(it.invariantSeparatorsPath, it.readText()) }

    /**
     * Kutsun nimetyt argumentit ylimmältä tasolta, arvojen tyhjät välit tiivistettyinä. Sisäkkäisten kutsujen ja
     * lambdojen sisältö kuuluu sen argumentin arvoon, jonka sisällä se on.
     */
    private fun arguments(args: String): Map<String, String> {
        val inner = args.substring(1, args.length - 1)
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        inner.forEachIndexed { index, c ->
            when (c) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    parts += inner.substring(start, index)
                    start = index + 1
                }
            }
        }
        parts += inner.substring(start)
        val named = Regex("""^(\w+)\s*=(?!=)\s*(.+)$""")
        return parts.map { it.trim().replace(Regex("""\s+"""), " ") }
            .mapNotNull { part -> named.find(part)?.let { it.groupValues[1] to it.groupValues[2] } }
            .toMap()
    }

    /** Tänään-kortin toissijainen nappi on värillisellä pohjalla, ja sillä on oma 2 dp:n reunansa. */
    private fun isTodayCardButton(call: Call) =
        call.path.endsWith("ui/history/TodayCard.kt") && arguments(call.args)["border"] == "BorderStroke(2.dp, color)"

    /** Puuttuva osa yhteisestä tyylistä, tai null, kun kutsu on kunnossa. */
    private fun fault(args: String): String? {
        val named = arguments(args)
        val border = named["border"]
        if (named["colors"] == "AppButtonDefaults.dangerOutlinedColors()") {
            return if (border == "AppButtonDefaults.dangerBorder()") null else "varoitusnapin reuna"
        }
        if (named["colors"] != "AppButtonDefaults.outlinedColors()") return "värit"
        // Reuna seuraa napin tilaa: käytöstä poissa olevan napin reuna on himmeämpi.
        val enabled = named["enabled"] ?: "true"
        val borderState = border?.let { Regex("""^AppButtonDefaults\.border\(\s*enabled\s*=\s*(.+?)\s*\)$""").find(it) }?.groupValues?.get(1)
        return if (borderState == enabled) null else "reuna tilalla $enabled"
    }

    @Test
    fun `kutsujen haku ohittaa kommentit merkkijonot ja merkkivakiot`() {
        val quotes = "\"\"\""
        val source = listOf(
            "// OutlinedButton( kommentissa \"",
            "/* OutlinedButton( /* sisäkkäin ) */ \" */",
            "val a = \"OutlinedButton(\" + '(' + '\"' + '\\'' + ${quotes}raaka ( \" $quotes + \"\${f(\"x)\")})\"",
            "fun `a'b`() = 1",
            "OutlinedButton(onClick = { g(')') /* ) */ }, colors = X) { Text(\"a)\") }",
            "fun `c'd \"`() = 2",
            "androidx.compose.material3.OutlinedButton(onClick = {})",
            "OutlinedButton (onClick = {})",
            "OutlinedButton /* väli */ (onClick = {})",
        ).joinToString("\n")
        val found = calls("Esimerkki.kt", source)
        assertEquals(listOf(5, 7, 8, 9), found.map { it.line })
        assertEquals("(onClick = { g(   )         }, colors = X)", found[0].args)
        assertEquals("{ Text(    ) }", found[0].content)
        assertEquals("", found[1].content)
    }

    @Test
    fun `tyylin tarkistus hylkaa vaarat varit vaaran reunan ja tilasta poikkeavan reunan`() {
        val colors = "colors = AppButtonDefaults.outlinedColors()"
        val border = "border = AppButtonDefaults.border(enabled = true)"
        fun borderFor(state: String) = "border = AppButtonDefaults.border(enabled = $state)"
        assertEquals(null, fault("(onClick = a, $colors, $border)"))
        assertEquals(null, fault("(onClick = a, $colors, $border,\n)"))
        // Napautuksen oma koodi ei ole napin argumentti.
        assertEquals(null, fault("(onClick = Tap { b = c.copy(); enabled = true }, $colors, $border)"))
        assertEquals(null, fault("(onClick = a, enabled = n > 0, $colors, ${borderFor("n > 0")})"))
        assertEquals(null, fault("(onClick = a, enabled=maxOf(1, n) > 1, $colors, ${borderFor("maxOf(1, n) > 1")})"))
        assertEquals(null, fault("(onClick = a, $colors, ${borderFor("ok")}, enabled = ok)"))
        assertEquals(null, fault("(onClick = a, enabled = first &&\n    second, $colors, ${borderFor("first && second")})"))
        assertEquals(
            null,
            fault("(colors = AppButtonDefaults.dangerOutlinedColors(), border = AppButtonDefaults.dangerBorder())"),
        )
        assertEquals("värit", fault("(onClick = a, $border)"))
        assertEquals("värit", fault("(colors = AppButtonDefaults.filledColors(), $border)"))
        assertEquals("värit", fault("(colors = AppButtonDefaults.outlinedColors().copy(a), $border)"))
        assertEquals("värit", fault("(colors = AppButtonDefaults.outlinedColors().let { other() }, $border)"))
        assertEquals("reuna tilalla true", fault("(onClick = a, $colors)"))
        assertEquals("reuna tilalla true", fault("(onClick = a, $colors, border = AppButtonDefaults.dangerBorder())"))
        assertEquals("reuna tilalla n > 0", fault("(onClick = a, enabled = n > 0, $colors, $border)"))
        assertEquals("reuna tilalla n > 0", fault("(onClick = Tap { enabled = true }, enabled=n > 0, $colors, $border)"))
        assertEquals("varoitusnapin reuna", fault("(colors = AppButtonDefaults.dangerOutlinedColors(), $border)"))
    }

    @Test
    fun `aariviivanapit kayttavat yhteista reunaa ja yhteisia vareja`() {
        val calls = outlinedButtons()
        assertTrue("kutsuja löytyi vain ${calls.size}", calls.size >= 8)
        val (exempt, shared) = calls.partition(::isTodayCardButton)
        assertEquals("Tänään-kortin poikkeus", 1, exempt.size)
        assertEquals(emptyList<String>(), shared.mapNotNull { call -> fault(call.args)?.let { "${call.place} $it" } })
    }

    @Test
    fun `nimen muu kaytto kuin tunnistettu kutsu tai tuonti ilmoitetaan`() {
        val source = listOf(
            "import androidx.compose.material3.OutlinedButton",
            "import\tandroidx.compose.material3.OutlinedButton",
            "import androidx.compose.material3.OutlinedButton as Toinen",
            "import androidx.compose.material3.OutlinedButton /* väli */ as Kolmas",
            "import androidx.compose.material3.`OutlinedButton` as Neljas",
            "val f = ::OutlinedButton",
            "OutlinedButton(onClick = {})",
            "// OutlinedButton kommentissa",
            "val g: @Composable (() -> Unit) -> Unit = { OutlinedButton",
            "    (onClick = it) }",
            "`OutlinedButton`(onClick = {})",
        ).joinToString("\n")
        assertEquals(
            listOf(3, 4, 5, 6, 9, 11).map { "Esimerkki.kt:$it" },
            unparsed("ui/Esimerkki.kt", source),
        )
    }

    @Test
    fun `jokainen aariviivanapin maininta lahteissa on tunnistettu kutsu`() {
        assertTrue("lähteitä on myös kansiossa src/main/kotlin, jota ei käydä läpi", !File("src/main/kotlin").exists())
        assertEquals(emptyList<String>(), sources().flatMap { unparsed(it.invariantSeparatorsPath, it.readText()) })
    }

    @Test
    fun `Lopeta ajo on toimintonapin korkuinen ja virhevarinen yhteisella reunalla`() {
        val stop = outlinedButtons().single { it.path.endsWith("ui/drive/DriveScreen.kt") && "R.string.stop_driving" in it.content }
        val named = arguments(stop.args)
        assertEquals("AppButtonDefaults.dangerOutlinedColors()", named["colors"])
        assertEquals("AppButtonDefaults.dangerBorder()", named["border"])
        assertTrue("korkeus: ${named["modifier"]}", named["modifier"].orEmpty().endsWith(".heightIn(min = ActionHeight)"))
        val drive = File("src/main/java/fi/tyomatka/ui/drive/DriveScreen.kt").readText()
        assertTrue("toimintonapin korkeus ei ole 56 dp", "private val ActionHeight = 56.dp" in drive)
    }

    @Test
    fun `yhteisten reunojen leveys on yksi vakio`() {
        val source = File("src/main/java/fi/tyomatka/ui/components/AppButtons.kt").readText()
        assertTrue("BorderWidth puuttuu tai ei ole 1,5 dp", "val BorderWidth = 1.5.dp" in source)
        val strokes = Regex("""BorderStroke\(\s*([^,]+),""").findAll(source).map { it.groupValues[1].trim() }.toList()
        assertTrue("reunoja löytyi vain ${strokes.size}", strokes.size >= 2)
        assertEquals(emptyList<String>(), strokes.filter { it != "BorderWidth" })
    }
}
