package fi.tyomatka.drive

import fi.tyomatka.core.eta.EtaEngine
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.LearnedStats
import fi.tyomatka.core.model.PlannedStop
import fi.tyomatka.core.nav.RouteFailure
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.repo.RoutePlan
import fi.tyomatka.nav.LegProgress
import fi.tyomatka.nav.NavTarget
import fi.tyomatka.nav.NavigationEngine
import fi.tyomatka.nav.PreparedRoute
import fi.tyomatka.nav.RoutingException
import fi.tyomatka.ui.drive.isArriveUnlocked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ajotilakone JVM:ssä: muistivarasto, tekaistu moottori ja Main-säikeenä
 * UnconfinedTestDispatcher, jolloin jokainen launch etenee heti ensimmäiseen
 * aitoon keskeytykseen asti (portit, jotka odottavat testin avaamaa porttia).
 * Testit ajetaan runBlockingilla eikä runTestillä: session 30 s tikkeri ja
 * suunnitelman 500 ms viive jäävät testiajastimeen, jota ei koskaan edistetä,
 * kun taas runTestin loppusiivous pyörittäisi tikkeriä virtuaaliajassa ikuisesti.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveSessionManagerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stop(id: Long, status: StopStatus = StopStatus.PENDING) = StopEntity(
        id = id,
        routeDayId = DAY_ID,
        label = "Stoppi $id",
        address = "Katu $id",
        lat = 60.0 + id * 0.01,
        lng = 24.0 + id * 0.01,
        orderIndex = id.toInt(),
        status = status,
    )

    private fun session(store: FakeStore, engine: FakeEngine, learning: FakeLearning = FakeLearning()): DriveSessionManager =
        DriveSessionManager(FakeHost(), store, learning, engine, EtaEngine())

    private fun plan(origin: GeoPoint?, ids: List<Long>, legs: List<Long>) = RoutePlan(
        origin = origin,
        stopIds = ids,
        legDurationsSec = legs,
        legDistancesM = legs.map { it * 10 },
        encodedPolyline = "",
        totalDurationSec = legs.sum(),
        fetchedAtEpochSec = System.currentTimeMillis() / 1000,
    )

    @Test
    fun `ajo etenee reitin, saapumisen ja lähdön kautta seuraavaan stoppiin`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)

        session.startDay(DAY_ID)

        assertEquals(DrivePhase.NAVIGATING, session.state.value.phase)
        assertEquals(1L, session.state.value.currentStop?.id)
        assertEquals(listOf(1L), engine.prepared.map { it.stopId })
        assertEquals(1L, engine.started.last().target.stopId)
        assertEquals(StopStatus.ACTIVE, store.status(1))

        engine.arrivals.emit(engine.started.last().target)

        assertEquals(DrivePhase.AT_STOP, session.state.value.phase)
        assertEquals(StopStatus.ARRIVED, store.status(1))

        session.departCurrentStop()

        assertEquals(DrivePhase.NAVIGATING, session.state.value.phase)
        assertEquals(StopStatus.DONE, store.status(1))
        assertEquals(2L, session.state.value.currentStop?.id)
        assertEquals(2L, engine.started.last().target.stopId)

        // Sessio alas, muuten 30 s tikkeri pitää virtuaaliajan ikuisesti käynnissä.
        session.stopDriving()
    }

    @Test
    fun `huoltostopin kesto ei opeta stoppikestoa, asiakasstopin opettaa`() = runBlocking {
        val store = FakeStore(listOf(stop(1).copy(placeId = "service:osm:n1"), stop(2)))
        val engine = FakeEngine()
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)

        engine.arrivals.emit(engine.started.last().target)
        session.departCurrentStop()
        assertEquals(0, learning.services)

        engine.arrivals.emit(engine.started.last().target)
        session.departCurrentStop()
        assertEquals(1, learning.services)
    }

    @Test
    fun `aloitus ja päivän päättyminen kirjaavat ajat päivän riville kenttäkohtaisesti`() = runBlocking {
        val store = FakeStore(listOf(stop(1)))
        val engine = FakeEngine()
        val session = session(store, engine)

        session.startDay(DAY_ID)

        assertEquals(1, store.dayStartedCalls)
        val startedAt = store.day.startedAtEpochSec
        assertTrue(startedAt != null)
        assertEquals(null, store.day.finishedAtEpochSec)

        engine.arrivals.emit(engine.started.last().target)
        session.departCurrentStop()

        assertEquals(DrivePhase.FINISHED, session.state.value.phase)
        assertEquals(startedAt, store.day.startedAtEpochSec)
        assertTrue(store.day.finishedAtEpochSec != null)
    }

    @Test
    fun `kohteen vaihto keskeyttää vanhan opastuksen eikä vanhan kohteen saapuminen kuittaa uutta`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)
        val targetA = engine.started.last().target

        // Uuden kohteen reittihaku jää odottamaan palvelinta.
        engine.routeGate = CompletableDeferred()
        session.skipCurrentStop()

        assertEquals(2L, session.state.value.currentStop?.id)
        assertTrue(engine.suspendCalls >= 1, "vanha opastus pitää keskeyttää ennen uutta hakua")

        // Moottori ilmoittaa saapumisesta VANHAAN kohteeseen haun ollessa kesken.
        engine.arrivals.emit(targetA)

        assertEquals(DrivePhase.NAVIGATING, session.state.value.phase)
        assertEquals(StopStatus.ACTIVE, store.status(2))

        engine.routeGate!!.complete(Unit)

        assertEquals(2L, engine.started.last().target.stopId)
        session.stopDriving()
    }

    @Test
    fun `saapumisen kirjaus valmistuu vaikka ajo lopetetaan kesken kirjauksen`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)

        // Kannan kirjoitus jää odottamaan; lopetus ehtii väliin.
        store.arrivedGate = CompletableDeferred()
        engine.arrivals.emit(engine.started.last().target)
        assertEquals(DrivePhase.AT_STOP, session.state.value.phase)

        session.stopDriving()
        store.arrivedGate!!.complete(Unit)

        assertEquals(DrivePhase.IDLE, session.state.value.phase)
        assertEquals(StopStatus.ARRIVED, store.status(1), "hyväksytty saapuminen ei saa peruuntua")
    }

    @Test
    fun `valmiin päivän käynnistys ei jätä moottorin sessiota auki`() = runBlocking {
        val store = FakeStore(listOf(stop(1, StopStatus.DONE)))
        val engine = FakeEngine()
        val session = session(store, engine)

        session.startDay(DAY_ID)

        assertEquals(DrivePhase.FINISHED, session.state.value.phase)
        assertFalse(engine.sessionOpen, "sessio ei saa jäädä auki ilman ajoa")
    }


    @Test
    fun `alussa lahtee yksi reittipyynto vaikka oma ACTIVE-kirjaus laukaisee toisen emission`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { routeGate = CompletableDeferred() }
        val session = session(store, engine)

        session.startDay(DAY_ID)
        assertEquals(StopStatus.ACTIVE, store.status(1))
        assertEquals(listOf(1L), engine.prepared.map { it.stopId })

        engine.routeGate!!.complete(Unit)

        assertEquals(listOf(1L), engine.prepared.map { it.stopId })
        assertEquals(1, engine.started.size)
        session.stopDriving()
    }

    @Test
    fun `osoitteen muokkaus samalla tunnisteella hakee uuden reitin`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)

        store.edit(1) { it.copy(lat = it.lat + 0.02) }

        assertEquals(2, engine.prepared.size)
        assertEquals(1L, engine.started.last().target.stopId)
        assertEquals(60.03, engine.started.last().target.location.lat, 1e-9)
        session.stopDriving()
    }

    @Test
    fun `vanhentunut reittitulos ei kaynnista opastusta uudelle kohteelle`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { routeGate = CompletableDeferred() }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        session.skipCurrentStop()
        engine.routeGate!!.complete(Unit)

        assertEquals(listOf(2L), engine.started.map { it.target.stopId })
        session.stopDriving()
    }

    @Test
    fun `automaattinen saapuminen pysayttaa opastuksen ja oppiminen saa kummankin ennusteen`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        store.cached = plan(origin = GeoPoint(60.0101, 24.01), ids = listOf(1, 2), legs = listOf(500, 300))
        val engine = FakeEngine().apply { location.value = GeoPoint(60.01, 24.01) }
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)
        val suspendsBefore = engine.suspendCalls

        engine.arrivals.emit(engine.started.last().target)

        assertEquals(DrivePhase.AT_STOP, session.state.value.phase)
        assertTrue(engine.suspendCalls > suspendsBefore, "välistopin saapuminen pysäyttää opastuksen")
        assertEquals(listOf(LegRecord(navPredictedSec = 600, planPredictedSec = 500)), learning.legs)
        session.stopDriving()
    }

    @Test
    fun `seuraavan legin suunnitelman ennuste otetaan juuri lahdetysta stopista`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        store.cached = plan(origin = GeoPoint(60.0101, 24.01), ids = listOf(1, 2), legs = listOf(500, 300))
        val engine = FakeEngine().apply { location.value = GeoPoint(60.01, 24.01) }
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)
        engine.arrivals.emit(engine.started.last().target)
        session.departCurrentStop()

        engine.arrivals.emit(engine.started.last().target)

        assertEquals(LegRecord(navPredictedSec = 600, planPredictedSec = 300), learning.legs.last())
        session.stopDriving()
    }

    @Test
    fun `kaukana lasketun suunnitelman ensimmainen legi ei opeta suunnitelman kerrointa`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        store.cached = plan(origin = GeoPoint(61.5, 25.5), ids = listOf(1, 2), legs = listOf(500, 300))
        val engine = FakeEngine().apply { location.value = GeoPoint(60.01, 24.01) }
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)

        engine.arrivals.emit(engine.started.last().target)

        assertEquals(listOf(LegRecord(navPredictedSec = 600, planPredictedSec = null)), learning.legs)
        session.stopDriving()
    }

    @Test
    fun `kasin merkitty saapuminen pysayttaa opastuksen`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)
        val suspendsBefore = engine.suspendCalls

        session.markArrived()

        assertEquals(DrivePhase.AT_STOP, session.state.value.phase)
        assertTrue(engine.suspendCalls > suspendsBefore)
        session.stopDriving()
    }

    @Test
    fun `vieraan kohteen saapuminen ohitetaan`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)

        engine.arrivals.emit(NavTarget(99L, GeoPoint(61.0, 25.0), "Vieras"))

        assertEquals(DrivePhase.NAVIGATING, session.state.value.phase)
        assertEquals(StopStatus.ACTIVE, store.status(1))
        session.stopDriving()
    }

    @Test
    fun `etenemisen paivitykset eivat muuta legin ennustetta`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)
        val target = engine.started.last().target

        engine.progress.value = LegProgress(target, generation = 1, remainingSec = 900, remainingM = 5_000)
        assertEquals(900L, session.state.value.liveEtaSec)
        engine.arrivals.emit(target)

        assertEquals(600L, learning.legs.single().navPredictedSec)
        session.stopDriving()
    }

    @Test
    fun `lahdon jalkeen myohassa tuleva saapuminen ei palauta DONE-stoppia ARRIVED-tilaan`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)
        val targetA = engine.started.last().target
        engine.arrivals.emit(targetA)

        // Lähdön kantakirjoitus jää kesken; moottorilta tulee myöhässä saapuminen samaan stoppiin.
        store.doneGate = CompletableDeferred()
        session.departCurrentStop()
        engine.arrivals.emit(targetA)

        assertEquals(DrivePhase.NAVIGATING, session.state.value.phase)
        store.doneGate!!.complete(Unit)
        assertEquals(StopStatus.DONE, store.status(1))
        assertEquals(2L, session.state.value.currentStop?.id)
        session.stopDriving()
    }

    @Test
    fun `viimeisen stopin lahto paattaa paivan ja sulkee session`() = runBlocking {
        val store = FakeStore(listOf(stop(1)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)
        engine.arrivals.emit(engine.started.last().target)

        session.departCurrentStop()

        assertEquals(DrivePhase.FINISHED, session.state.value.phase)
        assertFalse(engine.sessionOpen)
    }

    @Test
    fun `aktiivisen stopin poisto kesken vaihtaa kohteen`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)

        session.removeStop(1)

        assertEquals(2L, engine.started.last().target.stopId)
        assertEquals(2L, session.state.value.currentStop?.id)
        session.stopDriving()
    }

    @Test
    fun `ARRIVED-stopista jatkaminen ei hae reittia ennen Valmis-painallusta`() = runBlocking {
        val store = FakeStore(listOf(stop(1, StopStatus.ARRIVED), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)

        session.startDay(DAY_ID)
        assertEquals(DrivePhase.AT_STOP, session.state.value.phase)
        assertTrue(engine.prepared.isEmpty())

        session.departCurrentStop()

        assertEquals(listOf(2L), engine.prepared.map { it.stopId })
        session.stopDriving()
    }

    @Test
    fun `lopetus palauttaa ACTIVE-stopit jonoon ja sulkee session`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)

        session.stopDriving()

        assertEquals(DrivePhase.IDLE, session.state.value.phase)
        assertEquals(StopStatus.PENDING, store.status(1))
        assertFalse(engine.sessionOpen)
    }


    /** Virtuaaliaika eteenpäin: myös täsmälleen nyt erääntyvät viiveet ajetaan. */
    private fun advance(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `palvelin ei vastaa - virheteksti ja uusi yritys 5 ja 10 sekunnin jalkeen samaan kohteeseen`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += listOf(RouteFailure.Unavailable, RouteFailure.Unavailable) }
        val session = session(store, engine)

        session.startDay(DAY_ID)
        assertEquals("Reititys epäonnistui: Unavailable", session.state.value.error)
        assertEquals(1, engine.prepared.size)

        advance(4_999)
        assertEquals(1, engine.prepared.size)
        advance(1)
        assertEquals(2, engine.prepared.size)
        advance(9_999)
        assertEquals(2, engine.prepared.size)
        advance(1)

        assertEquals(listOf(1L, 1L, 1L), engine.prepared.map { it.stopId })
        assertEquals(1L, engine.started.single().target.stopId)
        assertEquals(null, session.state.value.error)
        session.stopDriving()
    }

    @Test
    fun `uusi yritys lakkaa kun kohde vaihtuu`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += RouteFailure.Unavailable }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        session.skipCurrentStop()
        advance(120_000)

        assertEquals(listOf(1L, 2L), engine.prepared.map { it.stopId })
        session.stopDriving()
    }

    @Test
    fun `NoRoute yritetaan uudelleen vasta kun sijainti on siirtynyt 200 metria`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply {
            failures += RouteFailure.NoRoute
            location.value = GeoPoint(60.5, 24.5)
        }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        advance(120_000)
        assertEquals(1, engine.prepared.size)
        engine.location.value = GeoPoint(60.5 + 150.0 / 111_000, 24.5)
        assertEquals(1, engine.prepared.size)
        engine.location.value = GeoPoint(60.5 + 250.0 / 111_000, 24.5)

        assertEquals(2, engine.prepared.size)
        session.stopDriving()
    }

    @Test
    fun `liian pitka reitti ja asetusvirhe eivat yrita uudelleen`() = runBlocking {
        for (failure in listOf(RouteFailure.TooLong, RouteFailure.BadConfig)) {
            val store = FakeStore(listOf(stop(1), stop(2)))
            val engine = FakeEngine().apply { failures += failure }
            val session = session(store, engine)
            session.startDay(DAY_ID)

            advance(300_000)

            assertEquals(1, engine.prepared.size, "$failure")
            session.stopDriving()
        }
    }

    @Test
    fun `kuormaraja odottaa Retry-After-ajan`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += RouteFailure.RateLimited(30) }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        advance(29_999)
        assertEquals(1, engine.prepared.size)
        advance(1)

        assertEquals(2, engine.prepared.size)
        session.stopDriving()
    }

    @Test
    fun `onnistunut reitti tyhjentaa virheen ja varaetaisyyden`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply {
            failures += RouteFailure.Unavailable
            location.value = GeoPoint(60.01 + 100.0 / 111_000, 24.01)
        }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        assertEquals(100, session.state.value.fallbackDistanceM)

        advance(5_000)

        assertEquals(null, session.state.value.error)
        assertEquals(null, session.state.value.fallbackDistanceM)
        session.stopDriving()
    }

    @Test
    fun `varaetaisyys seuraa sijaintia vain epaonnistuneelle kohteelle`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { location.value = GeoPoint(60.01 + 100.0 / 111_000, 24.01) }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        engine.location.value = GeoPoint(60.01 + 50.0 / 111_000, 24.01)

        assertEquals(null, session.state.value.fallbackDistanceM)
        session.stopDriving()
    }

    @Test
    fun `lahdon jalkeen naapuristopin Perilla pysyy lukossa kunnes reittihaku on epaonnistunut`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { location.value = GeoPoint(60.02 + 100.0 / 111_000, 24.02) }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        engine.arrivals.emit(engine.started.last().target)
        engine.routeGate = CompletableDeferred()

        session.departCurrentStop()
        assertEquals(2L, session.state.value.currentStop?.id)
        assertFalse(isArriveUnlocked(session.state.value.liveDistanceM, session.state.value.fallbackDistanceM))

        engine.failures += RouteFailure.Unavailable
        engine.routeGate!!.complete(Unit)

        assertEquals(100, session.state.value.fallbackDistanceM)
        assertTrue(isArriveUnlocked(session.state.value.liveDistanceM, session.state.value.fallbackDistanceM))
        session.stopDriving()
    }

    @Test
    fun `yli 30 minuuttia vanha suunnitelma haetaan uudelleen tikkerissa`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)
        advance(1_000)
        val callsAfterStart = store.planCalls
        val old = System.currentTimeMillis() / 1000 - 31 * 60
        store.cached = plan(origin = null, ids = listOf(1, 2), legs = listOf(500, 300)).copy(fetchedAtEpochSec = old)

        advance(30_000)
        advance(500)

        assertTrue(store.planCalls > callsAfterStart)
        session.stopDriving()
    }

    // ---- Katselmointipisteen C regressiotestit ----

    @Test
    fun `lahdon jalkeen Perilla on heti lukossa eika tuplanapautus palauta lahdettya stoppia`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)
        val targetA = engine.started.last().target
        engine.progress.value = LegProgress(targetA, generation = 1, remainingSec = 5, remainingM = 20)
        engine.arrivals.emit(targetA)

        // Room ei ole vielä vastannut lähdön kirjoitukseen; toinen napautus osuu Perillä-napin kohdalle.
        store.doneGate = CompletableDeferred()
        session.departCurrentStop()
        assertFalse(isArriveUnlocked(session.state.value.liveDistanceM, session.state.value.fallbackDistanceM))
        session.markArrived()
        store.doneGate!!.complete(Unit)

        assertEquals(StopStatus.DONE, store.status(1))
        assertEquals(1, learning.legs.size, "lähdetyn stopin legiä ei saa oppia toiseen kertaan")
        assertEquals(2L, session.state.value.currentStop?.id)
        session.stopDriving()
    }

    @Test
    fun `lyhimman reitin ajo ei opeta ennusteita`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { learnableRoutes = false }
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)

        engine.arrivals.emit(engine.started.last().target)
        session.departCurrentStop()
        engine.arrivals.emit(engine.started.last().target)

        assertTrue(learning.legs.isEmpty(), "pienten teiden toteuma ei saa muuttaa tavallisen reitin kertoimia")
        assertTrue(store.legStats.isEmpty(), "stoppiparin aika ei saa tulla lyhimmältä reitiltä")
        session.stopDriving()
    }

    @Test
    fun `lahdon jalkeen Ohita ei ohita lahdettya stoppia`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)
        engine.arrivals.emit(engine.started.last().target)

        store.doneGate = CompletableDeferred()
        session.departCurrentStop()
        session.skipCurrentStop()
        store.doneGate!!.complete(Unit)

        assertEquals(StopStatus.DONE, store.status(1))
        assertEquals(StopStatus.ACTIVE, store.status(2))
        // Ohitus olisi katkaissut stoppiparin mittauksen; lähdöstä alkanut legi oppii sen yhä.
        engine.arrivals.emit(engine.started.last().target)
        assertEquals(listOf(1L to 2L), store.legStats)
        session.stopDriving()
    }

    @Test
    fun `kasin merkitty saapuminen kesken uuden yrityksen ei kaynnista opastusta stopilla`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply {
            failures += RouteFailure.Unavailable
            location.value = GeoPoint(60.01 + 100.0 / 111_000, 24.01)
        }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        engine.routeGate = CompletableDeferred()
        advance(5_000)
        assertEquals(2, engine.prepared.size)

        session.markArrived()
        engine.routeGate!!.complete(Unit)

        assertEquals(DrivePhase.AT_STOP, session.state.value.phase)
        assertTrue(engine.started.isEmpty(), "stopilla ei saa alkaa opastus")
        session.stopDriving()
    }

    @Test
    fun `uudella yrityksella saatu reitti ei opeta suunnitelman kerrointa eika stoppiparia`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        store.cached = plan(origin = GeoPoint(60.0101, 24.01), ids = listOf(1, 2), legs = listOf(500, 300))
        val engine = FakeEngine().apply { location.value = GeoPoint(60.01, 24.01) }
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)
        engine.arrivals.emit(engine.started.last().target)
        engine.failures += RouteFailure.Unavailable
        session.departCurrentStop()
        advance(5_000)

        engine.arrivals.emit(engine.started.last().target)

        assertEquals(LegRecord(navPredictedSec = 600, planPredictedSec = null), learning.legs.last())
        assertTrue(store.legStats.isEmpty(), "legin alku ei ole lähtöhetki, joten stoppiparia ei opita")
        session.stopDriving()
    }

    @Test
    fun `ohituksen aikana tuleva saapuminen ei kirjaa ohitettua stoppia saapuneeksi`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val learning = FakeLearning()
        val session = session(store, engine, learning)
        session.startDay(DAY_ID)
        val targetA = engine.started.last().target

        store.skipGate = CompletableDeferred()
        session.skipCurrentStop()
        engine.arrivals.emit(targetA)

        assertEquals(DrivePhase.NAVIGATING, session.state.value.phase)
        assertTrue(learning.legs.isEmpty())
        store.skipGate!!.complete(Unit)
        assertEquals(StopStatus.SKIPPED, store.status(1))
        assertEquals(2L, session.state.value.currentStop?.id)
        session.stopDriving()
    }

    @Test
    fun `varaetaisyys seuraa sijaintia epaonnistuneelle kohteelle ja avaa lukon lahestyttaessa`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply {
            failures += RouteFailure.BadConfig
            location.value = GeoPoint(60.01 + 1_000.0 / 111_000, 24.01)
        }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        assertFalse(isArriveUnlocked(session.state.value.liveDistanceM, session.state.value.fallbackDistanceM))

        engine.location.value = GeoPoint(60.01 + 100.0 / 111_000, 24.01)

        assertEquals(100, session.state.value.fallbackDistanceM)
        assertTrue(isArriveUnlocked(session.state.value.liveDistanceM, session.state.value.fallbackDistanceM))
        session.stopDriving()
    }

    @Test
    fun `muun stopin muokkaus ei kaynnista epaonnistunutta hakua heti eika nollaa virhetta`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply {
            failures += listOf(RouteFailure.Unavailable, RouteFailure.Unavailable)
            location.value = GeoPoint(60.01 + 100.0 / 111_000, 24.01)
        }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        advance(5_000)
        assertEquals(2, engine.prepared.size)

        store.edit(2) { it.copy(label = "Uusi nimi") }

        assertEquals(2, engine.prepared.size)
        assertEquals("Reititys epäonnistui: Unavailable", session.state.value.error)
        assertEquals(100, session.state.value.fallbackDistanceM)
        advance(9_999)
        assertEquals(2, engine.prepared.size, "uusintaväli ei saa nollautua muokkauksesta")
        advance(1)
        assertEquals(3, engine.prepared.size)
        session.stopDriving()
    }

    @Test
    fun `liian pitka reitti haetaan uudelleen vasta stoppilistan muutoksesta`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += RouteFailure.TooLong }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        advance(120_000)
        assertEquals(1, engine.prepared.size)

        store.edit(2) { it.copy(label = "Uusi nimi") }

        assertEquals(2, engine.prepared.size)
        assertEquals(1L, engine.started.single().target.stopId)
        session.stopDriving()
    }

    @Test
    fun `sijainnin puuttuessa uusi yritys vasta 10 s jalkeen ja kun sijainti saadaan`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += RouteFailure.NoLocation }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        advance(60_000)
        assertEquals(1, engine.prepared.size)
        engine.location.value = GeoPoint(60.0, 24.0)

        assertEquals(2, engine.prepared.size)
        session.stopDriving()
    }

    @Test
    fun `kuormaraja ei lyhenna uusintavalia`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += listOf(RouteFailure.Unavailable, RouteFailure.RateLimited(1)) }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        advance(5_000)
        assertEquals(2, engine.prepared.size)

        advance(9_999)
        assertEquals(2, engine.prepared.size, "429 odottaa vähintään uusintavälin (10 s)")
        advance(1)
        assertEquals(3, engine.prepared.size)
        session.stopDriving()
    }

    @Test
    fun `vanhan kohteen uusi yritys ei laukea uuden kohteen haun aikana`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += RouteFailure.Unavailable }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        engine.routeGate = CompletableDeferred()
        session.skipCurrentStop()
        advance(120_000)

        assertEquals(listOf(1L, 2L), engine.prepared.map { it.stopId })
        session.stopDriving()
    }

    @Test
    fun `lahdosta alkava legi opettaa stoppiparin`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2), stop(3)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)
        engine.arrivals.emit(engine.started.last().target)
        session.departCurrentStop()

        engine.arrivals.emit(engine.started.last().target)

        assertEquals(listOf(1L to 2L), store.legStats)
        session.stopDriving()
    }

    @Test
    fun `kohteen vaihto kesken legin katkaisee stoppiparin mittauksen`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2), stop(3)))
        val engine = FakeEngine()
        val session = session(store, engine)
        session.startDay(DAY_ID)
        engine.arrivals.emit(engine.started.last().target)
        session.departCurrentStop()

        session.removeStop(2)
        assertEquals(3L, engine.started.last().target.stopId)
        engine.arrivals.emit(engine.started.last().target)

        assertTrue(store.legStats.isEmpty(), "1 -> 3 ei ole ajettu suoraan lähdöstä")
        session.stopDriving()
    }

    @Test
    fun `moottorin oma peruutus on palvelinkatko eika jumita sessiota`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { cancelNext = true }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        assertEquals("Reititys epäonnistui: Unavailable", session.state.value.error)
        advance(5_000)

        assertEquals(2, engine.prepared.size)
        assertEquals(1L, engine.started.single().target.stopId)
        session.stopDriving()
    }

    @Test
    fun `uudelleenreitityksen virhe nayttaa virhetekstin ja avaa Perillan linnuntiella kunnes reitti palaa`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { location.value = GeoPoint(60.01 + 1_000.0 / 111_000, 24.01) }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        val target = engine.started.last().target
        engine.progress.value = LegProgress(target, generation = 1, remainingSec = 300, remainingM = 2_000)
        assertEquals(2_000, session.state.value.liveDistanceM)

        engine.rerouteFailures.emit(RouteFailure.Unavailable)

        assertEquals("Reititys epäonnistui: Unavailable", session.state.value.error)
        assertEquals(null, session.state.value.liveDistanceM, "vanhan reitin lukema ei saa pitää Perillää lukossa")
        assertTrue(session.state.value.fallbackDistanceM in 990..1010, "linnuntie ${session.state.value.fallbackDistanceM}")
        engine.location.value = GeoPoint(60.01 + 100.0 / 111_000, 24.01)
        assertTrue(isArriveUnlocked(session.state.value.liveDistanceM, session.state.value.fallbackDistanceM))
        assertEquals(1, engine.prepared.size, "moottori hakee uuden reitin itse; sessio ei lähetä omaa hakua")

        engine.progress.value = LegProgress(target, generation = 1, remainingSec = 30, remainingM = 150)

        assertEquals(null, session.state.value.error)
        assertEquals(null, session.state.value.fallbackDistanceM)
        assertEquals(150, session.state.value.liveDistanceM)
        session.stopDriving()
    }

    @Test
    fun `vanhan reitin eteneminen poikkeamassa ei kuittaa uudelleenreitityksen virhetta`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { location.value = GeoPoint(60.01 + 1_000.0 / 111_000, 24.01) }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        val target = engine.started.last().target
        engine.rerouteFailures.emit(RouteFailure.Unavailable)
        assertEquals("Reititys epäonnistui: Unavailable", session.state.value.error)

        // Ydin julkaisee vanhan reitin lukemia myös poikkeamassa: ne eivät ole uusi reitti.
        engine.progress.value = LegProgress(target, generation = 1, remainingSec = 300, remainingM = 1_900, offRoute = true)

        assertEquals("Reititys epäonnistui: Unavailable", session.state.value.error, "virhe säilyy poikkeamassa")
        assertEquals(null, session.state.value.liveDistanceM, "vanhan reitin lukema ei saa lukita Perillää")
        assertTrue(session.state.value.fallbackDistanceM in 990..1010, "linnuntie ${session.state.value.fallbackDistanceM}")

        // Paluu kelvolliselle reitille (uusi reitti tai vanha reitti alla) kuittaa virheen.
        engine.progress.value = LegProgress(target, generation = 1, remainingSec = 30, remainingM = 150)

        assertEquals(null, session.state.value.error)
        assertEquals(null, session.state.value.fallbackDistanceM)
        assertEquals(150, session.state.value.liveDistanceM)
        session.stopDriving()
    }

    @Test
    fun `ohitus epaonnistuneen haun jalkeen peruu uuden yrityksen`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += RouteFailure.Unavailable }
        val session = session(store, engine)
        session.startDay(DAY_ID)
        assertEquals(listOf(1L), engine.prepared.map { it.stopId })

        store.skipGate = CompletableDeferred()
        session.skipCurrentStop()
        advance(10_000)
        assertEquals(listOf(1L), engine.prepared.map { it.stopId }, "uusi yritys ei saa laueta kantakirjoituksen aikana")
        store.skipGate!!.complete(Unit)

        assertEquals(listOf(1L, 2L), engine.prepared.map { it.stopId })
        assertEquals(2L, engine.started.single().target.stopId)
        session.stopDriving()
    }

    @Test
    fun `haettavana olevan kohteen poisto peruu haun`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { routeGate = CompletableDeferred() }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        session.removeStop(1)
        engine.routeGate!!.complete(Unit)

        assertTrue(engine.started.none { it.target.stopId == 1L }, "poistetun stopin reitti ei saa alkaa")
        assertEquals(2L, engine.started.last().target.stopId)
        session.stopDriving()
    }

    @Test
    fun `opastuksen aloituksessa heti tuleva eteneminen ei huku`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { emitProgressOnStart = true }
        val session = session(store, engine)

        session.startDay(DAY_ID)

        assertEquals(500, session.state.value.liveDistanceM)
        assertEquals(100L, session.state.value.liveEtaSec)
        session.stopDriving()
    }

    @Test
    fun `simuloitu kaynnistys valittaa simuloinnin moottorille`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine()
        val session = session(store, engine)

        session.startDay(DAY_ID, simulate = true)

        assertEquals(true, engine.simulateRequested)
        session.stopDriving()

        val plain = FakeEngine()
        val second = session(FakeStore(listOf(stop(1), stop(2))), plain)
        second.startDay(DAY_ID)
        assertEquals(false, plain.simulateRequested)
        second.stopDriving()
    }

    @Test
    fun `uusi yritys kayttaa stopin uusinta nimea`() = runBlocking {
        val store = FakeStore(listOf(stop(1), stop(2)))
        val engine = FakeEngine().apply { failures += RouteFailure.Unavailable }
        val session = session(store, engine)
        session.startDay(DAY_ID)

        store.edit(1) { it.copy(label = "Uusi nimi") }
        advance(5_000)

        assertEquals("Uusi nimi", engine.prepared.last().label)
        session.stopDriving()
    }

    private companion object {
        const val DAY_ID = 1L
    }
}

