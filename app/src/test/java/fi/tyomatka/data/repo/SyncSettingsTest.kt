package fi.tyomatka.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSettingsTest {
    private class MemoryPrefs : SyncPrefs {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun `osoitehaku kulkee karttakohdepalvelimen kautta`() {
        val servers = ServerUrls(valhalla = "v", osrm = "o", mapStyle = "m", photon = "p", poi = "https://oma.example")

        assertEquals("https://oma.example/v1/geocode", servers.geocode)
        assertEquals("https://oma.example/v1/geocode", servers.copy(poi = "https://oma.example/").geocode)
    }

    @Test
    fun `teeman oletus on järjestelmä`() {
        assertEquals(ThemeChoice.SYSTEM, SyncSettings(MemoryPrefs()).theme)
    }

    @Test
    fun `teema säilyy`() {
        val prefs = MemoryPrefs()

        SyncSettings(prefs).theme = ThemeChoice.DARK

        assertEquals(ThemeChoice.DARK, SyncSettings(prefs).theme)
    }

    private val defaults = ServerUrls(
        valhalla = "https://valhalla.example.fi",
        osrm = "https://osrm.example.fi",
        mapStyle = "https://kartta.example.fi/style",
        photon = "https://photon.example.fi",
        poi = "https://poi.example.fi",
    )

    @Test
    fun `ilman tallennettua käytetään oletuksia`() {
        assertEquals(defaults, SyncSettings(MemoryPrefs()).servers(defaults))
    }

    @Test
    fun `tallennettu osoite korvaa vain oman palvelimensa`() {
        val settings = SyncSettings(MemoryPrefs())

        assertEquals(true, settings.setServer(ServerKind.VALHALLA, " https://oma.example.fi/ "))

        assertEquals(defaults.copy(valhalla = "https://oma.example.fi"), settings.servers(defaults))
    }

    @Test
    fun `jokainen palvelin tallentuu omaan paikkaansa`() {
        val settings = SyncSettings(MemoryPrefs())

        ServerKind.entries.forEach { settings.setServer(it, "https://${it.name.lowercase().replace('_', '-')}.example.fi") }

        assertEquals(
            ServerUrls(
                valhalla = "https://valhalla.example.fi",
                osrm = "https://osrm.example.fi",
                mapStyle = "https://map-style.example.fi",
                photon = "https://photon.example.fi",
                poi = "https://poi.example.fi",
            ),
            settings.servers(defaults),
        )
    }

    @Test
    fun `kelpaamatonta osoitetta ei tallenneta`() {
        val settings = SyncSettings(MemoryPrefs())
        settings.setServer(ServerKind.OSRM, "https://oma.example.fi")

        assertEquals(false, settings.setServer(ServerKind.OSRM, "http://oma.example.fi"))

        assertEquals("https://oma.example.fi", settings.servers(defaults).osrm)
    }

    @Test
    fun `rikkinäinen tallennettu osoite palautuu oletukseen`() {
        val prefs = MemoryPrefs()
        prefs.values["server_OSRM"] = "ei osoite"

        assertEquals(defaults.osrm, SyncSettings(prefs).servers(defaults).osrm)
    }

    @Test
    fun `tyhjä palauttaa palvelimen oletukseen`() {
        val settings = SyncSettings(MemoryPrefs())
        settings.setServer(ServerKind.POI, "https://oma.example.fi")

        assertEquals(true, settings.setServer(ServerKind.POI, "  "))

        assertEquals(defaults.poi, settings.servers(defaults).poi)
    }

    @Test
    fun `oletusten palautus koskee kaikkia`() {
        val settings = SyncSettings(MemoryPrefs())
        ServerKind.entries.forEach { settings.setServer(it, "https://oma.example.fi") }

        settings.resetServers()

        assertEquals(defaults, settings.servers(defaults))
    }

    @Test
    fun `rikkinäinen teema palautuu järjestelmään`() {
        val prefs = MemoryPrefs()
        prefs.values["theme"] = "VIOLETTI"

        assertEquals(ThemeChoice.SYSTEM, SyncSettings(prefs).theme)
    }
}
