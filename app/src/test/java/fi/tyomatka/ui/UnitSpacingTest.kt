package fi.tyomatka.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Luvun ja yksikön välissä on sitova väli (U+00A0), jottei yksikkö jää isolla fontilla yksin seuraavalle riville
 * ("475 450" / "km"). Koskee kaikkia kieliä ja paikanpitimen perään kirjoitettuja yksiköitä.
 */
class UnitSpacingTest {

    private fun texts(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml"))
        val out = linkedMapOf<String, String>()
        val strings = document.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val element = strings.item(i) as Element
            out[element.getAttribute("name")] = element.textContent
        }
        val items = document.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            val parent = item.parentNode as Element
            out["${parent.getAttribute("name")}#${item.getAttribute("quantity")}"] = item.textContent
        }
        return out
    }

    @Test
    fun `paikanpitimen ja yksikon valissa on sitova vali`() {
        val unit = Regex("""%\d\$[sd] (km/h|km|kWh|kW|kg|min|h|l|m|t|MB|Mt|€)(?![\p{L}\d])""")
        val breakable = listOf("values", "values-fi", "values-sv").flatMap { folder ->
            texts(folder).filterValues { unit.containsMatchIn(it) }.keys.map { "$folder/$it" }
        }
        assertEquals(emptyList<String>(), breakable)
    }
}
