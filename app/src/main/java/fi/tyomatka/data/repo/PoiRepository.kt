package fi.tyomatka.data.repo

import fi.tyomatka.core.api.PoiClient
import fi.tyomatka.core.api.ApiException
import fi.tyomatka.core.api.PoiUnavailableException
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiCorrectionField
import fi.tyomatka.core.poi.PoiGrid
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiPart
import fi.tyomatka.data.db.PoiCacheEntity
import fi.tyomatka.data.db.PoiCorrectionEntity
import fi.tyomatka.data.db.PoiDao
import fi.tyomatka.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Haun tulos: [items] näytettävät kohteet; [cacheAgeDays] kun kohteet tulevat vanhasta välimuistista; [offline] verkko ei
 * vastannut; [unavailable] palvelin pysäytetty tai kiintiö täynnä; [truncated] ikkunasta jäi kohteita pois (lähennä).
 */
data class PoiLoad(
    val items: List<PoiItem>,
    val cacheAgeDays: Int? = null,
    val offline: Boolean = false,
    val unavailable: Boolean = false,
    val truncated: Boolean = false,
    /** Tiedon tuoreus päällekkäisyyksien ratkaisuun: uusin voittaa. */
    val freshness: PoiFreshness = PoiFreshness.NONE,
)

/**
 * Tiedon tuoreusjärjestys: tämän käynnistyksen haut haun aloitusjärjestyksessä ([sequence], kellosta riippumatta), ja
 * edellisten käynnistysten rivit (0) ovat niitä vanhempia ja keskenään hakuhetken ([fetchedEpochSec]) mukaan.
 */
data class PoiFreshness(val sequence: Long, val fetchedEpochSec: Long) : Comparable<PoiFreshness> {
    override fun compareTo(other: PoiFreshness): Int = compareValuesBy(this, other, { it.sequence }, { it.fetchedEpochSec })

    companion object {
        val NONE = PoiFreshness(0, 0)
    }
}

/** Välimuistin kohteet alueelta; [ageDays] ja [fetched] kohteittain sen rivin ikä päivinä ja tuoreus, jonka tieto voitti. */
data class CachedNear(val items: List<PoiItem>, val ageDays: Map<String, Int>, val fetched: Map<String, PoiFreshness> = emptyMap()) {
    val oldestAgeDays: Int? get() = ageDays.values.maxOrNull()
}

/** Välimuistiavaimen alue (`minLon,minLat,maxLon,maxLat`, ennen mahdollista `|luokka`-osaa); null, kun muoto on muu. */
internal fun keyBox(area: String): PoiBox? {
    val values = area.substringBefore('|').split(',').map { it.toDoubleOrNull() ?: return null }
    return if (values.size == 4) PoiBox(values[0], values[1], values[2], values[3]) else null
}

/** Leikkaavatko alueet (reunat mukaan lukien). */
internal fun PoiBox.overlaps(other: PoiBox): Boolean =
    minLon <= other.maxLon && other.minLon <= maxLon && minLat <= other.maxLat && other.minLat <= maxLat

/** Onko [other] kokonaan tämän alueen sisällä. */
internal fun PoiBox.covers(other: PoiBox): Boolean =
    minLon <= other.minLon && other.maxLon <= maxLon && minLat <= other.minLat && other.maxLat <= maxLat

/**
 * Karttakohteet laitteen välimuistin kautta: alle vuorokauden vanha vastaus käytetään ilman verkkoa, verkkokatkossa
 * enintään 30 päivän vanha näytetään huomautuksella. Korjaukset ovat vain laitteella.
 */
