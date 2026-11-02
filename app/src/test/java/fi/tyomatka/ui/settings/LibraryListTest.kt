package fi.tyomatka.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryListTest {
    private val json = """
        {
          "libraries": [
            {"uniqueId": "b:b", "name": "beeta", "artifactVersion": "2.0", "website": "https://b.example", "licenses": ["MIT", "Apache-2.0"]},
            {"uniqueId": "a:a", "name": "Alfa", "artifactVersion": "1.0", "licenses": ["Apache-2.0"]},
            {"uniqueId": "c:c", "artifactVersion": "3.0", "licenses": []},
            {"uniqueId": "d:d", "name": "Delta", "licenses": ["Tuntematon"]}
          ],
          "licenses": {
            "Apache-2.0": {"name": "Apache License 2.0", "content": "Apache-teksti", "url": "https://apache.example"},
            "MIT": {"name": "MIT License", "url": "https://mit.example"}
          }
        }
    """.trimIndent()

    private val libraries = parseLibraries(json)

    @Test
    fun `kirjastot aakkosissa kirjainkoosta riippumatta`() {
        assertEquals(listOf("Alfa", "beeta", "c:c", "Delta"), libraries.map { it.name })
    }

    @Test
    fun `lisenssien nimet ja versio`() {
        val beeta = libraries.single { it.name == "beeta" }

        assertEquals("2.0", beeta.version)
        assertEquals(listOf("MIT License", "Apache License 2.0"), beeta.licenseNames)
    }

    @Test
    fun `lisenssin teksti löytyy kirjaston lisensseistä`() {
        assertEquals("Apache-teksti", libraries.single { it.name == "Alfa" }.licenseText)
        assertEquals("Apache-teksti", libraries.single { it.name == "beeta" }.licenseText)
    }

    @Test
    fun `ilman tekstiä käytetään lisenssin linkkiä ja sen jälkeen kirjaston sivua`() {
        val parsed = parseLibraries(
            """{"libraries": [
                {"uniqueId": "x", "name": "X", "website": "https://x.example", "licenses": ["MIT"]},
                {"uniqueId": "y", "name": "Y", "website": "https://y.example", "licenses": []}
            ], "licenses": {"MIT": {"name": "MIT", "url": "https://mit.example"}}}""",
        )

        assertNull(parsed[0].licenseText)
        assertEquals("https://mit.example", parsed[0].url)
        assertEquals("https://y.example", parsed[1].url)
    }

    private val templates = """
        {
          "libraries": [
            {"uniqueId": "bsd:a", "name": "BSD", "website": "https://bsd.example", "licenses": ["BSD-3-Clause"]},
            {"uniqueId": "mit:a", "name": "MIT", "website": "https://mit.example", "licenses": ["MIT"]},
            {"uniqueId": "apache:a", "name": "Apache", "licenses": ["Apache-2.0"]},
            {"uniqueId": "dual:a", "name": "Kaksi", "licenses": ["BSD-3-Clause", "Apache-2.0"]}
          ],
          "licenses": {
            "BSD-3-Clause": {"name": "BSD", "content": "Copyright (c) <<var;name=copyright;match=.+>>"},
            "MIT": {"name": "MIT", "content": "MIT License Copyright (c) <year> <copyright holders>"},
            "Apache-2.0": {"name": "Apache", "content": "Apache-teksti"}
          }
        }
    """.trimIndent()

    @Test
    fun `alkuperäinen merkintä korvaa lisenssin mallipohjan`() {
        val parsed = parseLibraries(templates, mapOf("bsd:a" to "Copyright (c) Tekijä"))

        assertEquals("Copyright (c) Tekijä", parsed.single { it.id == "bsd:a" }.licenseText)
    }

    @Test
    fun `merkintä näytetään valmiin lisenssitekstin edellä`() {
        val parsed = parseLibraries(templates, mapOf("apache:a" to "Huomautus"))

        assertEquals("Huomautus\n\nApache-teksti", parsed.single { it.id == "apache:a" }.licenseText)
    }

    @Test
    fun `mallipohjaa ei näytetä ilman alkuperäistä merkintää`() {
        val parsed = parseLibraries(templates)

        assertNull(parsed.single { it.id == "bsd:a" }.licenseText)
        assertNull(parsed.single { it.id == "mit:a" }.licenseText)
        assertEquals("https://mit.example", parsed.single { it.id == "mit:a" }.url)
    }

    @Test
    fun `kahden lisenssin kirjastolla näkyy valmis teksti ilman mallipohjaa`() {
        assertEquals("Apache-teksti", parseLibraries(templates).single { it.id == "dual:a" }.licenseText)
    }

    @Test
    fun `kaikki valmiit lisenssitekstit näytetään`() {
        val parsed = parseLibraries(
            """{"libraries": [{"uniqueId": "x", "name": "X", "licenses": ["A", "B"]}],
                "licenses": {"A": {"name": "A", "content": "ensimmäinen"}, "B": {"name": "B", "content": "toinen"}}}""",
        )

        assertEquals("ensimmäinen\n\ntoinen", parsed.single().licenseText)
    }

    @Test
    fun `merkinnät luetaan kirjastoryhmittäin`() {
        val notices = parseNotices(
            """[{"libraries": ["a:a", "a:b"], "source": "https://a.example", "text": "Teksti A"},
                {"libraries": ["c:c"], "text": "Teksti C"}]""",
        )

        assertEquals(mapOf("a:a" to "Teksti A", "a:b" to "Teksti A", "c:c" to "Teksti C"), notices)
    }

    @Test
    fun `rikkinäinen merkintätiedosto ei kaada`() {
        assertEquals(emptyMap(), parseNotices("ei json"))
        assertEquals(emptyMap(), parseNotices("{}"))
        assertEquals(emptyMap(), parseNotices("""[5, {"libraries": 5, "text": "x"}, {"libraries": ["a"]}, {"libraries": [5], "text": "x"}]"""))
    }

    @Test
    fun `tuntematon lisenssi näkyy tunnuksellaan`() {
        assertEquals(listOf("Tuntematon"), libraries.single { it.name == "Delta" }.licenseNames)
    }

    @Test
    fun `rikkinäinen aineisto antaa tyhjän listan`() {
        assertEquals(emptyList(), parseLibraries("ei json"))
        assertEquals(emptyList(), parseLibraries("{}"))
    }

    @Test
    fun `väärän muotoinen luettelo ei kaada`() {
        assertEquals(emptyList(), parseLibraries("""{"libraries": 5}"""))
        assertEquals(emptyList(), parseLibraries("""{"libraries": [5]}"""))
        assertEquals(emptyList(), parseLibraries("[]"))
    }

    @Test
    fun `väärän muotoiset lisenssit ohitetaan`() {
        val parsed = parseLibraries("""{"libraries": [{"uniqueId": "x", "name": "X", "licenses": 5}], "licenses": 7}""")

        assertEquals(listOf("X"), parsed.map { it.name })
        assertEquals(emptyList(), parsed.single().licenseNames)
    }
}