private class FakeEngine : NavigationEngine {
    /** Seuraava reittihaku päättyy moottorin omaan peruutukseen (esimerkiksi sisäinen aikakatkaisu). */
    var cancelNext = false

    var sessionOpen = false
    var suspendCalls = 0
    val prepared = mutableListOf<NavTarget>()
    val started = mutableListOf<PreparedRoute>()

    /** Kun asetettu, reittihaku odottaa tämän avaamista. */
    var routeGate: CompletableDeferred<Unit>? = null

    /** Seuraavat reittihaut epäonnistuvat näillä syillä järjestyksessä. */
    val failures = ArrayDeque<RouteFailure>()

    override val progress = MutableStateFlow<LegProgress?>(null)
    override val arrivals = MutableSharedFlow<NavTarget>()
    override val location = MutableStateFlow<GeoPoint?>(null)
    override val rerouteFailures = MutableSharedFlow<RouteFailure>()

    /** Viimeisimmän session simulointipyyntö; null ennen käynnistystä. */
    var simulateRequested: Boolean? = null

    override suspend fun startSession(simulate: Boolean) {
        sessionOpen = true
        simulateRequested = simulate
    }

    override suspend fun prepareRoute(target: NavTarget): PreparedRoute {
        prepared += target
        routeGate?.await()
        if (cancelNext) {
            cancelNext = false
            throw CancellationException("moottorin aikakatkaisu")
        }
        failures.removeFirstOrNull()?.let { throw RoutingException(it) }
        return PreparedRoute(target, predictedSec = 600, distanceM = 1000.0, payload = Unit, learnable = learnableRoutes)
    }

