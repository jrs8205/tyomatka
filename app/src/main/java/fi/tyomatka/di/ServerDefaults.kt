package fi.tyomatka.di

import fi.tyomatka.BuildConfig
import fi.tyomatka.core.api.Defaults
import fi.tyomatka.data.repo.ServerKind
import fi.tyomatka.data.repo.ServerUrls

/** Oletuspalvelimet. Kehityksen `poi.base.url` korvaa kohdepalvelimen oletuksen. */
internal fun defaultServers() = ServerUrls(
    valhalla = Defaults.VALHALLA_URL,
    osrm = Defaults.OSRM_URL,
    mapStyle = Defaults.TILE_STYLE_LIGHT,
    photon = Defaults.PHOTON_URL,
    poi = BuildConfig.POI_BASE_URL.ifBlank { Defaults.POI_URL },
)

internal fun defaultServerMap(): Map<ServerKind, String> = defaultServers().let {
    mapOf(
        ServerKind.VALHALLA to it.valhalla,
        ServerKind.OSRM to it.osrm,
        ServerKind.MAP_STYLE to it.mapStyle,
        ServerKind.PHOTON to it.photon,
        ServerKind.POI to it.poi,
    )
}
