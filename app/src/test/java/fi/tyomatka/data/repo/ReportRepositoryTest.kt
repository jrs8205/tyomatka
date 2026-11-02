package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import fi.tyomatka.core.api.ReportClient
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.ReportRank
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.PendingReportEntity
import fi.tyomatka.data.db.PoiCacheEntity
import fi.tyomatka.data.db.PoiCorrectionEntity
import fi.tyomatka.data.db.PoiPriceEntity
import fi.tyomatka.data.db.ReportDao
import fi.tyomatka.ui.poi.ReportWindowGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportRepositoryTest {
    private lateinit var server: MockWebServer
    private val dao = FakeReportDao()
    private val poiDao = FakePoiDao()
    private val box = PoiBox(24.5, 60.25, 25.0, 60.5)

    /** 23.10.2026 klo 7.00 UTC sekunteina. */
    private var now = 1_792_738_800L
    private val today = 1_792_738_800L / 86_400
    private var ids = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val station = PoiItem("n1", PoiCategory.FUEL, GeoPoint(60.3, 24.8), "Neste", "Neste", null, false, emptyMap())
    /** Toisen laitteen ilmoitus samalla sekunnilla kuin testin oma (23.10. klo 7.00 UTC). */
    private val otherDevice = """{"poi":"n1","field":"closed","value":"no","date":"2026-10-23","at":"2026-10-23T07:00:00.000Z"}"""

    /** Toisen laitteen myöhempi "Asema suljettu" (klo 7.00.15 UTC). */
    private val closedYesItem = """{"poi":"n1","field":"closed","value":"yes","date":"2026-10-23","at":"2026-10-23T07:00:15.000Z"}"""

    /** Näkymän tila vartijan läpi: hyväksytty tulos korvaa näkyvän. */
    private fun ReportWindowGuard.show(result: RemoteReports, shown: List<StationReport>): List<StationReport> =
        if (accept(begin()!!, result.version)) result.items else shown

    private fun TestScope.repository(callTimeoutMs: Long = 30_000): ReportRepository {
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(2, TimeUnit.SECONDS).build()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(storage = MemoryPreferencesStorage(), scope = backgroundScope)
        val client = ReportClient(http, server.url("/").toString().removeSuffix("/"), ZoneOffset.UTC, callTimeoutMs)
        return ReportRepository(dao, poiDao, client, dataStore, ZoneOffset.UTC, { now }) {
            "00000000-0000-4000-8000-00000000000${ids++}"
        }
    }

    private fun created(date: String = "2026-10-23") = MockResponse().setResponseCode(201).setBody("""{"date":"$date","at":"${date}T07:00:00.000Z"}""")
    private fun window(body: String) = MockResponse().setBody("""{"items":[$body]}""")
    /** Palvelimen vastaus tunnukselle, jonka ilmoitukset on jo poistettu. */
    private fun gone() = MockResponse().setResponseCode(410).setBody("{\"error\":\"tunnus poistettu\"}")
    /** Pyyntö luetaan mutta vastausta ei tule: sovellukselle verkkovirhe, testille kirjattu pyyntö. */
    private fun offline() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("pyyntö puuttuu")
    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `ilmoitus ilman verkkoa jää jonoon ja näkyy heti tämän päivän kuljettajan ilmoituksena`() = runTest {
        repeat(3) { server.enqueue(offline()) }
        val repo = repository()

        assertEquals(ReportOutcome.QUEUED, repo.report("n1", ReportField.CLOSED, "yes"))

        assertEquals(1, dao.rows.size)
        assertEquals(listOf(StationReport("n1", ReportField.CLOSED, "yes", today, atEpochSec = now, rank = ReportRank.PENDING)), repo.window(box))
    }

    @Test
    fun `seuraava lähetys lähettää saman tunnisteen kerran`() = runTest {
        server.enqueue(offline())
        val repo = repository()
        repo.report("n1", ReportField.CLOSED, "yes")
        take()

        server.enqueue(created())
        assertEquals(1, repo.flush())

        val sent = take().json()
        assertEquals("00000000-0000-4000-8000-000000000000", sent["rid"])
        assertEquals("2026-10-23T07:00:00Z", sent["at"])
        assertTrue(dao.rows.values.all { it.sentEpochSec != null })
        assertEquals(0, repo.flush())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `omien ilmoitusten poisto tyhjentää jonon ja vaihtaa laitetunnuksen`() = runTest {
        val repository = repository()
        server.enqueue(created())
        repository.report("n1", ReportField.CLOSED, "yes")
        server.takeRequest(1, TimeUnit.SECONDS)
        server.enqueue(MockResponse().setResponseCode(503))
        repository.report("n2", ReportField.CLOSED, "yes")
        server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals(1, dao.sent().size)
        assertEquals(1, dao.pending().size)
        val old = repository.deviceId()
        server.enqueue(MockResponse().setBody("{\"deleted\":1}"))

        assertTrue(repository.forgetMine())

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/reports/forget", request.requestUrl!!.encodedPath)
        assertEquals(old, request.getHeader("X-Device"))
        assertTrue(dao.pending().isEmpty())
        assertTrue(dao.sent().isEmpty())
        assertTrue(repository.deviceId() != old)
    }

    @Test
    fun `palvelimen jo poistama tunnus vaihdetaan ja ilmoitus lähtee uudella`() = runTest {
        val repository = repository()
        val old = repository.deviceId()
        server.enqueue(gone())
        server.enqueue(created())

        assertEquals(ReportOutcome.SENT, repository.report("n1", ReportField.CLOSED, "yes"))

        val refused = take()
        val accepted = take()
        assertEquals(old, refused.getHeader("X-Device"))
        val fresh = accepted.getHeader("X-Device")!!
        assertTrue(fresh != old)
        assertEquals(refused.json()["rid"], accepted.json()["rid"])
        assertEquals(fresh, repository.deviceId())
        assertTrue(dao.pending().isEmpty())
        assertEquals(1, dao.sent().size)
    }

    @Test
    fun `tunnus vaihdetaan vain kerran saman jonon aikana`() = runTest {
        val repository = repository()
        repeat(2) {
            server.enqueue(offline())
            repository.report("n$it", ReportField.CLOSED, "yes")
            take()
        }
        server.enqueue(gone())
        server.enqueue(created())
        server.enqueue(created())

        assertEquals(2, repository.flush())

        take()
        assertEquals(take().getHeader("X-Device"), take().getHeader("X-Device"))
    }

    @Test
    fun `tunnuksen vaihto ei lähetä vanhoja korjauksia uudelleen`() = runTest {
        val repository = repository()
        poiDao.upsertCorrection(PoiCorrectionEntity("n1", "closed", "yes", now))
        assertEquals(1, repository.migrateLocalEdits())
        server.enqueue(gone())
        server.enqueue(created())
        assertEquals(1, repository.flush())

        assertEquals(0, repository.migrateLocalEdits())
        assertTrue(dao.pending().isEmpty())
    }

    @Test
    fun `tunnuksen vaihto vie käsittelemättömät korjaukset ensin jonoon`() = runTest {
        val repository = repository()
        server.enqueue(offline())
        repository.report("n2", ReportField.CLOSED, "yes")
        take()
        poiDao.upsertCorrection(PoiCorrectionEntity("n1", "closed", "yes", now))
        server.enqueue(gone())
        server.enqueue(created())

        assertEquals(1, repository.flush())

        assertEquals(listOf("n1"), dao.pending().map { it.poiId })
        assertEquals(0, repository.migrateLocalEdits())
        assertEquals(1, dao.pending().size)
    }

    @Test
    fun `tunnuksen vaihdon aikana tuotu korjaus lähtee myöhemmin`() = runTest {
        val repository = repository()
        server.enqueue(offline())
        repository.report("n2", ReportField.CLOSED, "yes")
        take()
        poiDao.upsertCorrection(PoiCorrectionEntity("n1", "closed", "yes", now))
        poiDao.afterCorrections = {
            poiDao.afterCorrections = null
            poiDao.upsertCorrection(PoiCorrectionEntity("n3", "closed", "yes", now))
        }
        server.enqueue(gone())
        server.enqueue(created())

        assertEquals(1, repository.flush())

        assertEquals(1, repository.migrateLocalEdits())
        assertEquals(listOf("n1", "n3"), dao.pending().map { it.poiId }.sorted())
    }

    @Test
    fun `ilmoituksen tulos kertoo lähetyksen, jonon ja hylkäyksen`() = runTest {
        val repository = repository()
        server.enqueue(created())
        assertEquals(ReportOutcome.SENT, repository.report("n1", ReportField.CLOSED, "yes"))
        server.enqueue(offline())
        assertEquals(ReportOutcome.QUEUED, repository.report("n2", ReportField.CLOSED, "yes"))
        take()
        take()
        dao.rows.keys.toList().forEach { dao.delete(it) }
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"tuntematon kohde\"}"))

        assertEquals(ReportOutcome.REJECTED, repository.report("n3", ReportField.CLOSED, "yes"))

        assertTrue(dao.rows.isEmpty())
    }

    private fun unknownPoi() = MockResponse().setResponseCode(404).setBody("""{"error":"tuntematon kohde"}""")

    @Test
    fun `jonossa ollut hylätty ilmoitus jää kerrottavaksi, kunnes se on nähty`() = runTest {
        server.enqueue(offline())
        val repo = repository()
        assertEquals(ReportOutcome.QUEUED, repo.report("n1", ReportField.CLOSED, "yes"))
        assertEquals(0, repo.rejectedUnseen.first().size)

        server.enqueue(unknownPoi())
        repo.flush()

        assertEquals(1, repo.rejectedUnseen.first().size)
        repo.acknowledgeRejected(repo.rejectedUnseen.first())
        assertEquals(0, repo.rejectedUnseen.first().size)
    }

    @Test
    fun `uuden ilmoituksen lähetys kertoo myös vanhan hylkäyksen`() = runTest {
        server.enqueue(offline())
        val repo = repository()
        repo.report("n1", ReportField.CLOSED, "yes")
        now += 10

        server.enqueue(unknownPoi())
        server.enqueue(created())

        assertEquals(ReportOutcome.SENT, repo.report("n2", ReportField.CLOSED, "no"))
        assertEquals(1, repo.rejectedUnseen.first().size)
    }

    @Test
    fun `juuri tehdyn ilmoituksen hylkäys jää kerrottavaksi, vaikka näkymä olisi jo suljettu`() = runTest {
        server.enqueue(unknownPoi())
        val repo = repository()

        assertEquals(ReportOutcome.REJECTED, repo.report("n1", ReportField.CLOSED, "yes"))

        assertEquals(1, repo.rejectedUnseen.first().size)

        server.enqueue(unknownPoi())
        repo.report("n2", ReportField.CLOSED, "yes")
        assertEquals(2, repo.rejectedUnseen.first().size)
    }

    @Test
    fun `tunnuksen vaihdon jälkeen jonossa olevan vanhan korjauksen hylkäystä ei kerrota`() = runTest {
        val repo = repository()
        server.enqueue(offline())
        repo.report("n2", ReportField.CLOSED, "yes")
        take()
        poiDao.upsertCorrection(PoiCorrectionEntity("n1", "closed", "yes", now))
        server.enqueue(gone())
        server.enqueue(created())
        assertEquals(1, repo.flush())
        assertEquals(listOf("n1"), dao.pending().map { it.poiId })

        server.enqueue(unknownPoi())
        repo.flush()

        assertTrue(dao.pending().isEmpty())
        assertEquals(0, repo.rejectedUnseen.first().size)
    }

    @Test
    fun `kuittaus koskee vain näytettyjä, sen jälkeen hylätty jää kerrottavaksi`() = runTest {
        val repo = repository()
        server.enqueue(unknownPoi())
        repo.report("n1", ReportField.CLOSED, "yes")
        val shown = repo.rejectedUnseen.first()

        server.enqueue(unknownPoi())
        repo.report("n2", ReportField.CLOSED, "yes")
        repo.acknowledgeRejected(shown)

        assertEquals(1, repo.rejectedUnseen.first().size)
        assertTrue(repo.rejectedUnseen.first().none { it in shown })
    }

    @Test
    fun `saman rivin uusinta poiston epäonnistuttua kerrotaan yhtenä hylkäyksenä`() = runTest {
        val repo = repository()
        server.enqueue(offline())
        repo.report("n1", ReportField.CLOSED, "yes")
        take()

        server.enqueue(unknownPoi())
        dao.failNextDelete = true
        assertFailsWith<IllegalStateException> { repo.flush() }
        assertEquals(1, dao.pending().size, "rivi jäi jonoon")
        assertEquals(1, repo.rejectedUnseen.first().size)

        server.enqueue(unknownPoi())
        repo.flush()

        assertTrue(dao.pending().isEmpty())
        assertEquals(1, repo.rejectedUnseen.first().size)
    }

    @Test
    fun `vanhan korjauksen hylkäys ei ole kuljettajan juuri tekemä ilmoitus`() = runTest {
        val repo = repository()
        poiDao.upsertCorrection(PoiCorrectionEntity("n1", "closed", "yes", now))
        assertEquals(1, repo.migrateLocalEdits())
        server.enqueue(unknownPoi())

        repo.flush()

        assertTrue(dao.rows.isEmpty())
        assertEquals(0, repo.rejectedUnseen.first().size)
    }

    @Test
    fun `raja tai verkkovirhe ei ole hylkäys`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"ip"}"""))
        val repo = repository()
        repo.report("n1", ReportField.CLOSED, "yes")

        server.enqueue(MockResponse().setResponseCode(500))
        repo.flush()

        assertEquals(0, repo.rejectedUnseen.first().size)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `muun lähteen 410 ei vaihda tunnusta`() = runTest {
        val repository = repository()
        val old = repository.deviceId()
        server.enqueue(MockResponse().setResponseCode(410).setBody("<html>Gone</html>"))

        assertEquals(ReportOutcome.REJECTED, repository.report("n1", ReportField.CLOSED, "yes"))

        assertEquals(old, repository.deviceId())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `uudenkin tunnuksen torjunta jättää ilmoituksen jonoon`() = runTest {
        val repository = repository()
        server.enqueue(gone())
        server.enqueue(gone())

        assertEquals(ReportOutcome.QUEUED, repository.report("n1", ReportField.CLOSED, "yes"))

        assertEquals(1, dao.pending().size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `vahvistamaton poistovastaus ei vaihda tunnusta eikä tyhjennä jonoa`() = runTest {
        val repository = repository()
        server.enqueue(offline())
        repository.report("n1", ReportField.CLOSED, "yes")
        take()
        val old = repository.deviceId()
        server.enqueue(MockResponse().setBody("<html>ok</html>"))

        assertFalse(repository.forgetMine())

        assertEquals(old, repository.deviceId())
        assertEquals(1, dao.pending().size)
    }

    @Test
    fun `hitaasti tippuva poistovastaus katkeaa eikä vaihda tunnusta`() = runTest {
        val repository = repository(callTimeoutMs = 300)
        val old = repository.deviceId()
        // Kelvollinen vahvistus: ilman aikarajaa poisto onnistuisi noin sekunnissa.
        val slow = "{\"deleted\":1}" + " ".repeat(100)
        server.enqueue(MockResponse().setBody(slow).throttleBody(1, 10, TimeUnit.MILLISECONDS))

        assertFalse(withContext(Dispatchers.Default) { repository.forgetMine() })

        assertEquals(old, repository.deviceId())
    }

    @Test
    fun `poistetut ilmoitukset eivät näy verkkokatkossa`() = runTest {
        val repository = repository()
        server.enqueue(window(closedYesItem))
        assertEquals(1, repository.remote(box).items.size)
        server.enqueue(MockResponse().setBody("{\"deleted\":1}"))

        assertTrue(repository.forgetMine())
        server.enqueue(offline())

        assertEquals(emptyList(), repository.remote(box).items)
        assertTrue(poiDao.cache.keys.none { it.startsWith("reports|") })
    }

    @Test
    fun `epäonnistunut poisto säilyttää ilmoitusten välimuistin`() = runTest {
        val repository = repository()
        server.enqueue(window(closedYesItem))
        repository.remote(box)
        server.enqueue(MockResponse().setResponseCode(503))

        assertFalse(repository.forgetMine())

        assertTrue(poiDao.cache.keys.any { it.startsWith("reports|") })
    }

    @Test
    fun `ennen poistoa alkanut haku ei palauta poistettua ilmoitusta välimuistiin`() = runTest {
        val repository = repository()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl!!.encodedPath == "/v1/reports/forget") {
                    MockResponse().setBody("{\"deleted\":1}")
                } else {
                    arrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    window(closedYesItem)
                }
        }
        val running = async(Dispatchers.Default) { repository.remote(box) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))

        assertTrue(repository.forgetMine())
        release.countDown()
        val stale = running.await()

        assertTrue(poiDao.cache.keys.none { it.startsWith("reports|") })
        assertEquals(emptyList(), stale.items, "poistoa edeltävä vastaus ei saa palata näkymään")
        assertEquals(null, stale.version)
        assertFalse(stale.fresh)
    }

    @Test
    fun `poiston jälkeen alkanut haku näkyy ja tallentuu`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody("{\"deleted\":1}"))
        assertTrue(repository.forgetMine())
        server.enqueue(window(closedYesItem))

        val found = repository.remote(box)

        assertEquals(1, found.items.size)
        assertTrue(poiDao.cache.keys.any { it.startsWith("reports|") })
    }

    @Test
    fun `onnistunut poisto ilmoittaa näkymille ja epäonnistunut ei`() = runTest {
        val repository = repository()
        val before = repository.forgotten.value
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(repository.forgetMine())
        assertEquals(before, repository.forgotten.value)

        server.enqueue(MockResponse().setBody("{\"deleted\":1}"))
        assertTrue(repository.forgetMine())

        assertEquals(before + 1, repository.forgotten.value)
    }

    @Test
    fun `kesken oleva korjausten siirto ei palauta poistettuja ilmoituksia`() = runTest {
        val repository = repository()
        poiDao.upsertCorrection(PoiCorrectionEntity("n1", "closed", "yes", now))
        val reached = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        poiDao.beforeCorrections = {
            poiDao.beforeCorrections = null
            reached.complete(Unit)
            go.await()
        }
        val migrating = async(Dispatchers.Default) { repository.migrateLocalEdits() }
        withContext(Dispatchers.Default) { reached.await() }
        server.enqueue(MockResponse().setBody("{\"deleted\":0}"))
        val forgetting = async(Dispatchers.Default) { repository.forgetMine() }
        withContext(Dispatchers.Default) { assertEquals(null, server.takeRequest(2, TimeUnit.SECONDS), "poisto odottaa siirron loppua") }

        go.complete(Unit)
        withContext(Dispatchers.Default) {
            migrating.await()
            forgetting.await()
        }

        assertTrue(dao.pending().isEmpty())
        assertEquals(0, repository.migrateLocalEdits())
        assertTrue(dao.pending().isEmpty())
    }

    @Test
    fun `aloitettu poisto viedään loppuun vaikka kutsuja peruuntuu`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(503))
        repository.report("n1", ReportField.CLOSED, "yes")
        server.takeRequest(1, TimeUnit.SECONDS)
        val old = repository.deviceId()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("{\"deleted\":1}")
            }
        }
        val job = launch(Dispatchers.Default) { repository.forgetMine() }
        withContext(Dispatchers.Default) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }

        job.cancel()
        release.countDown()
        withContext(Dispatchers.Default) { job.join() }

        assertTrue(dao.pending().isEmpty())
        assertTrue(repository.deviceId() != old)
    }

    @Test
    fun `epäonnistunut poisto säilyttää tunnuksen ja jonon`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(503))
        repository.report("n1", ReportField.CLOSED, "yes")
        server.takeRequest(1, TimeUnit.SECONDS)
        val old = repository.deviceId()
        server.enqueue(MockResponse().setResponseCode(429))

        assertFalse(repository.forgetMine())

        assertEquals(old, repository.deviceId())
        assertEquals(1, dao.pending().size)
    }

    @Test
    fun `poisto ilman verkkoa säilyttää tunnuksen`() = runTest {
        val repository = repository()
        val old = repository.deviceId()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertFalse(repository.forgetMine())

        assertEquals(old, repository.deviceId())
    }

    @Test
    fun `poisto ei palauta vanhoja korjauksia lähetettäväksi uudella tunnuksella`() = runTest {
        val repository = repository()
        poiDao.upsertCorrection(PoiCorrectionEntity("n1", "closed", "yes", now))
        assertEquals(1, repository.migrateLocalEdits())
        server.enqueue(MockResponse().setBody("{\"deleted\":0}"))

        assertTrue(repository.forgetMine())

        assertEquals(0, repository.migrateLocalEdits())
        assertTrue(dao.pending().isEmpty())
    }

    @Test
    fun `laitetunniste luodaan kerran ja kulkee otsakkeessa`() = runTest {
        server.enqueue(created())
        server.enqueue(created())
        val repo = repository()
        assertEquals(ReportOutcome.SENT, repo.report("n1", ReportField.SHOP, "no"))
        assertEquals(ReportOutcome.SENT, repo.report("n2", ReportField.SHOP, "yes"))
        val first = take().getHeader("X-Device")
        val second = take().getHeader("X-Device")
        assertEquals(first, second)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(first.orEmpty()), first)
    }

    @Test
    fun `vanha hintarivi lähtee jonosta eikä näy omissa ilmoituksissa`() = runTest {
        dao.insert(PendingReportEntity("r-hinta", "n1", "price:diesel", "1.789 EUR", now))
        val repo = repository()
        assertTrue(repo.observeOwn().first().isEmpty())

        server.enqueue(created())
        assertEquals(1, repo.flush())

        assertEquals("price:diesel", take().json()["field"])
        assertTrue(dao.rows.values.all { it.sentEpochSec != null })
    }

    @Test
    fun `palvelimen hylkäämä vanha hintarivi poistuu jonosta`() = runTest {
        dao.insert(PendingReportEntity("r-hinta", "n1", "price:diesel", "1.789 EUR", now))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"tuntematon kenttä"}"""))

        assertEquals(0, repository().flush())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `hylätty ilmoitus poistuu jonosta, raja jättää sen`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"ip"}"""))
        val repo = repository()
        repo.report("n1", ReportField.SHOWER, "yes")
        assertEquals(1, dao.rows.size)

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"tuntematon kohde"}"""))
        assertEquals(0, repo.flush())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `palvelin ilman ilmoitusrajapintaa ei tyhjennä jonoa`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        val repo = repository()
        repo.report("n1", ReportField.SHOWER, "yes")
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `eilen jonoon jäänyt ilmoitus kulkee tekohetkellään`() = runTest {
        server.enqueue(offline())
        val repo = repository()
        repo.report("n1", ReportField.TOILETS, "yes")
        take()
        now += 86_400

        server.enqueue(created("2026-10-23"))
        repo.flush()
        assertEquals("2026-10-23T07:00:00Z", take().json()["at"])
    }

    @Test
    fun `ikkunahaku ei lähetä jonoa`() = runTest {
        server.enqueue(offline())
        val repo = repository()
        repo.report("n1", ReportField.SHOP, "no")
        take()

        server.enqueue(window(""))
        repo.window(box)

        assertEquals("GET", take().method)
        assertEquals(2, server.requestCount)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `verkkokatkossa muiden ilmoitukset tulevat viimeisimmästä vastauksesta`() = runTest {
        val repo = repository()
        server.enqueue(window("""{"poi":"n7","field":"closed","value":"yes","date":"2026-10-22","at":"2026-10-22T09:00:00.000Z"}"""))
        val online = repo.window(box)
        server.enqueue(offline())

        val offlineResult = repo.window(box)

        assertEquals(online, offlineResult)
        assertEquals(
            listOf(StationReport("n7", ReportField.CLOSED, "yes", 20_748, atEpochSec = 20_748L * 86_400 + 9 * 3_600, rank = ReportRank.server(now))),
            offlineResult,
        )
    }

    @Test
    fun `kuittauksen jälkeen haettu palvelimen tulos voittaa saman tekohetken kuitatun oman, lähettämätön oma voittaa`() = runTest {
        server.enqueue(created())
        val repo = repository()
        assertEquals(ReportOutcome.SENT, repo.report("n1", ReportField.CLOSED, "yes"))
        take()

        // Palvelin ratkaisi saman sekunnin tasapelin rivitunnisteella toisen laitteen hyväksi.
        now += 1
        server.enqueue(window(otherDevice))
        assertEquals(false, StationInfo.of(station, repo.window(box)).closed?.value)

        // Jonossa odottava oma ilmoitus samalla sekunnilla kuin toisen laitteen on palvelimen tietoa uudempi aikomus.
        now -= 1
        server.enqueue(offline())
        assertEquals(ReportOutcome.QUEUED, repo.report("n1", ReportField.CLOSED, "yes"))
        take()
        server.enqueue(window(otherDevice))
        assertEquals(true, StationInfo.of(station, repo.window(box)).closed?.value)
    }

    @Test
    fun `ennen kuittausta haettu välimuistivastaus ei kumoa saman tekohetken kuitattua omaa`() = runTest {
        val repo = repository()
        server.enqueue(window(otherDevice))
        assertEquals(false, StationInfo.of(station, repo.window(box)).closed?.value)
        server.enqueue(offline())
        assertEquals(ReportOutcome.QUEUED, repo.report("n1", ReportField.CLOSED, "yes"))
        take()

        now += 10
        server.enqueue(created())
        assertEquals(1, repo.flush())
        take()
        server.enqueue(offline())
        assertEquals(true, StationInfo.of(station, repo.window(box)).closed?.value)
    }

    @Test
    fun `ikkunan vastaus kertoo, onko se tuore verkosta`() = runTest {
        val repo = repository()
        server.enqueue(offline())
        assertEquals(RemoteReports(emptyList(), version = null, fresh = false), repo.remote(box))
        take()
        server.enqueue(window(otherDevice))
        val online = repo.remote(box)
        assertTrue(online.fresh)
        assertEquals(listOf(ReportRank.server(now)), online.items.map { it.rank })
        take()
        now += 60
        server.enqueue(offline())
        // Välimuistin rivit säilyttävät alkuperäisen hakuhetkensä ja versionsa.
        assertEquals(RemoteReports(online.items, online.version, fresh = false), repo.remote(box))
    }

    @Test
    fun `viallinen vastaus ei korvaa hyvää välimuistiriviä`() = runTest {
        val repo = repository()
        server.enqueue(window(otherDevice))
        val good = repo.remote(box)
        server.enqueue(MockResponse().setBody("<html>välityspalvelimen virhesivu</html>"))
        assertEquals(RemoteReports(good.items, good.version, fresh = false), repo.remote(box))
        server.enqueue(offline())
        assertEquals(RemoteReports(good.items, good.version, fresh = false), repo.remote(box))
    }

    @Test
    fun `toisen näkymän hakema uudempi tieto näkyy, kun oma haku epäonnistuu`() = runTest {
        // Kartta ja ajonäkymä ovat eri kuluttajia (omat vartijat) saman välimuistin päällä.
        val repo = repository()
        val map = ReportWindowGuard()
        val drive = ReportWindowGuard()
        map.moveTo(box)
        drive.moveTo(box)

        server.enqueue(window(otherDevice))
        var onMap = map.show(repo.remote(box), emptyList())
        assertEquals(false, StationInfo.of(station, onMap).closed?.value)

        now += 20
        server.enqueue(window(closedYesItem))
        assertEquals(true, StationInfo.of(station, drive.show(repo.remote(box), emptyList())).closed?.value)

        now += 10
        server.enqueue(offline())
        onMap = map.show(repo.remote(box), onMap)
        assertEquals(true, StationInfo.of(station, onMap).closed?.value)
    }

    @Test
    fun `aiemmin alkanut hidas haku ei korvaa samalla sekunnilla myöhemmin alkaneen vastausta`() = runTest {
        slowEarlierFetch(clockShiftSec = 0)
    }

    @Test
    fun `ennen kellon siirtoa alkanut hidas haku ei korvaa myöhemmin alkaneen vastausta`() = runTest {
        slowEarlierFetch(clockShiftSec = -3_600)
    }

    /**
     * Haku A alkaa ja jää odottamaan palvelinta (closed=no), kello siirtyy [clockShiftSec], haku B alkaa ja valmistuu
     * (closed=yes), sitten A valmistuu. Näkyviin ja välimuistiin pitää jäädä B:n tieto.
     */
    private suspend fun TestScope.slowEarlierFetch(clockShiftSec: Long) {
        val firstArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (calls.getAndIncrement() == 0) {
                    firstArrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    window(otherDevice)
                } else {
                    window(closedYesItem)
                }
        }
        val repo = repository()
        val map = ReportWindowGuard()
        map.moveTo(box)
        val slow = async(Dispatchers.IO) { repo.remote(box) }
        assertTrue(firstArrived.await(5, TimeUnit.SECONDS))
        now += clockShiftSec
        var onMap = map.show(repo.remote(box), emptyList())
        assertEquals(true, StationInfo.of(station, onMap).closed?.value)

        release.countDown()
        onMap = map.show(slow.await(), onMap)
        assertEquals(true, StationInfo.of(station, onMap).closed?.value)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = offline()
        }
        assertEquals(true, StationInfo.of(station, repo.remote(box).items).closed?.value)
    }

    @Test
    fun `edellisen käynnistyksen välimuistirivi korvautuu verkkovastauksella`() = runTest {
        val repo = repository()
        val closedYes = """{"items":[$closedYesItem]}"""
        poiDao.putCache(PoiCacheEntity("reports|24.50,60.25,25.00,60.50", closedYes, now + 3_600))
        server.enqueue(window(otherDevice))

        val result = repo.remote(box)
        assertTrue(result.fresh)
        assertEquals(false, StationInfo.of(station, result.items).closed?.value)
        server.enqueue(offline())
        assertEquals(false, StationInfo.of(station, repo.remote(box).items).closed?.value)
    }

    @Test
    fun `omat ilmoitukset tulevat heti kannasta`() = runTest {
        val repo = repository()
        assertEquals(emptyList(), repo.observeOwn().first())
        server.enqueue(offline())
        assertEquals(ReportOutcome.QUEUED, repo.report("n1", ReportField.CLOSED, "yes"))
        assertEquals(
            listOf(StationReport("n1", ReportField.CLOSED, "yes", today, atEpochSec = now, rank = ReportRank.PENDING)),
            repo.observeOwn().first(),
        )
    }

    @Test
    fun `kuitattu ilmoitus näkyy omana tietona, vaikka ikkunahaku epäonnistuu, ja vanhenee viikossa`() = runTest {
        server.enqueue(created())
        val repo = repository()
        assertEquals(ReportOutcome.SENT, repo.report("n1", ReportField.CLOSED, "yes"))
        take()

        server.enqueue(offline())
        assertEquals(listOf(StationReport("n1", ReportField.CLOSED, "yes", today, atEpochSec = now, rank = ReportRank.sent(now))), repo.window(box))

        now += 8 * 86_400
        server.enqueue(offline())
        assertEquals(emptyList(), repo.window(box))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `varmuuskopiosta myöhemmin palautetut korjaukset siirtyvät jonoon`() = runTest {
        val repo = repository()
        assertEquals(0, repo.migrateLocalEdits())
        poiDao.corrections["n1" to "shower"] = PoiCorrectionEntity("n1", "shower", "yes", now - 86_400)

        assertEquals(1, repo.migrateLocalEdits())
        assertEquals(0, repo.migrateLocalEdits())
        assertEquals(listOf("shower"), dao.rows.values.map { it.field })
    }

    @Test
    fun `siirron tunnistetta ei voi päätellä julkisesta tiedosta`() = runTest {
        poiDao.corrections["n1" to "shower"] = PoiCorrectionEntity("n1", "shower", "yes", now)
        repository().migrateLocalEdits()
        val first = dao.rows.keys.single()
        dao.rows.clear()
        // Toinen laite (eri laitetunniste) samalla korjauksella saa eri tunnisteen.
        repository().migrateLocalEdits()
        assertTrue(dao.rows.keys.single() != first)
    }

    @Test
    fun `vain kelvollinen arvo kelpaa jonoon`() = runTest {
        val repo = repository()
        assertFailsWith<IllegalArgumentException> { repo.report("n1", ReportField.MAX_HEIGHT, "4,15") }
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `laitteen omat korjaukset siirtyvät jonoon kerran alkuperäisin päivin mutta hinnat eivät`() = runTest {
        poiDao.corrections["n1" to "food"] = PoiCorrectionEntity("n1", "food", "yes", now - 3 * 86_400)
        poiDao.corrections["n1" to "adblue"] = PoiCorrectionEntity("n1", "adblue", "yes", now)
        poiDao.corrections["n2" to "adblue"] = PoiCorrectionEntity("n2", "adblue", "no", now)
        poiDao.corrections["n1" to "maxheight"] = PoiCorrectionEntity("n1", "maxheight", "4,2", now)
        poiDao.prices["n1" to FuelKind.DIESEL] = PoiPriceEntity("n1", FuelKind.DIESEL, 1.789, "EUR", today - 1)
        val repo = repository()

        assertEquals(3, repo.migrateLocalEdits())

        val queued = dao.rows.values.map { it.poiId to (it.field to it.value) }.toSet()
        assertEquals(
            setOf("n1" to ("restaurant" to "yes"), "n2" to ("adblue" to "no"), "n1" to ("maxheight" to "4.2")),
            queued,
        )
        assertEquals(now - 3 * 86_400, dao.rows.values.single { it.field == "restaurant" }.createdEpochSec)
        assertEquals(0, repo.migrateLocalEdits())
        assertEquals(3, dao.rows.size)
    }

    /** Kohdelistan ilmoitusikkuna (50 km:n ympyrän rajaava suorakulmio), jota ei ole koskaan haettu verkosta. */
    private val listBox = PoiBox(23.9, 59.8, 25.75, 60.75)
    private val overlapping = PoiBox(24.75, 60.25, 25.25, 60.5)

    private fun closed(value: String, at: String) =
        """{"poi":"n1","field":"closed","value":"$value","date":"${at.take(10)}","at":"$at"}"""

    private fun reportsRow(box: String, ageSec: Long, vararg items: String) {
        val key = "reports|$box"
        poiDao.cache[key] = PoiCacheEntity(key, """{"items":[${items.joinToString(",")}]}""", now - ageSec)
    }

    private fun List<StationReport>.closedValues() = filter { it.poiId == "n1" && it.field == ReportField.CLOSED }.map { it.value }

    @Test
    fun `verkkokatkossa kartan ikkunassa haettu ilmoitus näkyy listan suuremmalle ikkunalle`() = runTest {
        val repo = repository()
        server.enqueue(window(closedYesItem))
        repo.remote(box)
        server.enqueue(offline())

        val result = repo.remote(listBox)

        assertEquals(true, StationInfo.of(station, result.items).closed?.value)
        assertFalse(result.fresh)
    }

    @Test
    fun `päällekkäisten välimuistirivien sama kohde ja kenttä, vain uusin tekohetki`() = runTest {
        val repo = repository()
        server.enqueue(window(closed("yes", "2026-10-23T07:00:15.000Z")))
        repo.remote(box)
        now += 60
        server.enqueue(window(closed("no", "2026-10-23T06:00:00.000Z")))
        repo.remote(overlapping)
        server.enqueue(offline())

        assertEquals(listOf("yes"), repo.remote(listBox).items.closedValues())
    }

    @Test
    fun `alueen ulkopuolinen ja yli 30 päivää vanha rivi eivät tule mukaan`() = runTest {
        reportsRow("10.00,50.00,10.25,50.25", 60, closed("yes", "2026-10-23T07:00:00.000Z"))
        reportsRow("24.50,60.25,25.00,60.50", PoiRepository.KEEP_SEC + 1, closed("yes", "2026-09-20T07:00:00.000Z"))
        server.enqueue(offline())

        assertEquals(RemoteReports(emptyList(), version = null, fresh = false), repository().remote(listBox))
    }

    @Test
    fun `kahdessa välimuistirivissä sama kohde, kenttä ja tekohetki, uudemman haun rivi jää`() = runTest {
        reportsRow("24.50,60.25,25.00,60.50", 100, closed("yes", "2026-10-23T06:00:00.000Z"))
        reportsRow("24.75,60.25,25.25,60.50", 10, closed("no", "2026-10-23T06:00:00.000Z"))
        server.enqueue(offline())

        val items = repository().remote(listBox).items
        assertEquals(listOf("no"), items.closedValues())
        assertEquals(listOf(ReportRank.server(now - 10)), items.map { it.rank })
    }

    @Test
    fun `koosteen versio on saman avaimen rivin versio, muuten pienin`() = runTest {
        val repo = repository()
        server.enqueue(window(closedYesItem))
        val first = repo.remote(box)
        server.enqueue(window(otherDevice))
        val second = repo.remote(overlapping)
        assertTrue(first.version!! < second.version!!)

        server.enqueue(offline())
        assertEquals(second.version, repo.remote(overlapping).version)
        server.enqueue(offline())
        assertEquals(first.version, repo.remote(listBox).version)
    }

    @Test
    fun `verkkokatkossa saman avaimen uudempi tyhjä vastaus kumoaa vanhemman päällekkäisen rivin ilmoituksen`() = runTest {
        val repo = repository()
        server.enqueue(window(closedYesItem))
        repo.remote(overlapping)
        now += 600
        server.enqueue(window(""))
        val online = repo.remote(box)
        server.enqueue(offline())

        val cached = repo.remote(box)

        assertEquals(emptyList(), cached.items.closedValues())
        assertEquals(online.version, cached.version)
    }

    @Test
    fun `uudemman rivin sisään jäävät rivit eivät vie paikkaa alueen muulta riviltä`() = runTest {
        reportsRow("24.00,60.00,25.50,60.60", 0)
        repeat(25) { i -> reportsRow(String.format(java.util.Locale.ROOT, "24.%02d,60.25,24.%02d,60.30", 10 + i, 11 + i), 10L + i) }
        reportsRow("25.25,60.25,25.75,60.75", 3_600, closedYesItem)
        server.enqueue(offline())

        assertEquals(listOf("yes"), repository().remote(listBox).items.closedValues())
    }

    @Test
    fun `uudemmat rivit muualta tai päällekkäin eivät pudota saman avaimen eivätkä alueen riviä`() = runTest {
        repeat(21) { i -> reportsRow(String.format(java.util.Locale.ROOT, "%d.00,50.00,%d.25,50.25", 10 + i, 10 + i), 0) }
        reportsRow("24.50,60.25,25.00,60.50", 3_600, closedYesItem)
        server.enqueue(offline())
        assertEquals(listOf("yes"), repository().remote(listBox).items.closedValues())

        poiDao.cache.clear()
        repeat(21) { i -> reportsRow(String.format(java.util.Locale.ROOT, "24.%02d,60.25,24.%02d,60.30", 50 + i, 51 + i), 0) }
        reportsRow("23.90,59.80,25.75,60.75", 3_600, closedYesItem)
        server.enqueue(offline())
        assertEquals(listOf("yes"), repository().remote(listBox).items.closedValues())
    }

    @Test
    fun `saman avaimen rivin sisään jäävä uudempi rivi tulee mukaan`() = runTest {
        reportsRow("23.90,59.80,25.75,60.75", 3_600, closed("no", "2026-10-23T05:00:00.000Z"))
        reportsRow("24.50,60.25,25.00,60.50", 10, closed("yes", "2026-10-23T06:00:00.000Z"))
        server.enqueue(offline())

        assertEquals(listOf("yes"), repository().remote(listBox).items.closedValues())
    }

    @Test
    fun `saman avaimen rivin ja uudemman rivin sama tekohetki, uudemman haun ilmoitus jää`() = runTest {
        reportsRow("23.90,59.80,25.75,60.75", 3_600, closed("no", "2026-10-23T06:00:00.000Z"))
        reportsRow("24.50,60.25,25.00,60.50", 10, closed("yes", "2026-10-23T06:00:00.000Z"))
        server.enqueue(offline())

        assertEquals(listOf("yes"), repository().remote(listBox).items.closedValues())
    }

    @Test
    fun `kellon siirryttyä taaksepäin koosteen versio on saman avaimen rivin versio`() = runTest {
        val repo = repository()
        server.enqueue(window(closedYesItem))
        repo.remote(overlapping)
        now -= 600
        server.enqueue(window(""))
        val online = repo.remote(box)
        server.enqueue(offline())

        assertEquals(online.version, repo.remote(box).version)
    }

    @Test
    fun `verkkokatkossa yhdistetään enintään 20 uusinta riviä`() = runTest {
        repeat(21) { i ->
            val item = """{"poi":"p$i","field":"closed","value":"yes","date":"2026-10-23","at":"2026-10-23T06:00:00.000Z"}"""
            reportsRow(String.format(java.util.Locale.ROOT, "24.%02d,60.25,24.%02d,60.30", 10 + i, 11 + i), 10L + i, item)
        }
        server.enqueue(offline())

        assertEquals((0 until 20).map { "p$it" }.toSet(), repository().remote(listBox).items.map { it.poiId }.toSet())
    }

    @Test
    fun `kellon siirryttyä taaksepäin vanhempi päällekkäinen ikkuna ei palauta ilmoitusta`() = runTest {
        val repo = repository()
        server.enqueue(window(closedYesItem))
        repo.remote(overlapping)
        now -= 600
        server.enqueue(window(""))
        val online = repo.remote(box)
        server.enqueue(offline())

        val cached = repo.remote(box)

        assertEquals(emptyList(), cached.items.closedValues())
        assertEquals(online.version, cached.version)
    }

    @Test
    fun `saman sekunnin aikana haettu uudempi päällekkäinen ikkuna tulee mukaan`() = runTest {
        val repo = repository()
        server.enqueue(window(""))
        repo.remote(box)
        server.enqueue(window(closedYesItem))
        repo.remote(overlapping)
        server.enqueue(offline())

        assertEquals(listOf("yes"), repo.remote(box).items.closedValues())
    }

    @Test
    fun `edellisen käynnistyksen samalla sekunnilla haetuista riveistä saman avaimen rivi riittää`() = runTest {
        reportsRow("24.50,60.25,25.00,60.50", 600)
        reportsRow("24.75,60.25,25.25,60.50", 600, closedYesItem)
        server.enqueue(offline())

        assertEquals(emptyList(), repository().remote(box).items.closedValues())
    }
}

/** Muistinvarainen ilmoitusjono. */
internal class FakeReportDao : ReportDao {
    val rows = linkedMapOf<String, PendingReportEntity>()
    private val version = MutableStateFlow(0)
    override fun observeAll(): Flow<List<PendingReportEntity>> = version.map { rows.values.toList() }
    override suspend fun insert(report: PendingReportEntity) { rows[report.rid] = report; version.value++ }
    override suspend fun pending(): List<PendingReportEntity> =
        rows.values.filter { it.sentEpochSec == null }.sortedBy { it.createdEpochSec }
    override suspend fun sent(): List<PendingReportEntity> =
        rows.values.filter { it.sentEpochSec != null }.sortedBy { it.createdEpochSec }
    override suspend fun deleteAll() { rows.clear(); version.value++ }
    override suspend fun row(rid: String): PendingReportEntity? = rows[rid]
    override suspend fun markSent(rid: String, sentEpochSec: Long) { rows[rid]?.let { rows[rid] = it.copy(sentEpochSec = sentEpochSec) }; version.value++ }
    override suspend fun pruneSent(before: Long): Int {
        val old = rows.values.filter { (it.sentEpochSec ?: Long.MAX_VALUE) < before }.map { it.rid }
        old.forEach { rows.remove(it) }
        version.value++
        return old.size
    }
    var failNextDelete = false
    override suspend fun delete(rid: String) {
        check(!failNextDelete.also { failNextDelete = false }) { "poisto epäonnistui" }
        rows.remove(rid)
        version.value++
    }
}
