package fi.tyomatka.data.repo

import android.content.Context
import fi.tyomatka.core.api.normalizeServerUrl

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

enum class ServerKind { VALHALLA, OSRM, MAP_STYLE, PHOTON, POI }

data class ServerUrls(val valhalla: String, val osrm: String, val mapStyle: String, val photon: String, val poi: String) {
    /** Digitransitin osoitehaku palvelimen kautta, joten sovelluksessa ei tarvita avainta. */
    val geocode: String get() = "${poi.trimEnd('/')}/v1/geocode"
}

interface SyncPrefs {
    fun get(key: String): String?

    fun put(key: String, value: String?)
}

class SharedSyncPrefs(context: Context) : SyncPrefs {
    private val prefs = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** Valinnat, jotka pitää tietää synkronisesti käynnistyksessä: teema ennen ensimmäistä ruutua ja palvelimet ennen riippuvuuksia. */
class SyncSettings(private val prefs: SyncPrefs) {
    var theme: ThemeChoice
        get() = ThemeChoice.entries.firstOrNull { it.name == prefs.get(KEY_THEME) } ?: ThemeChoice.SYSTEM
        set(value) = prefs.put(KEY_THEME, value.name)

    /** Rikkinäinen tallennettu osoite palautuu oletukseen, jotta sovellus käynnistyy aina. */
    fun servers(defaults: ServerUrls): ServerUrls = ServerUrls(
        valhalla = server(ServerKind.VALHALLA) ?: defaults.valhalla,
        osrm = server(ServerKind.OSRM) ?: defaults.osrm,
        mapStyle = server(ServerKind.MAP_STYLE) ?: defaults.mapStyle,
        photon = server(ServerKind.PHOTON) ?: defaults.photon,
        poi = server(ServerKind.POI) ?: defaults.poi,
    )

    /** Omat osoitteet asetussivulle; oletusta käyttävää palvelinta ei ole mukana. */
    fun storedServers(): Map<ServerKind, String> =
        ServerKind.entries.mapNotNull { kind -> server(kind)?.let { kind to it } }.toMap()

    /** Tyhjä palauttaa oletuksen. Palauttaa false, jos osoite ei kelpaa; silloin mikään ei muutu. */
    fun setServer(kind: ServerKind, text: String): Boolean {
        if (text.isBlank()) {
            prefs.put(kind.key, null)
            return true
        }
        val url = normalizeServerUrl(text) ?: return false
        prefs.put(kind.key, url)
        return true
    }

    fun resetServers() = ServerKind.entries.forEach { prefs.put(it.key, null) }

    private fun server(kind: ServerKind): String? = prefs.get(kind.key)?.let(::normalizeServerUrl)

    private val ServerKind.key get() = "server_$name"

    private companion object {
        const val KEY_THEME = "theme"
    }
}
