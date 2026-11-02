package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.data.repo.PoiLoad

/**
 * Jäikö näkyvistä kohteita pois palvelimen 300 rajan takia. Palvelin järjestää raskaan kaluston ensin, joten
 * rekkasuodattimen alaisessa luokassa (huoltoasemat, levähdys) muu kuin rekka-asema vastauksessa tarkoittaa, että kaikki
 * näytettävät rekka-asemat mahtuivat mukaan.
 */
fun poiWindowTruncated(loads: List<Pair<PoiCategory, PoiLoad>>, heavyOnly: Boolean): Boolean = loads.any { (category, load) ->
    load.truncated && !(heavyOnly && category.heavyFiltered() && load.items.any { !it.hgv })
}

/** Sama rajaus kuin näkymän suodattimessa: raskaan kaluston valinta koskee huoltoasemia ja levähdyspaikkoja. */
fun PoiCategory.heavyFiltered(): Boolean = this == PoiCategory.FUEL || this == PoiCategory.REST
