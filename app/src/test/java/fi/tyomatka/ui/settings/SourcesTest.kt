package fi.tyomatka.ui.settings

import fi.tyomatka.core.api.Defaults
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourcesTest {
    private val allHosts = SOURCES.flatMap { it.hosts }.toSet()
    private val allUrls = SOURCES.flatMap { source -> source.links.map { it.url } }

    @Test
    fun `jokaisella oletuspalvelimella on lähdemerkintä`() {
        listOf(
            Defaults.VALHALLA_URL,
            Defaults.OSRM_URL,
            Defaults.TILE_STYLE_LIGHT,
            Defaults.PHOTON_URL,
            Defaults.POI_URL,
        ).forEach { url ->
            val host = URI(url).host
            assertTrue(host in allHosts, "$host ilman lähdemerkintää")
        }
    }

    @Test
    fun `digitransitin ja osoitehaun lähteet näkyvät`() {
        assertTrue(SOURCES.any { it.name == "Digitransit" })
        assertTrue(SOURCES.any { it.name == "Photon" })
        assertTrue(SOURCES.none { it.name == "Geoapify" })
    }

    @Test
    fun `osmn vaatimat linkit ovat mukana`() {
        assertTrue("https://www.openstreetmap.org/copyright" in allUrls)
        assertTrue("https://www.openstreetmap.org/fixthemap" in allUrls)
    }

    @Test
    fun `fintraffic linkittää lisenssiin`() {
        val fintraffic = SOURCES.single { it.name == "Fintraffic" }

        assertTrue(fintraffic.links.any { it.url == "https://creativecommons.org/licenses/by/4.0/" })
    }

    @Test
    fun `aineistolisenssit ovat listalla`() {
        assertEquals(setOf("gpl", "cdla", "apache", "foursquare"), DATA_LICENSES.map { it.key }.toSet())
    }

    @Test
    fun `fintrafficin merkintä on ehtojen muodossa kaikilla kielillä`() {
        fun text(folder: String) = File("src/main/res/$folder/strings.xml").readText()

        assertTrue("Source: Fintraffic / digitraffic.fi, license CC 4.0 BY" in text("values"))
        assertTrue("Lähde: Fintraffic / digitraffic.fi, lisenssi CC 4.0 BY" in text("values-fi"))
        assertTrue("Källa: Fintraffic / digitraffic.fi, licens CC 4.0 BY" in text("values-sv"))
    }

    @Test
    fun `saman rivin linkeillä on eri nimet`() {
        SOURCES.forEach { source ->
            assertEquals(source.links.size, source.links.map { it.label }.toSet().size, source.name)
        }
    }
}
