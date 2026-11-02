package fi.tyomatka.core.poi

import fi.tyomatka.core.model.GeoPoint

/** Karttakohteen luokka; [apiName] on palvelimen `cat`-arvo. */
enum class PoiCategory(val apiName: String) {
    FUEL("fuel"),
    CHARGING("charging"),
    GAS("gas"),
    REST("rest");

    companion object {
        fun of(apiName: String): PoiCategory? = entries.firstOrNull { it.apiName == apiName }
    }
}

/**
 * Karttakohde palvelimelta (OpenStreetMap, ODbL). [tags] on valittu tagijoukko pienin avaimin; [hgv] on palvelimen
 * laskema raskaan kaluston lippu (HGV-diesel, AdBlue, rekkapysäköinti).
 */
data class PoiItem(
    val id: String,
    val category: PoiCategory,
    val location: GeoPoint,
    val name: String?,
    val brand: String?,
    val address: String?,
    val hgv: Boolean,
    val tags: Map<String, String>,
)

/** Kartan ikkuna asteina: länsi, etelä, itä, pohjoinen. */
data class PoiBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

/** Yhteishaun pala: yksi ruudukon ikkuna ja yksi luokka, kuten erillisessä haussa. */
data class PoiPart(val box: PoiBox, val category: PoiCategory)
