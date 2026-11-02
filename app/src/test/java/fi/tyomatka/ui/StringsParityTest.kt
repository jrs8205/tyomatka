package fi.tyomatka.ui

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/** Kieliversiot pysyvät samanlaisina: englanti on oletus, ja suomessa ja ruotsissa on samat tekstit ja paikanpitimet. */
class StringsParityTest {
    private val english = texts("values")
    private val finnish = texts("values-fi")
    private val swedish = texts("values-sv")

    private fun texts(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml"))
        val out = linkedMapOf<String, String>()
        val strings = document.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val element = strings.item(i) as Element
            out[element.getAttribute("name")] = element.textContent
        }
        val plurals = document.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val element = plurals.item(i) as Element
            val items = element.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                out["${element.getAttribute("name")}#${item.getAttribute("quantity")}"] = item.textContent
            }
        }
        return out
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `kaikissa kielissä on samat tekstit`() {
        assertEquals(finnish.keys, english.keys)
        assertEquals(finnish.keys, swedish.keys)
    }

    @Test
    fun `paikanpitimet täsmäävät jokaisessa tekstissä`() {
        for ((name, text) in finnish) {
            assertEquals(name, placeholders(text), placeholders(english.getValue(name)))
            assertEquals(name, placeholders(text), placeholders(swedish.getValue(name)))
        }
    }

    @Test
    fun `yksikään teksti ei ole tyhjä`() {
        for (language in listOf(english, finnish, swedish)) {
            for ((name, text) in language) assertFalse(name, text.isBlank())
        }
    }

    @Test
    fun `sovelluksen nimi on kielen oma ja oletus on englanti`() {
        assertEquals("Work Trip", english["app_name"])
        assertEquals("Työmatka", finnish["app_name"])
        assertEquals("Körjournal", swedish["app_name"])
        val properties = Properties().apply { File("src/main/res/resources.properties").inputStream().use(::load) }
        assertEquals("en", properties.getProperty("unqualifiedResLocale"))
    }

    @Test
    fun `viikonpäivälistassa on seitsemän päivää`() {
        for (language in listOf(english, finnish, swedish)) {
            assertEquals(7, language.getValue("poi_hours_days").split(",").size)
        }
    }

    @Test
    fun `englanti on brittiläistä kuten raportissa`() {
        val american = Regex("""\b(kilometers?|meters?|liters?|gasoline|fueled|canceled|color)\b""", RegexOption.IGNORE_CASE)
        for ((name, text) in english) assertFalse("$name: $text", american.containsMatchIn(text))
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(?:\d+\$)?[sd]""")
    }
}