@Singleton
class PoiRepository internal constructor(
    private val dao: PoiDao,
    private val client: PoiClient,
    private val nowSec: () -> Long,
) {
    @Inject
    constructor(dao: PoiDao, client: PoiClient) : this(dao, client, { System.currentTimeMillis() / 1000 })

    /** Hakujen aloitusjärjestys ja kunkin tällä käynnistyksellä kirjoitetun välimuistirivin järjestysnumero. */
    private val started = AtomicLong()
    private val sequences = ConcurrentHashMap<String, Long>()
    /** Rivi ja sen järjestysnumero kirjoitetaan yhdessä, jotta numero kuvaa aina tallessa olevaa riviä. */
    private val writing = Mutex()

    private fun freshness(row: PoiCacheEntity) = PoiFreshness(sequences[row.key] ?: 0, row.fetchedEpochSec)

    /**
     * Peruutus (ikkuna suljettu kesken latauksen) ei saa jättää riviä ilman sen järjestysnumeroa, eikä aiemmin alkanut
     * hidas haku korvaa myöhemmin alkaneen haun riviä (kartta ja lista hakevat samaa avainta).
     */
    private suspend fun store(key: String, raw: String, now: Long, sequence: Long) = withContext(NonCancellable) {
        writing.withLock {
            if ((sequences[key] ?: 0L) > sequence) return@withLock
            dao.putCache(PoiCacheEntity(key, raw, now))
            sequences[key] = sequence
        }
    }

    /** Rivi ja sen tuoreus luetaan yhdessä, jottei rinnakkainen kirjoitus anna riville toisen rivin numeroa. */
    private suspend fun cachedRow(key: String): Pair<PoiCacheEntity, PoiFreshness>? =
        writing.withLock { dao.cache(key)?.let { it to freshness(it) } }

    /** Alle vuorokauden vanha rivi; tulevaisuuteen päivätty (kello siirtynyt taaksepäin) haetaan uudelleen. */
    private fun isFresh(row: PoiCacheEntity, now: Long) = now - row.fetchedEpochSec in 0 until FRESH_SEC

    suspend fun load(box: PoiBox, category: PoiCategory): PoiLoad {
        val key = PoiGrid.cacheKey(box, category)
        val now = nowSec()
        val cached = cachedRow(key)?.takeIf { now - it.first.fetchedEpochSec < KEEP_SEC }
        if (cached != null && isFresh(cached.first, now)) {
            val parsed = PoiClient.parse(cached.first.json)
            return PoiLoad(parsed.items, truncated = parsed.truncated, freshness = cached.second)
        }
        val sequence = started.incrementAndGet()
        return try {
            val response = client.fetch(box, category)
            store(key, response.raw, now, sequence)
            Diag.event("kohteet", "haku $key verkosta ${response.items.size} kohdetta")
            PoiLoad(response.items, truncated = response.truncated, freshness = PoiFreshness(sequence, now))
        } catch (e: PoiUnavailableException) {
            Diag.event("kohteet", "haku $key ei saatavilla ${e.httpCode}")
            stale(cached, now, unavailable = true)
        } catch (e: IOException) {
            Diag.event("kohteet", "haku $key verkkovirhe ${e.javaClass.simpleName}")
            stale(cached, now, offline = true)
        }
    }

    /**
     * Monta palaa kerralla (kohdelista): tuoreet välimuistista ilman verkkoa, muut yhteishaulla [PoiClient.MAX_BATCH]in
     * erissä, ja jokainen osa tallentuu omalla avaimellaan kuten [load]in vastaus, joten kartta ja lista jakavat
     * välimuistin. Erän verkkovirhe tai palvelimen esto antaa erän palat vanhasta välimuistista; muut erät jatkuvat.
     * Jäsennys voi olla megatavuja, joten se tehdään taustasäikeessä.
     */
    suspend fun loadMany(parts: List<PoiPart>): Map<PoiPart, PoiLoad> = withContext(Dispatchers.Default) {
        val now = nowSec()
        val result = HashMap<PoiPart, PoiLoad>()
        // Erät palojen järjestyksessä: kutsujan tärkein laji lähtee ensin.
        val old = LinkedHashMap<PoiPart, Pair<PoiCacheEntity, PoiFreshness>?>()
        for (part in parts.distinct()) {
            val cached = cachedRow(PoiGrid.cacheKey(part.box, part.category))?.takeIf { now - it.first.fetchedEpochSec < KEEP_SEC }
            val fresh = cached?.takeIf { isFresh(it.first, now) }?.let { (row, freshness) -> parseOrNull(row.json)?.let { it to freshness } }
            if (fresh != null) {
                result[part] = PoiLoad(fresh.first.items, truncated = fresh.first.truncated, freshness = fresh.second)
            } else {
                old[part] = cached
            }
        }
        for (batch in old.keys.toList().chunked(PoiClient.MAX_BATCH)) {
            val sequence = started.incrementAndGet()
            try {
                val responses = client.fetchBatch(batch)
                batch.zip(responses).forEach { (part, response) ->
                    store(PoiGrid.cacheKey(part.box, part.category), response.raw, now, sequence)
                    result[part] = PoiLoad(response.items, truncated = response.truncated, freshness = PoiFreshness(sequence, now))
                }
                Diag.event("kohteet", "yhteishaku ${batch.size} palaa verkosta ${responses.sumOf { it.items.size }} kohdetta")
            } catch (e: PoiUnavailableException) {
                Diag.event("kohteet", "yhteishaku ${batch.size} palaa ei saatavilla ${e.httpCode}")
                batch.forEach { result[it] = stale(old[it], now, unavailable = true) }
            } catch (e: IOException) {
                // Palvelimen virhevastaus (esim. 404 tai 500) ei ole verkkokatko: "Ei verkkoa" olisi väärä tieto.
                val server = (e as? ApiException)?.httpCode
                Diag.event("kohteet", "yhteishaku ${batch.size} palaa ${if (server != null) "palvelinvirhe $server" else "verkkovirhe ${e.javaClass.simpleName}"}")
                batch.forEach { result[it] = if (server != null) stale(old[it], now, unavailable = true) else stale(old[it], now, offline = true) }
            }
        }
        parts.associateWith { result.getValue(it) }
    }

    /**
     * Luokan kohteet kaikista alle 30 päivän välimuistiriveistä, joiden alue leikkaa aluetta [area] (kartan ikkunat ja
     * listan palat). Sama kohde useassa rivissä tulee kerran, ja uusimman rivin ([PoiFreshness]) tieto voittaa. Alue rajataan avaimista,
     * ja rungot luetaan ja jäsennetään rivi kerrallaan, jottei kuukauden välimuisti ole kerralla muistissa.
     */
    suspend fun cachedNear(area: PoiBox, category: PoiCategory): CachedNear = withContext(Dispatchers.Default) {
        val now = nowSec()
        val keys = dao.cacheKeys(category.apiName, now - KEEP_SEC).filter { keyBox(it.key)?.overlaps(area) == true }
        val items = LinkedHashMap<String, PoiItem>()
        val ages = HashMap<String, Int>()
        val fetched = HashMap<String, PoiFreshness>()
        for (key in keys) {
            val (row, freshness) = cachedRow(key.key) ?: continue
            val parsed = parseOrNull(row.json) ?: continue
            val age = ((now - row.fetchedEpochSec) / DAY_SEC).toInt().coerceAtLeast(0)
            parsed.items.forEach {
                // Voittaja kohteittain rivin lukuhetken tuoreudesta: rivi on voinut uudistua avainten listauksen jälkeen,
                // joten listauksen järjestykseen ei voi luottaa. Tasapeli syntyy vain samalla sekunnilla haetuista
                // riveistä, joiden tieto on sama, joten kumpi tahansa kelpaa.
                if (fetched[it.id]?.let { best -> freshness < best } == true) return@forEach
                items[it.id] = it
                ages[it.id] = age
                fetched[it.id] = freshness
            }
        }
        CachedNear(items.values.toList(), ages, fetched)
    }

    /** Rikkinäinen välimuistirivi ohitetaan kuin sitä ei olisi. */
    private fun parseOrNull(json: String) = try {
        PoiClient.parse(json)
    } catch (e: SerializationException) {
        null
    }

    /** Vanha välimuistirivi virheen sattuessa; rikkinäinen rivi on kuin riviä ei olisi. */
    private fun stale(cached: Pair<PoiCacheEntity, PoiFreshness>?, now: Long, offline: Boolean = false, unavailable: Boolean = false): PoiLoad {
        val parsed = cached?.let { parseOrNull(it.first.json) } ?: return PoiLoad(emptyList(), offline = offline, unavailable = unavailable)
        return PoiLoad(
            parsed.items,
            cacheAgeDays = ((now - cached.first.fetchedEpochSec) / DAY_SEC).toInt().coerceAtLeast(0),
            offline = offline,
            unavailable = unavailable,
            truncated = parsed.truncated,
            freshness = cached.second,
        )
    }

    /** Käynnistyksessä: yli 30 päivän välimuistirivit pois. */
    suspend fun prune(): Int = dao.pruneCache(nowSec() - KEEP_SEC)

    fun observeCorrections(poiId: String): Flow<Map<PoiCorrectionField, String>> =
        dao.observeCorrections(poiId).map { rows ->
            rows.mapNotNull { row -> PoiCorrectionField.of(row.field)?.let { it to row.value } }.toMap()
        }

    /** Null poistaa korjauksen; muut kirjoitetaan nykyhetkellä. */
    suspend fun saveCorrections(poiId: String, values: Map<PoiCorrectionField, String?>, nowSec: Long = this.nowSec()) {
        values.forEach { (field, value) ->
            if (value == null) dao.deleteCorrection(poiId, field.key) else dao.upsertCorrection(PoiCorrectionEntity(poiId, field.key, value, nowSec))
        }
    }

    companion object {
        const val FRESH_SEC = 24 * 3600L
        const val KEEP_SEC = 30 * 24 * 3600L
        private const val DAY_SEC = 24 * 3600L
    }
}
