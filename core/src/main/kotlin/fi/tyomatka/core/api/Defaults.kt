package fi.tyomatka.core.api

/** Avointen palvelinten oletusosoitteet; asetukset voivat myöhemmin ylikirjoittaa nämä. */
object Defaults {
    const val OSRM_URL = "https://routing.openstreetmap.de/routed-car"
    const val PHOTON_URL = "https://photon.komoot.io"
    const val VALHALLA_URL = "https://valhalla1.openstreetmap.de"
    const val TILE_STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
    const val TILE_STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
    /** Oma karttakohdepalvelin (Worker + D1); `local.properties`-avain `poi.base.url` ylikirjoittaa. */
    const val POI_URL = "https://poi.jrs8205.com"

    /** Suomi: länsi,etelä,itä,pohjoinen — Photonin bbox-parametri. */
    const val FINLAND_BBOX = "19.0,59.7,31.6,70.1"
}
