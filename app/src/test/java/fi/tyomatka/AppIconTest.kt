package fi.tyomatka

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Androidin adaptiivisen kuvakkeen vaatimukset: tasot 108 dp ja yksivärinen taso teemoitukseen. */
class AppIconTest {
    private fun xml(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        return factory.newDocumentBuilder().parse(File("src/main/res/$path")).documentElement
    }

    private fun Element.children(): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    @Test
    fun `kuvakkeessa on tausta, etuala ja yksivärinen taso`() {
        val layers = xml("mipmap-anydpi-v26/ic_launcher.xml").children().associate { it.tagName to it.getAttribute("android:drawable") }

        assertEquals("@drawable/ic_launcher_background", layers["background"])
        assertEquals("@drawable/ic_launcher_foreground", layers["foreground"])
        assertEquals("@drawable/ic_launcher_monochrome", layers["monochrome"])
    }

    /** Renkaan polku alkaa ylimmästä pisteestä, ja säde on ensimmäisessä kaaressa. */
    @Test
    fun `reitin päät ja neulan kärki pysyvät 66 dp turva-alueella`() {
        val paths = xml("drawable/ic_launcher_foreground.xml").children().map { it.getAttribute("android:pathData") }
        val ring = Regex("^M([\\d.]+),([\\d.]+) a([\\d.]+),").find(paths[1])!!.groupValues.drop(1).map { it.toDouble() }
        val (ringX, ringTop, ringRadius) = ring
        val ringFar = hypot(ringX - 54, ringTop + ringRadius - 54) + ringRadius
        val tail = Regex("^M[\\d.]+,([\\d.]+) H([\\d.]+)$").find(paths[3])!!.groupValues.drop(1).map { it.toDouble() }
        val tailFar = hypot(tail[1] + 5.5 / 2 - 54, tail[0] - 54)
        val tip = Regex("L([\\d.]+),([\\d.]+) L").find(paths[2])!!.groupValues.drop(1).map { it.toDouble() }

        assertTrue(ringFar <= 33.0, "rengas $ringFar")
        assertTrue(tailFar <= 33.0, "häntä $tailFar")
        assertTrue(hypot(tip[0] - 54, tip[1] - 54) <= 33.0, "kärki")
    }

    @Test
    fun `kaikki tasot ovat 108 dp`() {
        listOf("background", "foreground", "monochrome").forEach { layer ->
            val root = xml("drawable/ic_launcher_$layer.xml")
            assertEquals("108dp", root.getAttribute("android:width"), layer)
            assertEquals("108dp", root.getAttribute("android:height"), layer)
            assertEquals("108", root.getAttribute("android:viewportWidth"), layer)
            assertEquals("108", root.getAttribute("android:viewportHeight"), layer)
        }
    }

    @Test
    fun `yksivärinen taso on saman muotoinen kuin etuala ja yhtä väriä`() {
        val foreground = xml("drawable/ic_launcher_foreground.xml").children()
        val monochrome = xml("drawable/ic_launcher_monochrome.xml").children()

        assertEquals(foreground.map { it.getAttribute("android:pathData") }, monochrome.map { it.getAttribute("android:pathData") })
        val colors = monochrome.flatMap { listOf(it.getAttribute("android:fillColor"), it.getAttribute("android:strokeColor")) }.filter { it.isNotEmpty() }.toSet()
        assertEquals(1, colors.size)
        assertTrue(foreground.flatMap { listOf(it.getAttribute("android:fillColor"), it.getAttribute("android:strokeColor")) }.filter { it.isNotEmpty() }.toSet().size > 1)
    }
}