    /** Lyhimmän reitin ajossa moottori merkitsee reitit, joista ei opita. */
    var learnableRoutes = true

    /** Ferrostarin ensimmäinen tila tulee samassa kutsussa kuin opastuksen aloitus (Main.immediate). */
    var emitProgressOnStart = false

    override fun startGuidance(route: PreparedRoute) {
        started += route
        if (emitProgressOnStart) {
            progress.value = LegProgress(route.target, generation = started.size, remainingSec = 100, remainingM = 500)
        }
    }

    override fun suspendGuidance() {
        suspendCalls++
    }

    override fun endSession() {
        sessionOpen = false
    }
}

private class FakeStore(initial: List<StopEntity>) : DriveStore {
    private val stops = MutableStateFlow(initial)
    var day = RouteDayEntity(id = 1L, dateEpochDay = 20741)
        private set

    /** Aloitusajan kirjauskutsut: ehto "vain kun tyhjä" on kyselyssä, joten kutsu saa toistua. */
    var dayStartedCalls = 0

    /** Kun asetettu, saapumiskirjaus odottaa tämän avaamista. */
    var arrivedGate: CompletableDeferred<Unit>? = null

    /** Kun asetettu, lähtökirjaus odottaa tämän avaamista. */
    var doneGate: CompletableDeferred<Unit>? = null

