package fi.tyomatka.data.repo

import fi.tyomatka.core.api.PoiClient
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiCorrectionField
import fi.tyomatka.core.poi.PoiGrid
import fi.tyomatka.core.poi.PoiPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.PoiCacheEntity
import fi.tyomatka.data.db.PoiCacheKey
import fi.tyomatka.data.db.PoiCorrectionEntity
import fi.tyomatka.data.db.PoiDao
import fi.tyomatka.data.db.PoiPriceEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoiRepositoryTest {
    private lateinit var server: MockWebServer
    private val dao = FakePoiDao()
    private val box = PoiBox(24.5, 60.25, 25.0, 60.5)
    private val key = "24.50,60.25,25.00,60.50|fuel"
    private val body = """{"generatedAt":"2026-10-22T05:00:00Z","source":"© OpenStreetMap contributors (ODbL)","items":[""" +
        """{"id":"n1","cat":"fuel","lat":60.33,"lon":25.05,"name":"Neste Truck","hgv":true,"tags":{}}]}"""

    /** Nyt-hetki sekunteina; testit siirtävät sitä. */
    private var now = 10_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(baseUrl: String = server.url("/").toString().removeSuffix("/")) =
        PoiRepository(dao, PoiClient(OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(), baseUrl)) { now }

    private fun cached(ageSec: Long) {
        dao.cache[key] = PoiCacheEntity(key, body, now - ageSec)
    }

    @Test
    fun `tuore välimuisti käytetään ilman verkkoa`() = runTest {
        cached(ageSec = 3_600)

        val load = repository().load(box, PoiCategory.FUEL)

        assertEquals(listOf("n1"), load.items.map { it.id })
        assertNull(load.cacheAgeDays)
        assertFalse(load.offline)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `katkaistu vastaus kertoo katkaisusta verkosta ja välimuistista`() = runTest {
        val truncatedBody = body.removeSuffix("}") + ""","truncated":true}"""
        server.enqueue(MockResponse().setBody(truncatedBody))

        assertTrue(repository().load(box, PoiCategory.FUEL).truncated)
        assertTrue(repository().load(box, PoiCategory.FUEL).truncated)
        assertEquals(1, server.requestCount)
        cached(ageSec = 3_600)
        assertFalse(repository().load(box, PoiCategory.FUEL).truncated)
    }

    @Test
    fun `vanhentunut välimuisti haetaan verkosta ja talletetaan`() = runTest {
        cached(ageSec = PoiRepository.FRESH_SEC + 1)
        server.enqueue(MockResponse().setBody(body))

        val load = repository().load(box, PoiCategory.FUEL)

        assertEquals(listOf("n1"), load.items.map { it.id })
        assertEquals(1, server.requestCount)
        assertEquals(now, dao.cache.getValue(key).fetchedEpochSec)
    }

    @Test
    fun `verkkovirheessä alle 30 päivän välimuisti näytetään huomautuksella`() = runTest {
        cached(ageSec = 25L * 24 * 3600 + 60)

        val load = repository("http://127.0.0.1:1").load(box, PoiCategory.FUEL)

        assertEquals(listOf("n1"), load.items.map { it.id })
        assertEquals(25, load.cacheAgeDays)
        assertTrue(load.offline)
        assertFalse(load.unavailable)
    }

    @Test
    fun `verkkovirheessä yli 30 päivän välimuistia ei näytetä`() = runTest {
        cached(ageSec = 31L * 24 * 3600)

        val load = repository("http://127.0.0.1:1").load(box, PoiCategory.FUEL)

        assertTrue(load.items.isEmpty())
        assertTrue(load.offline)
        assertNull(load.cacheAgeDays)
    }

    @Test
    fun `kiintiö täynnä kertoo, ettei palvelu ole saatavilla, ja näyttää välimuistin`() = runTest {
        cached(ageSec = 2L * 24 * 3600)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"ip"}"""))

        val load = repository().load(box, PoiCategory.FUEL)

        assertTrue(load.unavailable)
        assertFalse(load.offline)
        assertEquals(listOf("n1"), load.items.map { it.id })
        assertEquals(2, load.cacheAgeDays)
    }

    @Test
    fun `siivous poistaa vain yli 30 päivän rivit`() = runTest {
        dao.cache["a"] = PoiCacheEntity("a", body, now - 29L * 24 * 3600)
        dao.cache["b"] = PoiCacheEntity("b", body, now - 31L * 24 * 3600)

        assertEquals(1, repository().prune())
        assertEquals(setOf("a"), dao.cache.keys)
    }

    @Test
    fun `korjaukset tallentuvat kentittäin ja null poistaa`() = runTest {
        val repository = repository()
        repository.saveCorrections("n1", mapOf(PoiCorrectionField.SHOWER to "yes", PoiCorrectionField.MAX_HEIGHT to "4.2"))
        assertEquals(mapOf(PoiCorrectionField.SHOWER to "yes", PoiCorrectionField.MAX_HEIGHT to "4.2"), repository.observeCorrections("n1").first())

        repository.saveCorrections("n1", mapOf(PoiCorrectionField.SHOWER to null))
        assertEquals(mapOf(PoiCorrectionField.MAX_HEIGHT to "4.2"), repository.observeCorrections("n1").first())
        assertEquals(now, dao.corrections.getValue("n1" to "maxheight").epochSec)
    }

    /** [count] erillistä 0,05°:n palaa riviin Vantaan kohdalla. */
    private fun parts(count: Int, category: PoiCategory = PoiCategory.FUEL) = List(count) { i ->
        PoiPart(PoiBox((480 + i) / 20.0, 60.25, (481 + i) / 20.0, 60.3), category)
    }

    private fun partBody(id: String) =
        """{"generatedAt":"2026-10-29T05:00:00Z","source":"s","items":[{"id":"$id","cat":"fuel","lat":60.3,"lon":24.8,"hgv":false,"tags":{}}],"truncated":false}"""

    /** Yhteishaun vastaus: jokaiselle pyydetylle palalle osa, jonka kohteen tunniste on palan bbox. [failFirst] → 503 ensin. */
    private fun batchServer(failFirst: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            private var calls = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failFirst && calls++ == 0) return MockResponse().setResponseCode(503).setBody("""{"error":"paused"}""")
                val bboxes = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("parts").jsonArray
                    .map { it.jsonObject.getValue("bbox").jsonPrimitive.content }
                return MockResponse().setBody("""{"parts":[${bboxes.joinToString(",") { partBody(it) }}]}""")
            }
        }
    }

    @Test
    fun `yhteishaku lähtee 50 palan erissä ja jokainen pala tallentuu omalle avaimelleen`() = runTest {
        batchServer()
        val parts = parts(150)

        val loads = repository().loadMany(parts)

        assertEquals(3, server.requestCount)
        assertEquals(150, dao.cache.size)
        assertEquals(parts.toSet(), loads.keys)
        parts.forEach { part ->
            val key = PoiGrid.cacheKey(part.box, part.category)
            assertEquals(now, dao.cache.getValue(key).fetchedEpochSec)
            assertEquals(listOf(key.substringBefore('|')), loads.getValue(part).items.map { it.id })
            assertEquals(loads.getValue(part).items, PoiClient.parse(dao.cache.getValue(key).json).items)
        }
    }

    @Test
    fun `tuore pala tulee välimuistista eikä lähde verkkoon`() = runTest {
        batchServer()
        val (fresh, stale) = parts(2)
        val freshKey = PoiGrid.cacheKey(fresh.box, fresh.category)
        dao.cache[freshKey] = PoiCacheEntity(freshKey, body, now - 3_600)

        val loads = repository().loadMany(listOf(fresh, stale))

        assertEquals(listOf("n1"), loads.getValue(fresh).items.map { it.id })
        assertEquals(1, server.requestCount)
        val sent = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertFalse(sent.contains(freshKey.substringBefore('|')))
        assertTrue(sent.contains(PoiGrid.cacheKey(stale.box, stale.category).substringBefore('|')))
    }

    @Test
    fun `palvelin ei saatavilla, erän palat vanhasta välimuistista, muut erät verkosta`() = runTest {
        batchServer(failFirst = true)
        val parts = parts(150)
        val oldKey = PoiGrid.cacheKey(parts[0].box, parts[0].category)
        dao.cache[oldKey] = PoiCacheEntity(oldKey, body, now - 2 * 86_400 - 60)

        val loads = repository().loadMany(parts)

        val first = loads.getValue(parts[0])
        assertEquals(listOf("n1"), first.items.map { it.id })
        assertTrue(first.unavailable)
        assertEquals(2, first.cacheAgeDays)
        assertTrue(loads.getValue(parts[1]).unavailable)
        assertTrue(loads.getValue(parts[1]).items.isEmpty())
        val second = loads.getValue(parts[120])
        assertFalse(second.unavailable)
        assertEquals(1, second.items.size)
        assertEquals(1 + 100, dao.cache.size)
    }

    @Test
    fun `rikkinäinen vanha välimuistirivi ei kaada yhteishakua`() = runTest {
        batchServer(failFirst = true)
        val parts = parts(150)
        val brokenKey = PoiGrid.cacheKey(parts[0].box, parts[0].category)
        dao.cache[brokenKey] = PoiCacheEntity(brokenKey, "{rikki", now - 2 * 86_400)

        val loads = repository().loadMany(parts)

        assertTrue(loads.getValue(parts[0]).items.isEmpty())
        assertTrue(loads.getValue(parts[0]).unavailable)
        assertEquals(1, loads.getValue(parts[120]).items.size)
    }

    @Test
    fun `palvelimen virhevastaus yhteishakuun ei näy verkkokatkona`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))

        val loads = repository().loadMany(parts(2))

        assertTrue(loads.values.all { it.unavailable && !it.offline })
    }

    @Test
    fun `verkkokatkossa erän palat ovat offline`() = runTest {
        val loads = repository("http://127.0.0.1:1").loadMany(parts(2))

        assertTrue(loads.values.all { it.offline && !it.unavailable && it.items.isEmpty() })
    }

    @Test
    fun `välimuistihaku yhdistää kartan ikkunan ja listan palan, uudempi rivi voittaa`() = runTest {
        fun row(key: String, ageSec: Long, items: String) {
            dao.cache[key] = PoiCacheEntity(key, """{"items":[$items]}""", now - ageSec)
        }
        fun item(id: String, name: String, cat: String = "fuel") =
            """{"id":"$id","cat":"$cat","lat":60.3,"lon":24.8,"name":"$name","hgv":false,"tags":{}}"""
        // Uudempi rivi listautuu ensin: voittaja ratkeaa tuoreudesta eikä listausjärjestyksestä.
        row("24.75,60.25,25.00,60.50|fuel", 3_600, item("n1", "Uusi") + "," + item("n2", "Listalta"))
        row("24.50,60.25,25.00,60.50|fuel", 3 * 86_400L, item("n1", "Vanha") + "," + item("n3", "Kartalta"))
        row("24.75,60.25,25.00,60.50|gas", 3_600, item("g1", "Kaasu", "gas"))
        row("reports|24.50,60.25,25.00,60.50", 3_600, "")
        row("20.00,60.00,20.25,60.25|fuel", 3_600, item("n9", "Kaukana"))
        row("24.50,60.00,24.75,60.25|fuel", PoiRepository.KEEP_SEC + 1, item("n8", "Vanhentunut"))

        dao.loaded.clear()
        val near = repository().cachedNear(PoiBox(24.0, 60.0, 25.5, 60.75), PoiCategory.FUEL)

        assertEquals(listOf("n1" to "Uusi", "n2" to "Listalta", "n3" to "Kartalta"), near.items.map { it.id to it.name }.sortedBy { it.first })
        assertEquals(mapOf("n1" to 0, "n2" to 0, "n3" to 3), near.ageDays)
        assertEquals(
            mapOf("n1" to PoiFreshness(0, now - 3_600), "n2" to PoiFreshness(0, now - 3_600), "n3" to PoiFreshness(0, now - 3 * 86_400L)),
            near.fetched,
        )
        assertEquals(3, near.oldestAgeDays)
        // Vain alueeseen osuvien rivien rungot luetaan.
        assertEquals(setOf("24.50,60.25,25.00,60.50|fuel", "24.75,60.25,25.00,60.50|fuel"), dao.loaded.toSet())
        assertEquals(CachedNear(emptyList(), emptyMap()), repository().cachedNear(PoiBox(10.0, 50.0, 10.25, 50.25), PoiCategory.FUEL))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `palan hakuhetki säilyy verkosta, tuoreesta ja vanhasta välimuistista`() = runTest {
        batchServer()
        val parts = parts(2)
        val freshKey = PoiGrid.cacheKey(parts[0].box, parts[0].category)
        dao.cache[freshKey] = PoiCacheEntity(freshKey, """{"items":[]}""", now - 3_600)

        val loads = repository().loadMany(parts)

        assertEquals(PoiFreshness(0, now - 3_600), loads.getValue(parts[0]).freshness)
        assertEquals(PoiFreshness(1, now), loads.getValue(parts[1]).freshness)

        val oldKey = PoiGrid.cacheKey(parts[1].box, parts[1].category)
        dao.cache[oldKey] = PoiCacheEntity(oldKey, """{"items":[]}""", now - 2 * 86_400)
        val offline = repository("http://127.0.0.1:1").loadMany(listOf(parts[1]))
        assertEquals(PoiFreshness(0, now - 2 * 86_400), offline.getValue(parts[1]).freshness)
    }

    @Test
    fun `kellon siirryttyä taaksepäin tämän käynnistyksen verkkotieto voittaa tulevaisuuteen jääneen vanhan rivin`() = runTest {
        batchServer()
        val part = parts(1)[0]
        val id = "24.00,60.25,24.05,60.30"
        val mapKey = "23.75,60.25,24.25,60.50|fuel"
        dao.cache[mapKey] = PoiCacheEntity(
            mapKey,
            """{"items":[{"id":"$id","cat":"fuel","lat":60.3,"lon":24.8,"name":"Vanha","hgv":false,"tags":{}}]}""",
            now + 600,
        )
        val repo = repository()

        val load = repo.loadMany(listOf(part)).getValue(part)
        val near = repo.cachedNear(part.box, PoiCategory.FUEL)

        assertEquals(listOf(id to null), near.items.map { it.id to it.name })
        assertEquals(load.freshness, near.fetched.getValue(id))
        assertTrue(load.freshness > PoiFreshness(0, now + 600))
        // Sama pala tuoreena välimuistista säilyttää järjestyksensä.
        assertEquals(load.freshness, repo.loadMany(listOf(part)).getValue(part).freshness)
    }

    @Test
    fun `kartan haku saa järjestyksen, joka voittaa edellisen käynnistyksen rivin`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        dao.cache["23.75,60.25,25.25,60.75|fuel"] = PoiCacheEntity("23.75,60.25,25.25,60.75|fuel", body, now + 600)
        val repo = repository()

        val load = repo.load(box, PoiCategory.FUEL)

        assertTrue(load.freshness > PoiFreshness(0, now + 600))
        assertTrue(repo.cachedNear(box, PoiCategory.FUEL).fetched.values.all { it == load.freshness })
    }

    private fun named(name: String) = MockResponse().setBody(body.replace("Neste Truck", name))

    @Test
    fun `välimuistihaku antaa uusimman tiedon, vaikka rivi kirjoitetaan kesken haun`() = runTest {
        val repo = repository()
        server.enqueue(named("Vanha"))
        repo.load(PoiBox(24.75, 60.25, 25.0, 60.5), PoiCategory.FUEL)
        server.enqueue(named("Uusi"))
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.afterPut = { written.complete(Unit); release.await() }
        try {
            // Uudempi haku on kirjoittanut rivinsä mutta ei vielä järjestysnumeroaan, kun välimuistihaku listaa avaimet.
            val writer = async(Dispatchers.IO) { repo.load(box, PoiCategory.FUEL) }
            written.await()
            dao.afterPut = null
            val listed = CompletableDeferred<Thread>()
            dao.afterKeys = { listed.complete(Thread.currentThread()) }
            val reader = async(Dispatchers.IO) { repo.cachedNear(box, PoiCategory.FUEL) }
            val thread = listed.await()
            // Listauksen jälkeen haku pysähtyy ensin kirjoituslukolle: kun säikeen pinossa ei enää ole PoiRepositoryn
            // kehyksiä, haku odottaa lukkoa ja kirjoituksen voi päästää loppuun.
            val repoClass = PoiRepository::class.java.name
            val deadline = System.nanoTime() + 5_000_000_000L
            while (thread.stackTrace.any { it.className == repoClass || it.className.startsWith("$repoClass$") }) {
                check(System.nanoTime() < deadline) { "välimuistihaku ei jäänyt odottamaan lukkoa" }
                Thread.sleep(1)
            }
            release.complete(Unit)
            val stored = writer.await()
            val near = reader.await()

            assertEquals(listOf("n1" to "Uusi"), near.items.map { it.id to it.name })
            assertEquals(stored.freshness, near.fetched.getValue("n1"))
            assertEquals(mapOf("n1" to 0), near.ageDays)
        } finally {
            // Kirjoitus odottaa lukon sisällä peruuttamattomana: vapautus aina, ettei epäonnistunut testi jää jumiin.
            release.complete(Unit)
        }
    }

    @Test
    fun `aiemmin alkanut hidas haku ei korvaa myöhemmin alkaneen haun riviä`() = runTest {
        val arrived = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requests.getAndIncrement() == 0) {
                    arrived.countDown()
                    // Pitkä raja: aikaistunut vastaus kirjoittaisi ensin ja kääntäisi testin väärin vihreäksi.
                    finish.await(30, TimeUnit.SECONDS)
                    named("Vanha")
                } else {
                    named("Uusi")
                }
        }
        val repo = repository()
        try {
            val slow = async(Dispatchers.IO) { repo.load(box, PoiCategory.FUEL) }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            repo.load(box, PoiCategory.FUEL)
            finish.countDown()
            slow.await()

            assertEquals(listOf("Uusi"), PoiClient.parse(dao.cache.getValue(key).json).items.map { it.name })
            assertEquals(listOf("n1" to "Uusi"), repo.cachedNear(box, PoiCategory.FUEL).items.map { it.id to it.name })
        } finally {
            finish.countDown()
        }
    }


    @Test
    fun `tulevaisuuteen päivätty rivi ei ole tuore, vaan haetaan uudelleen`() = runTest {
        batchServer()
        val part = parts(1)[0]
        val partKey = PoiGrid.cacheKey(part.box, part.category)
        dao.cache[partKey] = PoiCacheEntity(partKey, """{"items":[]}""", now + 3_600)

        assertEquals(1, repository().loadMany(listOf(part)).getValue(part).items.size)
        assertEquals(1, server.requestCount)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(body)
        }
        cached(-3_600)
        repository().load(box, PoiCategory.FUEL)
        assertEquals(2, server.requestCount)
    }
}

/** Muistinvarainen PoiDao. */
internal class FakePoiDao : PoiDao {
    val cache = mutableMapOf<String, PoiCacheEntity>()
    val corrections = mutableMapOf<Pair<String, String>, PoiCorrectionEntity>()
    val prices = mutableMapOf<Pair<String, FuelKind>, PoiPriceEntity>()
    private val version = MutableStateFlow(0)

    private fun bump() { version.value++ }

    /** Avaimet, joiden rungon [cache] on lukenut (aluerajaus ei saa lukea muiden rivien runkoja). */
    val loaded = mutableListOf<String>()

    /**
     * Rinnakkaisuustestien koukut: kutsutaan rivin kirjoituksen ja kohdeavainten listauksen jälkeen. [afterPut] ajetaan
     * repositoryn kirjoituslukon sisällä peruuttamattomana, joten keskeyttävä koukku on aina vapautettava.
     */
    var afterPut: (suspend () -> Unit)? = null
    var afterKeys: (suspend () -> Unit)? = null

    override suspend fun putCache(entry: PoiCacheEntity) { cache[entry.key] = entry; afterPut?.invoke() }
    override suspend fun cache(key: String): PoiCacheEntity? { loaded += key; return cache[key] }
    override suspend fun cacheKeys(apiName: String, since: Long): List<PoiCacheKey> =
        cache.values.filter { it.key.endsWith("|$apiName") && it.fetchedEpochSec >= since }.map { PoiCacheKey(it.key, it.fetchedEpochSec) }
            .also { afterKeys?.invoke() }
    override suspend fun reportCacheKeys(since: Long): List<PoiCacheKey> =
        cache.values.filter { it.key.startsWith("reports|") && it.fetchedEpochSec >= since }.map { PoiCacheKey(it.key, it.fetchedEpochSec) }
    override suspend fun deleteReportCache() { cache.keys.removeAll { it.startsWith("reports|") } }
    override suspend fun pruneCache(olderThan: Long): Int {
        val old = cache.filterValues { it.fetchedEpochSec < olderThan }.keys
        old.forEach { cache.remove(it) }
        return old.size
    }
    override suspend fun upsertCorrection(correction: PoiCorrectionEntity) { corrections[correction.poiId to correction.field] = correction; bump() }
    override suspend fun deleteCorrection(poiId: String, field: String) { corrections.remove(poiId to field); bump() }
    override fun observeCorrections(poiId: String): Flow<List<PoiCorrectionEntity>> = version.map { corrections.values.filter { it.poiId == poiId } }
    var beforeCorrections: (suspend () -> Unit)? = null
    /** Ajetaan luvun jälkeen: toinen kirjoittaja ehtii lisätä korjauksen, jota luettu lista ei sisällä. */
    var afterCorrections: (suspend () -> Unit)? = null
    override suspend fun allCorrections(): List<PoiCorrectionEntity> {
        beforeCorrections?.invoke()
        val rows = corrections.values.toList()
        afterCorrections?.invoke()
        return rows
    }
    override suspend fun correction(poiId: String, field: String): PoiCorrectionEntity? = corrections[poiId to field]
    override suspend fun upsertPrice(price: PoiPriceEntity) { prices[price.poiId to price.fuel] = price; bump() }
    override suspend fun allPrices(): List<PoiPriceEntity> = prices.values.toList()
    override suspend fun price(poiId: String, fuel: FuelKind): PoiPriceEntity? = prices[poiId to fuel]
}
