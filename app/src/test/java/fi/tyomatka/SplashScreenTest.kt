package fi.tyomatka

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Käynnistysnäkymä kaikilla tuetuilla Android-versioilla: sama kuvake ja tausta teemasta riippumatta. */
class SplashScreenTest {
    private fun document(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))

    private fun styles(path: String): List<Element> =
        document(path).getElementsByTagName("style").let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Element.items(): Map<String, String> =
        getElementsByTagName("item").let { list ->
            (0 until list.length).associate { i -> (list.item(i) as Element).let { it.getAttribute("name") to it.textContent.trim() } }
        }

    private val starting = styles("src/main/res/values/themes.xml").singleOrNull { it.getAttribute("name") == "Theme.Tyomatka.Starting" }

    @Test
    fun `käynnistysteema näyttää kuvakkeen ja palaa sovelluksen teemaan`() {
        val style = assertNotNull(starting)
        val items = style.items()

        assertEquals("Theme.SplashScreen", style.getAttribute("parent"))
        assertEquals("@drawable/ic_launcher_foreground", items["windowSplashScreenAnimatedIcon"])
        assertEquals("@style/Theme.Tyomatka", items["postSplashScreenTheme"])
        assertEquals("#FF0F3270", items["windowSplashScreenBackground"])
    }

    @Test
    fun `tausta ei riipu puhelimen teemasta`() {
        assertTrue(styles("src/main/res/values-night/themes.xml").none { it.getAttribute("name") == "Theme.Tyomatka.Starting" })
    }

    @Test
    fun `päänäkymä alkaa käynnistysteemalla`() {
        val manifest = document("src/main/AndroidManifest.xml")
        val activity = manifest.getElementsByTagName("activity").let { list ->
            (0 until list.length).map { list.item(it) as Element }.single { it.getAttribute("android:name") == ".MainActivity" }
        }

        assertEquals("@style/Theme.Tyomatka.Starting", activity.getAttribute("android:theme"))
        assertEquals("@style/Theme.Tyomatka", (manifest.getElementsByTagName("application").item(0) as Element).getAttribute("android:theme"))
    }

    @Test
    fun `käynnistysnäkymä asennetaan ennen aktiviteetin luontia`() {
        val source = File("src/main/java/fi/tyomatka/MainActivity.kt").readText()
        val install = source.indexOf("installSplashScreen()")
        val create = source.indexOf("super.onCreate(savedInstanceState)")

        assertFalse(install < 0, "installSplashScreen puuttuu")
        assertTrue(install < create)
    }
}