    /** Kun asetettu, ohituskirjaus odottaa tämän avaamista. */
    var skipGate: CompletableDeferred<Unit>? = null

    /** Opitut stoppiparit (mistä, mihin). */
    val legStats = mutableListOf<Pair<Long, Long>>()

    /** Viimeksi haettu päivän suunnitelma. */
    var cached: RoutePlan? = null

    /** Reittisuunnitelman hakujen määrä. */
    var planCalls = 0

    fun status(id: Long): StopStatus? = stops.value.firstOrNull { it.id == id }?.status

    fun edit(id: Long, transform: (StopEntity) -> StopEntity) {
        stops.value = stops.value.map { if (it.id == id) transform(it) else it }
    }

    private fun update(id: Long, transform: (StopEntity) -> StopEntity) = edit(id, transform)

    override suspend fun day(dayId: Long): RouteDayEntity? = day.takeIf { it.id == dayId }

    override suspend fun markDayStarted(dayId: Long, nowEpochSec: Long) {
        dayStartedCalls++
        if (day.id == dayId && day.startedAtEpochSec == null) day = day.copy(startedAtEpochSec = nowEpochSec)
    }

    override suspend fun markDayFinished(dayId: Long, nowEpochSec: Long) {
        if (day.id == dayId) day = day.copy(finishedAtEpochSec = nowEpochSec)
    }

