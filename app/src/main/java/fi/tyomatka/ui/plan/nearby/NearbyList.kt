package fi.tyomatka.ui.plan.nearby

import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.repo.NEARBY_RADIUS_KM
import fi.tyomatka.data.repo.NearbyData
import fi.tyomatka.ui.appLocale
import fi.tyomatka.ui.poi.heavyOk
import java.text.Collator
import java.util.Locale

/** Kohdelistan etäisyysvyöhyke linnuntietä: [fromKm, toKm), ja uloin myös tasan 50 km. */
enum class NearbyBand(val fromKm: Double, val toKm: Double) {
    NEAR(0.0, 10.0),
    MID(10.0, 20.0),
    FAR(20.0, NEARBY_RADIUS_KM);

    fun contains(km: Double): Boolean = km >= fromKm && (km < toKm || (this == FAR && km <= toKm))
}

/** Kohdelistan valinnat: vyöhyke, laji, Raskas ja haku. */
data class NearbyChoices(
    val band: NearbyBand = NearbyBand.NEAR,
    val category: PoiCategory = PoiCategory.FUEL,
    val heavyOnly: Boolean = false,
    val query: String = "",
)

/**
 * Kohteet-sivun laskettu näkymä: valitun lajin rivit, ladattujen lajien määrät valitulla vyöhykkeellä samoilla Raskas- ja
 * hakurajauksilla kuin lista sekä vyöhykkeiden osumat vain, kun hakukentässä on tekstiä.
 */
data class NearbyView(
    val rows: List<NearbyRow>,
    val categoryCounts: Map<PoiCategory, Int>,
    val bandCounts: Map<NearbyBand, Int>?,
)

fun nearbyView(items: Map<PoiCategory, List<PoiItem>>, reports: List<StationReport>, here: GeoPoint, choices: NearbyChoices): NearbyView {
    fun rows(list: List<PoiItem>) = nearbyRows(list, reports, here, choices.band, choices.heavyOnly, choices.query)
    val selected = items[choices.category].orEmpty()
    return NearbyView(
        rows = rows(selected),
        categoryCounts = items.mapValues { (_, list) -> rows(list).size },
        bandCounts = choices.query.takeIf { it.isNotBlank() }?.let { nearbyBandCounts(selected, reports, here, choices.heavyOnly, it) },
    )
}

/**
 * Rivin kohde tunnisteella: ensin valitun lajin listasta, koska palvelin voi tuoda saman aseman kahteen lajiin eri
 * lajilla (esim. biokaasua myyvä polttoaineasema kaasuhaussa), ja kortin pitää näyttää rivin laji.
 */
fun nearbyItem(items: Map<PoiCategory, List<PoiItem>>, category: PoiCategory, id: String): PoiItem? =
    items[category]?.firstOrNull { it.id == id } ?: items.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }

/** Listan alla näkyvä tila kartan tapaan. */
sealed interface NearbyHint {
    /** Ei verkkoa, rivit välimuistista; [days] vanhimman näkyvän tiedon ikä (0 = alle vuorokausi). */
    data class Stale(val days: Int) : NearbyHint
    /** Ei verkkoa eikä välimuistissa mitään. */
    data object Offline : NearbyHint
    data object Unavailable : NearbyHint
    /** Valitun lajin tiheimmästäkin alisolusta jäi kohteita pois. */
    data object Truncated : NearbyHint
}

/**
 * Ilman verkkoa "kohteita ei voi hakea" vain, kun välimuistissakaan ei ole mitään; muuten vanhan tiedon ilmoitus, myös
 * kun ympyrän tuoreet palat tulivat ilman ikää.
 */
fun nearbyHints(data: NearbyData, category: PoiCategory): List<NearbyHint> = buildList {
    val hasItems = data.items.values.any { it.isNotEmpty() }
    if (data.offline) add(if (hasItems) NearbyHint.Stale(data.cacheAgeDays ?: 0) else NearbyHint.Offline)
    if (data.unavailable) add(NearbyHint.Unavailable)
    if (category in data.truncated) add(NearbyHint.Truncated)
}

/** Listan rivi: kohde, sen tiedot ilmoituksineen ja etäisyys lähtöpisteestä. */
data class NearbyRow(val item: PoiItem, val info: StationInfo, val distanceKm: Double)

private fun nameOrder() = Collator.getInstance(appLocale())

/** Vyöhykkeen rivit: Raskas-rajaus kuten kartalla ja haku; lähin ensin, sitten nimi ja tunniste. */
fun nearbyRows(
    items: List<PoiItem>,
    reports: List<StationReport>,
    here: GeoPoint,
    band: NearbyBand,
    heavyOnly: Boolean,
    query: String,
): List<NearbyRow> {
    val byPoi = reports.groupBy { it.poiId }
    return items.mapNotNull { item ->
        val km = distanceKm(here, item.location)
        if (!band.contains(km) || !nearbyMatches(item, query)) return@mapNotNull null
        val info = StationInfo.of(item, byPoi[item.id].orEmpty())
        if (heavyOnly && !heavyOk(item, info)) null else NearbyRow(item, info, km)
    }.sortedWith(
        compareBy<NearbyRow> { it.distanceKm }
            .thenComparing({ it.item.name ?: it.item.brand ?: "" }, nameOrder())
            .thenBy { it.item.id },
    )
}

/** Hakuosumat vyöhykkeittäin (vyöhykenapit, kun hakukentässä on tekstiä). */
fun nearbyBandCounts(
    items: List<PoiItem>,
    reports: List<StationReport>,
    here: GeoPoint,
    heavyOnly: Boolean,
    query: String,
): Map<NearbyBand, Int> {
    val byPoi = reports.groupBy { it.poiId }
    val counts = NearbyBand.entries.associateWithTo(LinkedHashMap()) { 0 }
    for (item in items) {
        if (!nearbyMatches(item, query)) continue
        val km = distanceKm(here, item.location)
        val band = NearbyBand.entries.firstOrNull { it.contains(km) } ?: continue
        if (heavyOnly && !heavyOk(item, StationInfo.of(item, byPoi[item.id].orEmpty()))) continue
        counts[band] = counts.getValue(band) + 1
    }
    return counts
}

/**
 * Haku: jokaisen välilyönnein erotetun sanan pitää löytyä nimestä, ketjusta tai osoitteesta isoista ja pienistä
 * kirjaimista välittämättä; ä ja ö pysyvät omina kirjaiminaan. Tyhjä haku osuu kaikkiin.
 */
fun nearbyMatches(item: PoiItem, query: String): Boolean {
    val words = query.lowercase(Locale.ROOT).split(' ', '\t').filter(String::isNotEmpty)
    if (words.isEmpty()) return true
    val text = listOfNotNull(item.name, item.brand, item.address).joinToString(" ").lowercase(Locale.ROOT)
    return words.all { it in text }
}
