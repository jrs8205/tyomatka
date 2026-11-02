package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import fi.tyomatka.core.api.ApiException
import fi.tyomatka.core.api.DeviceForgottenException
import fi.tyomatka.core.api.ReportClient
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.ReportRank
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.db.PendingReportEntity
import fi.tyomatka.data.db.PoiCacheEntity
import fi.tyomatka.data.db.PoiCacheKey
import fi.tyomatka.data.db.PoiCorrectionEntity
import fi.tyomatka.data.db.PoiDao
import fi.tyomatka.data.db.ReportDao
import fi.tyomatka.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ikkunan muiden ilmoitukset; [version] on sisällön versio (sen haun aloitusjärjestys, joka toi sisällön; null = ei
 * sisältöä) ja [fresh] tosi, kun palvelin vastasi nyt. Versio ei riipu kellosta, joten saman sekunnin haut ja kellon
 * siirto eivät sotke järjestystä.
 */
data class RemoteReports(val items: List<StationReport>, val version: Long?, val fresh: Boolean)

/** Ilmoituksen lopputulos: palvelin kuittasi, jäi jonoon verkkoa odottamaan vai hylkäsi palvelin sen (ei tallennettu). */
enum class ReportOutcome { SENT, QUEUED, REJECTED }

/**
 * Kuljettajien ilmoitukset: ilmoitus menee ensin laitteen jonoon ja lähtee heti, kun verkko vastaa. Jonossa oleva oma
 * ilmoitus näkyy kortissa heti. Palvelin kuittaa saman `rid`:n uusinnan, joten aikakatkaisun jälkeinen uusinta ei tuota
 * toista riviä. Ilmoittaja on satunnainen laitetunniste (DataStore), ei kukaan tunnistettava.
 */