    override fun observeStops(dayId: Long): Flow<List<StopEntity>> = stops

    override suspend fun stop(stopId: Long): StopEntity? = stops.value.firstOrNull { it.id == stopId }

    override suspend fun setStatus(stopId: Long, status: StopStatus) {
        if (status == StopStatus.SKIPPED) skipGate?.await()
        update(stopId) { it.copy(status = status) }
    }

    override suspend fun markStopArrived(stopId: Long, arrivedAtEpochSec: Long) {
        arrivedGate?.await()
        update(stopId) { it.copy(status = StopStatus.ARRIVED, arrivedAtEpochSec = arrivedAtEpochSec) }
    }

    override suspend fun markStopDone(stopId: Long, departedAtEpochSec: Long) {
        doneGate?.await()
        update(stopId) { it.copy(status = StopStatus.DONE, departedAtEpochSec = departedAtEpochSec) }
    }

    override suspend fun removeStop(stopId: Long) {
        stops.value = stops.value.filter { it.id != stopId }
    }

    override suspend fun remainingStops(dayId: Long): List<StopEntity> =
        stops.value.filter { it.status == StopStatus.PENDING || it.status == StopStatus.ACTIVE }

    override suspend fun arrivedStop(dayId: Long): StopEntity? =
        stops.value.firstOrNull { it.status == StopStatus.ARRIVED }

