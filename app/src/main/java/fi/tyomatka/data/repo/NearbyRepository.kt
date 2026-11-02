package fi.tyomatka.data.repo

import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.NearbyCells
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiPart
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** Kohdelistan säde: kauempana olevat katsotaan Kartta-sivulta. */
const val NEARBY_RADIUS_KM = 50.0

/**
 * Kohdelistan tila: [items] vain ladatut lajit, kukin kohde kerran ja enintään [NEARBY_RADIUS_KM]:n päässä; [reports]
 * muiden ilmoitukset (omat yhdistää näkymä); [offline]/[unavailable] jokin pala tuli välimuistista verkkokatkon tai
 * palvelimen eston takia; [cacheAgeDays] vanhimman käytetyn välimuistitiedon ikä; [truncated] lajit, joiden tiheimmästäkin
 * alisolusta jäi kohteita pois.
 */
data class NearbyData(
    val items: Map<PoiCategory, List<PoiItem>>,
    val reports: List<StationReport>,
    val loading: Boolean,
    val offline: Boolean = false,
    val unavailable: Boolean = false,
    val cacheAgeDays: Int? = null,
    val truncated: Set<PoiCategory> = emptySet(),
)

/**
 * Kohdelistan lataus puhelimen ympäriltä paloina: ensin valitun lajin palat koko ympyrästä, sitten muiden lajien palat,
 * ilmoitukset ja lopuksi katkenneiden solujen alisolut. Jokainen vaihe näkyy heti, joten lista ja sirujen määrät
 * täydentyvät latauksen edetessä. Verkkokatkossa lajin kohteisiin yhdistetään kaikki ympyrään osuvat välimuistirivit
 * (myös kartalta haetut).
 */
class NearbyRepository internal constructor(
    private val loadMany: suspend (List<PoiPart>) -> Map<PoiPart, PoiLoad>,
    private val cachedNear: suspend (PoiBox, PoiCategory) -> CachedNear,
    private val remote: suspend (PoiBox) -> RemoteReports,
) {
    @Inject
    constructor(poi: PoiRepository, reports: ReportRepository) : this(poi::loadMany, poi::cachedNear, reports::remote)

    fun load(center: GeoPoint, first: PoiCategory): Flow<NearbyData> = flow {
        val cells = NearbyCells.cells(center, NEARBY_RADIUS_KM)
        val area = PoiBox(cells.minOf { it.minLon }, cells.minOf { it.minLat }, cells.maxOf { it.maxLon }, cells.maxOf { it.maxLat })
        val loads = LinkedHashMap<PoiPart, PoiLoad>()
        val fallback = HashMap<PoiCategory, CachedNear>()
        val subloads = HashMap<PoiPart, PoiLoad>()
        var reports = emptyList<StationReport>()

        suspend fun fetch(parts: List<PoiPart>, into: MutableMap<PoiPart, PoiLoad>) {
            into += guarded("lataus", parts.associateWith { PoiLoad(emptyList(), unavailable = true) }) { loadMany(parts) }
            val failed = into.filter { (_, load) -> load.offline || load.unavailable }.keys.map { it.category }.toSet()
            failed.filter { it !in fallback }.forEach { fallback[it] = guarded("välimuisti", CachedNear(emptyList(), emptyMap())) { cachedNear(area, it) } }
        }

        fun snapshot(categories: Collection<PoiCategory>, loading: Boolean): NearbyData {
            val all = loads + subloads
            val ages = mutableListOf<Int>()
            val items = categories.associateWith { category ->
                val ofCategory = all.filterKeys { it.category == category }.values
                // Uusin tieto voittaa hakuhetken mukaan: alle vuorokauden välimuistipala ei ole verkosta juuri saatu, joten
                // kartan uudempi rivi voittaa sen. Tasapelissä järjestys on verkosta tai tuoreesta välimuistista, sitten
                // välimuistihaku, lopuksi virheen takia vanhasta välimuistista tulleet palat.
                val byId = LinkedHashMap<String, Triple<PoiItem, PoiFreshness, Int?>>()
                fun offer(item: PoiItem, fetched: PoiFreshness, age: Int?) {
                    val current = byId[item.id]
                    if (current == null || fetched > current.second) byId[item.id] = Triple(item, fetched, age)
                }
                ofCategory.filter { it.cacheAgeDays == null }.forEach { load -> load.items.forEach { offer(it, load.freshness, null) } }
                fallback[category]?.let { cached -> cached.items.forEach { offer(it, cached.fetched[it.id] ?: PoiFreshness.NONE, cached.ageDays[it.id]) } }
                ofCategory.filter { it.cacheAgeDays != null }.forEach { load -> load.items.forEach { offer(it, load.freshness, load.cacheAgeDays) } }
                // Välimuistin ikä vain näkyvistä kohteista.
                byId.values.filter { (item, _, _) -> distanceKm(center, item.location) <= NEARBY_RADIUS_KM }
                    .map { (item, _, age) -> item.also { age?.let(ages::add) } }
            }
            return NearbyData(
                items = items,
                reports = reports,
                loading = loading,
                offline = all.values.any { it.offline },
                unavailable = all.values.any { it.unavailable },
                cacheAgeDays = ages.maxOrNull(),
                truncated = subloads.filterValues { it.truncated }.keys.map { it.category }.toSet(),
            )
        }

        fetch(cells.map { PoiPart(it, first) }, loads)
        emit(snapshot(listOf(first), loading = true))
        val others = PoiCategory.entries.filter { it != first }
        fetch(others.flatMap { category -> cells.map { PoiPart(it, category) } }, loads)
        emit(snapshot(PoiCategory.entries, loading = true))

        reports = NearbyCells.windows(center, NEARBY_RADIUS_KM).flatMap { window ->
            guarded("ilmoitukset", RemoteReports(emptyList(), version = null, fresh = false)) { remote(window) }.items
        }
        val cut = loads.filterValues { it.truncated }.keys
        val subparts = cut.flatMap { part -> NearbyCells.subcells(part.box, center, NEARBY_RADIUS_KM).map { PoiPart(it, part.category) } }
        Diag.event("kohdelista", "palat ${loads.size}, katkenneet ${cut.size}, alisolut ${subparts.size}")
        if (subparts.isNotEmpty()) {
            emit(snapshot(PoiCategory.entries, loading = true))
            fetch(subparts, subloads)
        }
        emit(snapshot(PoiCategory.entries, loading = false))
    }

    /** Odottamaton virhe (esim. kanta) ei katkaise latausta: vaihe saa [fallback]in, ja lataus päättyy tavallisesti. */
    private suspend fun <T> guarded(what: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Diag.event("kohdelista", "$what ${e.javaClass.simpleName}")
        fallback
    }
}
