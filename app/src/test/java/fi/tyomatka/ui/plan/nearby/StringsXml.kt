package fi.tyomatka.ui.plan.nearby

import fi.tyomatka.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Resurssien korvike oikeista suomenkielisistä teksteistä (`values-fi/strings.xml`): testi näkee saman tekstin kuin
 * sovellus. Muotoilu kuten Androidin getString; heittomerkkien ja lainausmerkkien kenoviivat poistetaan.
 */
internal object StringsXml {
    private val byName: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values-fi/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name") to element.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }

    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    val strings: (Int, Array<out Any>) -> String = { id, args ->
        val template = byName.getValue(names.getValue(id))
        if (args.isEmpty()) template else String.format(Locale.forLanguageTag("fi-FI"), template, *args)
    }
}