    override suspend fun doneCount(dayId: Long): Int =
        stops.value.count { it.status == StopStatus.DONE || it.status == StopStatus.SKIPPED }

    override suspend fun totalCount(dayId: Long): Int = stops.value.size

    override fun toPlanned(entity: StopEntity, dateEpochDay: Long): PlannedStop =
        PlannedStop(entity.id, entity.label, GeoPoint(entity.lat, entity.lng), null, entity.serviceSec)

    override suspend fun routePlan(
        dayId: Long,
        origin: GeoPoint?,
        stops: List<StopEntity>,
        dateEpochDay: Long,
        maxAgeSec: Long,
    ): RoutePlan = also { planCalls++ }.let { RoutePlan(
        origin = origin,
        stopIds = stops.map { it.id },
        legDurationsSec = stops.map { 600L },
        legDistancesM = stops.map { 1000L },
        encodedPolyline = "",
        totalDurationSec = 600L * stops.size,
        fetchedAtEpochSec = System.currentTimeMillis() / 1000,
    ) }

    override suspend fun cachedPlan(dayId: Long): RoutePlan? = cached

    override suspend fun learnedLegDurations(stops: List<StopEntity>, base: List<Long>, speedFactor: Double): List<Long> = base

    override suspend fun recordLegStat(from: StopEntity, to: StopEntity, actualSec: Long, nowEpochSec: Long) {
        legStats += from.id to to.id
    }
}

private data class LegRecord(val navPredictedSec: Long, val planPredictedSec: Long?)

private class FakeLearning : LearningStore {
    val legs = mutableListOf<LegRecord>()

    override suspend fun currentStats(): LearnedStats = LearnedStats()
    override suspend fun recordLeg(
        dayId: Long,
        toStopId: Long,
        navPredictedSec: Long,
        planPredictedSec: Long?,
        actualSec: Long,
        nowEpochSec: Long,
    ) {
        legs += LegRecord(navPredictedSec, planPredictedSec)
    }
    /** Kirjatut stoppikestot; huoltostopit eivät kirjaa. */
    var services = 0

    override suspend fun recordService(dayId: Long, stopId: Long, durationSec: Long, nowEpochSec: Long) {
        services++
    }
}

private class FakeHost : DriveHost {
    override fun message(kind: DriveMessage, detail: String): String = "$kind: $detail"
    override fun routingFailureText(failure: RouteFailure): String = "Reititys epäonnistui: $failure"
    override fun startNavigationService() = Unit
    override fun stopNavigationService() = Unit
    override suspend fun lastKnownLocation(): GeoPoint? = null
}