@Singleton
class ReportRepository internal constructor(
    private val dao: ReportDao,
    private val poiDao: PoiDao,
    private val client: ReportClient,
    private val dataStore: DataStore<Preferences>,
    private val zone: ZoneId,
    private val nowSec: () -> Long,
    private val newId: () -> String,
) {
    @Inject
    constructor(dao: ReportDao, poiDao: PoiDao, client: ReportClient, dataStore: DataStore<Preferences>) :
        this(dao, poiDao, client, dataStore, ZoneId.systemDefault(), { System.currentTimeMillis() / 1000 }, { UUID.randomUUID().toString() })

    private val sending = Mutex()
    private val caching = Mutex()
    /** Hakujen aloitusjärjestys ja kunkin välimuistiavaimen sisällön versio (vain [caching]-lukon alla). */
    private val started = AtomicLong()
    private val versions = mutableMapOf<String, Long>()
    /** Tätä vanhemmat haut alkoivat ennen omien ilmoitusten poistoa, joten niiden vastausta ei näytetä eikä tallenneta. */
    private var cacheFloor = 0L
    private val _forgotten = MutableStateFlow(0L)

    /**
     * Palvelimen hylkäämien ilmoitusten tunnukset, joista käyttäjälle ei ole vielä kerrottu. Tieto on pysyvä, koska hylkäys voi
     * tulla, kun lomake on jo suljettu, näkymä vaihtunut tai ilmoitus odottanut jonossa pitkään.
     */
    val rejectedUnseen: Flow<Set<String>> = dataStore.data.map { it[KEY_REJECTED_RIDS].orEmpty() }

    /** Vain näytetyt ([seen]): kuittauksen jälkeen hylätty jää kerrottavaksi. */
    suspend fun acknowledgeRejected(seen: Set<String>) {
        dataStore.edit { it[KEY_REJECTED_RIDS] = it[KEY_REJECTED_RIDS].orEmpty() - seen }
    }

    /** Kasvaa jokaisesta onnistuneesta poistosta: näkymät hylkäävät muistissa olevat ilmoituksensa ja hakevat ne uudelleen. */
    val forgotten: StateFlow<Long> = _forgotten.asStateFlow()

    /**
     * Ikkunan muiden ilmoitukset palvelimelta. Verkkokatkossa ne tulevat ikkunan viimeisimmästä vastauksesta (laitteen
     * välimuisti, sama 30 päivän siivous kuin kohteilla), jotta esimerkiksi "Asema suljettu" ei katoa kortista. Rivien
     * [StationReport.rank] on vastauksen hakuhetki (pyynnön alku, välimuistissa alkuperäinen), joten vastaus voittaa vain
     * ne saman tekohetken omat ilmoitukset, jotka kuitattiin ennen sen hakua. Jonoa ei lähetetä tällä polulla ([flush]).
     */
    suspend fun remote(box: PoiBox): RemoteReports {
        val key = REPORTS_PREFIX + String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", box.minLon, box.minLat, box.maxLon, box.maxLat)
        val version = started.incrementAndGet()
        val requestedAt = nowSec()
        val result = try {
            val raw = client.windowRaw(box)
            // Jäsennys ennen tallennusta: viallinen vastaus (esim. välityspalvelimen virhesivu) ei korvaa hyvää riviä.
            val items = client.parseWindow(raw)
            val response = RemoteReports(ranked(items, requestedAt), version, fresh = true)
            // Kartta ja ajonäkymä hakevat samaa välimuistia: aiemmin alkanut hidas haku ei korvaa myöhemmin alkaneen
            // haun riviä, vaan tulos on silloin se uudempi rivi.
            caching.withLock {
                val current = poiDao.cache(key)
                val currentVersion = cacheVersion(key, current)
                if (version <= cacheFloor) {
                    // Haku alkoi ennen poistoa: vastaus voi sisältää poistetut ilmoitukset, joten sitä ei näytetä eikä tallenneta.
                    RemoteReports(emptyList(), version = null, fresh = false)
                } else if (current != null && currentVersion > version) {
                    parsedCache(current, currentVersion, fresh = true) ?: response
                } else {
                    // Rivi ja sen versio yhdessä: peruttu haku ei saa jättää riviä ilman versiota.
                    withContext(NonCancellable) {
                        poiDao.putCache(PoiCacheEntity(key, raw, requestedAt))
                        versions[key] = version
                    }
                    response
                }
            }
        } catch (e: IOException) {
            cached(key, box, e)
        } catch (e: SerializationException) {
            cached(key, box, e)
        }
        dao.pruneSent(nowSec() - SENT_KEEP_SEC)
        return result
    }

    /**
     * Verkkokatkossa kaikki alle 30 päivän ilmoitusikkunat, jotka leikkaavat aluetta [box]: listan ikkuna ei ole koskaan
     * sama kuin kartan, ja hieman siirtynyt kartan ikkuna näyttää näin aiemmin haetut ilmoitukset. Avaimista valitaan
     * alueeseen osuvat rivit: täsmälleen sama avain aina ja muista vain sitä uudemmat (se kattaa koko alueen, joten
     * vanhempi rivi toisi takaisin jo poistetun ilmoituksen), uusin ensin enintään [MAX_MERGED_ROWS], ja uudemman valitun
     * rivin sisään jäävä rivi ohitetaan, jottei se vie paikkaa alueen muulta riviltä. Kohteen ja kentän mukaan jää uusin
     * (tekohetki, tasapelissä suurempi `rank` eli uudempi haku). Versio on saman avaimen rivin versio tai muuten
     * yhdistettyjen pienin, jotta vain osittain osuva uusi rivi ei kelpuuta koostetta vartijassa.
     */
    private suspend fun cached(key: String, box: PoiBox, error: Exception): RemoteReports {
        Diag.event("ilmoitus", "ikkuna ${error.javaClass.simpleName} ${(error as? ApiException)?.httpCode ?: ""}".trim())
        val rows = caching.withLock {
            val hits = poiDao.reportCacheKeys(nowSec() - CACHE_KEEP_SEC).mapNotNull { row ->
                val area = keyBox(row.key.removePrefix(REPORTS_PREFIX))
                if (row.key == key || area?.overlaps(box) == true) row to area else null
            }
            // Uutuus haun aloitusjärjestyksestä (versio), ei kellosta: kellon siirto taaksepäin ei tee vanhasta rivistä
            // uudempaa, ja saman sekunnin aikana haettu uudempi rivi tulee mukaan. Edellisen käynnistyksen rivit (versio 0)
            // ovat vanhempia kuin tämän käynnistyksen haut, ja niiden kesken ratkaisee hakuhetki.
            val newer = compareBy<Pair<PoiCacheKey, PoiBox?>>({ versions[it.first.key] ?: 0L }, { it.first.fetchedEpochSec })
            val exact = hits.firstOrNull { it.first.key == key }
            val chosen = listOfNotNull(exact).toMutableList()
            hits.filter { candidate -> candidate.first.key != key && (exact == null || newer.compare(candidate, exact) > 0) }
                .sortedWith(newer.reversed())
                .forEach { candidate ->
                    val area = candidate.second
                    val inside = area != null && chosen.any { it !== exact && it.second?.covers(area) == true }
                    if (chosen.size < MAX_MERGED_ROWS && !inside) chosen += candidate
                }
            chosen.mapNotNull { (row, _) -> poiDao.cache(row.key)?.let { it to cacheVersion(it.key, it) } }
        }
        val parsed = withContext(Dispatchers.Default) {
            rows.mapNotNull { (row, version) -> parsedCache(row, version, fresh = false)?.let { row.key to it } }
        }
        if (parsed.isEmpty()) return RemoteReports(emptyList(), version = null, fresh = false)
        val items = parsed.flatMap { it.second.items }
            .groupBy { it.poiId to it.field }
            .map { (_, same) -> same.maxWith(compareBy<StationReport>({ it.atEpochSec }, { it.rank })) }
        val version = parsed.firstOrNull { it.first == key }?.second?.version ?: parsed.minOf { it.second.version ?: 0L }
        return RemoteReports(items, version, fresh = false)
    }

    /** Välimuistirivin versio; edellisen käynnistyksen rivi on vanhempi kuin mikään tämän käynnistyksen haku (0). */
    private fun cacheVersion(key: String, row: PoiCacheEntity?): Long = if (row == null) 0L else versions[key] ?: 0L

    private fun parsedCache(row: PoiCacheEntity, version: Long, fresh: Boolean): RemoteReports? =
        runCatching { RemoteReports(ranked(client.parseWindow(row.json), row.fetchedEpochSec), version, fresh) }.getOrNull()

    /** Ikkunan ilmoitukset ja omat (jonossa olevat ja viikon sisällä kuitatut) yhtenä listana. */
    suspend fun window(box: PoiBox): List<StationReport> {
        val remote = remote(box).items
        // Jono ensin: jos lähetys kuittaa rivin kyselyjen välissä, rivi tulee kahdesti eikä katoa.
        val pending = dao.pending()
        return remote + own(pending + dao.sent())
    }

    /** Omat ilmoitukset kannasta: uusi tai kuitattu näkyy heti, vaikka ikkunahaku epäonnistuisi tai hylättäisiin. */
    fun observeOwn(): Flow<List<StationReport>> = dao.observeAll().map(::own)

    /** Jonoon ja heti lähetysyritys. Palvelimen hylkäämä rivi poistuu jonosta. Arvon pitää kelvata kentälle ([ReportField.accepts]). */
    suspend fun report(poiId: String, field: ReportField, value: String): ReportOutcome {
        require(field.accepts(value)) { "kelvoton arvo kentälle ${field.key}" }
        val rid = newId()
        dao.insert(PendingReportEntity(rid, poiId, field.key, value, nowSec()))
        Diag.event("ilmoitus", "jonoon ${field.key}")
        flush()
        val row = dao.row(rid) ?: return ReportOutcome.REJECTED
        return if (row.sentEpochSec != null) ReportOutcome.SENT else ReportOutcome.QUEUED
    }

    /**
     * Lähettää jonon vanhin ensin; palauttaa lähetettyjen määrän. 4xx (paitsi 429) poistaa, raja tai verkko jättää jonoon.
     * Jos palvelin on jo poistanut tämän tunnuksen ilmoitukset mutta poiston vastaus jäi saamatta, tunnus vaihdetaan ja sama
     * ilmoitus lähetetään uudella. Käsittelemättömät korjaukset viedään ensin jonoon, koska vaihto merkitsee kaikki
     * käsitellyiksi, eikä käyttäjä ole pyytänyt niiden hylkäämistä.
     */
    suspend fun flush(): Int = sending.withLock {
        var device = deviceId()
        var sent = 0
        for (row in dao.pending()) {
            try {
                try {
                    send(row, device)
                } catch (_: DeviceForgottenException) {
                    Diag.event("ilmoitus", "tunnus poistettu palvelimelta")
                    // Sama joukko kummallekin: tuonti kirjoittaa korjauksia tästä lukosta riippumatta, eikä välissä tullut korjaus saa merkityä käsitellyksi.
                    val corrections = poiDao.allCorrections()
                    queueCorrections(corrections)
                    // Jonossa voi yhä olla korjauksia vanhalla tunnisteella: ne pysyvät tunnistettavina vanhoina korjauksina.
                    device = renewDevice(corrections, keepOld = true)
                    send(row, device)
                }
                // Kuitattu rivi jää omaksi tiedoksi, kunnes palvelimen vastaus varmasti sisältää sen (viikko).
                dao.markSent(row.rid, nowSec())
                sent++
            } catch (e: ApiException) {
                val code = e.httpCode
                if (code != null && code in 400..499 && code != 429) {
                    // Tunnus talteen ennen poistoa: jos poisto epäonnistuu, uusinta kertoo saman hylkäyksen yhtenä. Peruutus ei saa
                    // jättää riviä jonoon, koska kuittaus välissä kertoisi sen uudelleen.
                    withContext(NonCancellable) {
                        if (!isOldCorrection(row.rid)) dataStore.edit { it[KEY_REJECTED_RIDS] = it[KEY_REJECTED_RIDS].orEmpty() + row.rid }
                        dao.delete(row.rid)
                    }
                    Diag.event("ilmoitus", "hylätty $code ${row.field}")
                    continue
                }
                Diag.event("ilmoitus", "virhe ${code ?: e.javaClass.simpleName}, jonossa ${dao.pending().size}")
                break
            } catch (e: IOException) {
                Diag.event("ilmoitus", "verkkovirhe ${e.javaClass.simpleName}, jonossa ${dao.pending().size}")
                break
            }
        }
        if (sent > 0) Diag.event("ilmoitus", "lähetetty $sent")
        sent
    }

    /** Vanha korjaus, joka siirrettiin jonoon automaattisesti: kuljettaja ei ole juuri tehnyt sitä, joten hylkäystä ei kerrota. */
    private suspend fun isOldCorrection(rid: String): Boolean = rid in dataStore.data.first()[KEY_MIGRATED_RIDS].orEmpty()

    /** Tekohetki kulkee aina: jonossa ollut ilmoitus järjestyy sen mukaan, ei lähetyshetken. */
    private suspend fun send(row: PendingReportEntity, device: String) {
        client.send(row.rid, row.poiId, row.field, row.value, device, at = Instant.ofEpochSecond(row.createdEpochSec))
    }

    /**
     * Laitteen omat korjaukset jonoon alkuperäisin päivin, kukin kerran: tunniste lasketaan rivin sisällöstä,
     * joten myös myöhemmin varmuuskopiosta palautetut rivit siirtyvät, eikä keskeytynyt siirto lähetä kahdesti (palvelin
     * kuittaa saman tunnisteen). AdBlue "on" ei siirry, koska ilmoitus erottaa pumpun ja kanisterin; muut korjauskentät
     * siirtyvät samannimisiin (ruoka → ravintola).
     */
    suspend fun migrateLocalEdits(): Int = sending.withLock { queueCorrections(poiDao.allCorrections()) }

    /** Vain [sending]-lukon alla. */
    private suspend fun queueCorrections(corrections: List<PoiCorrectionEntity>): Int {
        val done = dataStore.data.first()[KEY_MIGRATED_RIDS].orEmpty()
        // Laitetunniste mukaan: tunnistetta ei voi päätellä julkisesta tiedosta (ei ennalta lähetettävissä), eivätkä kahden
        // laitteen samanlaiset korjaukset törmää palvelimen kaksoiskappalesuojaan.
        val rows = correctionRows(deviceId(), corrections)
        val fresh = rows.filter { it.rid !in done }
        if (fresh.isEmpty()) return 0
        fresh.forEach { dao.insert(it) }
        dataStore.edit { it[KEY_MIGRATED_RIDS] = done + fresh.map { row -> row.rid } }
        Diag.event("ilmoitus", "omat korjaukset jonoon ${fresh.size}")
        return fresh.size
    }

    private fun correctionRows(device: String, corrections: List<PoiCorrectionEntity>): List<PendingReportEntity> = corrections.mapNotNull { c ->
        val (field, value) = correction(c.field, c.value) ?: return@mapNotNull null
        PendingReportEntity(stableId(device, "correction", c.poiId, c.field, c.value, c.epochSec), c.poiId, field.key, value, c.epochSec)
    }

    /**
     * Poistaa laitteen ilmoitukset palvelimelta ja jonosta ja vaihtaa laitetunnuksen, jolloin vanhoja ilmoituksia ei voi
     * enää yhdistää tähän laitteeseen. Epäonnistuessa mikään ei muutu. Laitteen vanhat korjaukset merkitään käsitellyiksi
     * uudella tunnuksella, jottei niitä lähetetä uudelleen.
     */
    suspend fun forgetMine(): Boolean = sending.withLock {
        // Käyttäjän vahvistama poisto viedään loppuun, vaikka näkymä suljetaan kesken: palvelimelta poistetut rivit eivät saa
        // jäädä jonoon lähetettäväksi uudelleen. Verkkokutsulla on aikaraja, joten tämä ei jää roikkumaan.
        withContext(NonCancellable) {
            try {
                client.forget(deviceId())
            } catch (e: ApiException) {
                Diag.event("ilmoitus", "poisto epäonnistui ${e.httpCode ?: ""}".trim())
                return@withContext false
            } catch (e: IOException) {
                Diag.event("ilmoitus", "poisto epäonnistui ${e.javaClass.simpleName}")
                return@withContext false
            }
            dao.deleteAll()
            caching.withLock {
                poiDao.deleteReportCache()
                versions.clear()
                cacheFloor = started.get()
            }
            renewDevice(poiDao.allCorrections())
            _forgotten.update { it + 1 }
            Diag.event("ilmoitus", "omat ilmoitukset poistettu")
            true
        }
    }

    /** Uusi laitetunnus; korjausten tunnisteet riippuvat siitä, joten [corrections] merkitään käsitellyiksi, jottei niitä lähetetä uudelleen. */
    private suspend fun renewDevice(corrections: List<PoiCorrectionEntity>, keepOld: Boolean = false): String {
        val fresh = UUID.randomUUID().toString()
        val handled = correctionRows(fresh, corrections).map { it.rid }.toSet()
        dataStore.edit {
            it[KEY_DEVICE] = fresh
            it[KEY_MIGRATED_RIDS] = if (keepOld) it[KEY_MIGRATED_RIDS].orEmpty() + handled else handled
        }
        return fresh
    }

    private fun stableId(vararg parts: Any): String = UUID.nameUUIDFromBytes(parts.joinToString("|").toByteArray()).toString()

    /** Satunnainen UUID v4, luodaan kerran. */
    suspend fun deviceId(): String {
        dataStore.data.first()[KEY_DEVICE]?.let { return it }
        val created = UUID.randomUUID().toString()
        var stored = created
        dataStore.edit { prefs -> stored = prefs[KEY_DEVICE] ?: created.also { prefs[KEY_DEVICE] = it } }
        return stored
    }

    /** Omat ilmoitukset kuljettajan aikavyöhykkeen päivin; kuitattu viikon, lähettämätön aina. */
    private fun own(rows: List<PendingReportEntity>): List<StationReport> {
        val keepFrom = nowSec() - SENT_KEEP_SEC
        return rows.filter { (it.sentEpochSec ?: Long.MAX_VALUE) >= keepFrom }.mapNotNull { row ->
            val day = Instant.ofEpochSecond(row.createdEpochSec).atZone(zone).toLocalDate().toEpochDay()
            val rank = row.sentEpochSec?.let(ReportRank::sent) ?: ReportRank.PENDING
            ReportField.of(row.field)?.let { StationReport(row.poiId, it, row.value, day, atEpochSec = row.createdEpochSec, rank = rank) }
        }
    }

    private fun ranked(items: List<StationReport>, fetchedEpochSec: Long): List<StationReport> =
        items.map { it.copy(rank = ReportRank.server(fetchedEpochSec)) }

    private fun correction(key: String, value: String): Pair<ReportField, String>? {
        val field = when (key) {
            "food" -> ReportField.RESTAURANT
            "adblue" -> if (value == "no") ReportField.ADBLUE else return null
            else -> ReportField.of(key) ?: return null
        }
        val normalized = value.trim().replace(',', '.')
        return (field to normalized).takeIf { field.accepts(normalized) }
    }

    private companion object {
        const val REPORTS_PREFIX = "reports|"
        /** Sama 30 päivän säilytys kuin kohteiden välimuistilla. */
        const val CACHE_KEEP_SEC = PoiRepository.KEEP_SEC
        /** Verkkokatkossa yhdistettävien ikkunarivien enimmäismäärä (jäsennys on suurin kustannus). */
        const val MAX_MERGED_ROWS = 20
        val KEY_DEVICE = stringPreferencesKey("report_device_id")
        val KEY_MIGRATED_RIDS = stringSetPreferencesKey("reports_migrated_rids")
        val KEY_REJECTED_RIDS = stringSetPreferencesKey("reports_rejected_rids")
        const val SENT_KEEP_SEC = 7 * 86_400L
    }
}
