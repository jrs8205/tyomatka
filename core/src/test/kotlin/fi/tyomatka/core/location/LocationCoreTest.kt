package fi.tyomatka.core.location

import fi.tyomatka.core.location.LocationSource.FUSED
import fi.tyomatka.core.location.LocationSource.GPS
import fi.tyomatka.core.location.LocationSource.NETWORK
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocationCoreTest {
    /** Realistinen käynnistyksestä kulunut aika. */
    private var clock = 432_000_000_000_000L

    private data class Fix(val name: String, val elapsedNanos: Long, val accuracyM: Float? = 5f)

    private class FakePort : LocationPort<Fix> {
        var available = listOf(FUSED, GPS, NETWORK)
        val known = mutableMapOf<LocationSource, Fix>()
        val denied = mutableSetOf<LocationSource>()
        val listeners = mutableMapOf<LocationSource, (Fix) -> Unit>()
        val closed = mutableListOf<LocationSource>()
        val failing = mutableSetOf<LocationSource>()
        val failingClose = mutableSetOf<LocationSource>()
        val requests = mutableMapOf<LocationSource, (Fix?) -> Unit>()
        val closedRequests = mutableListOf<LocationSource>()

        override fun sources() = available

        override fun lastKnown(source: LocationSource): Fix? {
            if (source in denied) throw SecurityException("ei lupaa")
            return known[source]
        }

        override fun subscribe(source: LocationSource, onFix: (Fix) -> Unit): AutoCloseable {
            if (source in denied) throw SecurityException("ei lupaa")
            if (source in failing) throw IllegalStateException("palvelu kaatui")
            listeners[source] = onFix
            return AutoCloseable {
                if (source in failingClose) throw IllegalStateException("sulkeminen kaatui")
                closed += source
            }
        }

        override fun currentOnce(source: LocationSource, onResult: (Fix?) -> Unit): AutoCloseable {
            if (source in denied) throw SecurityException("ei lupaa")
            if (source in failing) throw IllegalStateException("palvelu kaatui")
            requests[source] = onResult
            return AutoCloseable { closedRequests += source }
        }
    }

    private val port = FakePort()
    private val changes = mutableListOf<LocationSource>()
    private val core = LocationCore(
        port = port,
        describe = { source, fix -> LocationSample(source, fix.elapsedNanos, fix.accuracyM) },
        nowNanos = { clock },
        onSourceChanged = { changes += it },
    )

    private fun fix(name: String, agoSeconds: Long = 0, accuracy: Float? = 5f) = Fix(name, clock - agoSeconds * 1_000_000_000, accuracy)

    private fun TestScope.collectUpdates(into: MutableList<Fix>) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { core.updates().collect { into += it } }

    @Test
    fun `virta tilaa kaikki lähteet ja julkaisee vain hyväksytyt`() = runTest {
        val received = mutableListOf<Fix>()
        collectUpdates(received)
        assertEquals(setOf(FUSED, GPS, NETWORK), port.listeners.keys)

        val first = fix("fused")
        port.listeners.getValue(FUSED)(first)
        clock += 1_000_000_000
        port.listeners.getValue(GPS)(fix("gps"))
        runCurrent()

        assertEquals(listOf(first), received, "tarkka fused ohjaa, gps ei julkaistu")
    }

    @Test
    fun `lähteen vaihtuminen kerrotaan kerran`() = runTest {
        collectUpdates(mutableListOf())

        port.listeners.getValue(GPS)(fix("a"))
        clock += 1_000_000_000
        port.listeners.getValue(GPS)(fix("b"))
        clock += 1_000_000_000
        repeat(3) {
            port.listeners.getValue(FUSED)(fix("c$it"))
            clock += 1_000_000_000
        }
        runCurrent()

        assertEquals(listOf(GPS, FUSED), changes)
    }

    @Test
    fun `lähteen lupavirhe ei estä muita`() = runTest {
        port.denied += GPS
        val received = mutableListOf<Fix>()
        collectUpdates(received)

        assertEquals(setOf(FUSED, NETWORK), port.listeners.keys)
        val first = fix("network")
        port.listeners.getValue(NETWORK)(first)
        runCurrent()
        assertEquals(listOf(first), received)
    }

    @Test
    fun `kaikkien lupavirhe ei kaada virtaa`() = runTest {
        port.denied += listOf(FUSED, GPS, NETWORK)
        val received = mutableListOf<Fix>()
        val job = collectUpdates(received)
        runCurrent()

        assertTrue(job.isActive)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `olematonta lähdettä ei tilata`() = runTest {
        port.available = listOf(GPS)
        collectUpdates(mutableListOf())

        assertEquals(setOf(GPS), port.listeners.keys)
    }

    @Test
    fun `peruutus sulkee kaikki tilaukset`() = runTest {
        val job = collectUpdates(mutableListOf())

        job.cancel()
        runCurrent()

        assertEquals(setOf(FUSED, GPS, NETWORK), port.closed.toSet())
    }

    @Test
    fun `jokaisella kerääjällä on oma valitsin`() = runTest {
        val first = mutableListOf<Fix>()
        val second = mutableListOf<Fix>()
        val job = collectUpdates(first)
        val stale = fix("vanha", accuracy = 5f)
        port.listeners.getValue(FUSED)(stale)
        job.cancel()
        runCurrent()

        clock += 1_000_000_000
        collectUpdates(second)
        val next = fix("uusi")
        port.listeners.getValue(GPS)(next)
        runCurrent()

        assertEquals(listOf(next), second, "edellisen kerääjän fused ei hylkää uutta gps-näytettä")
    }

    @Test
    fun `viimeisin on tuorein kaikista lähteistä`() {
        port.known[FUSED] = fix("vanha", agoSeconds = 100)
        port.known[NETWORK] = fix("uusi", agoSeconds = 3)

        assertEquals("uusi", core.last()?.name)
    }

    @Test
    fun `viimeisin ohittaa lupavirheen`() {
        port.denied += FUSED
        port.known[GPS] = fix("gps", agoSeconds = 5)

        assertEquals("gps", core.last()?.name)
    }

    @Test
    fun `tarkka piste voittaa tuoreemman epätarkan`() {
        port.known[FUSED] = fix("tarkka", agoSeconds = 8, accuracy = 5f)
        port.known[NETWORK] = fix("masto", agoSeconds = 1, accuracy = 2_500f)

        assertEquals("tarkka", core.last()?.name)
    }

    @Test
    fun `kertahaku ei ota tuoreempaa epätarkkaa pistettä`() = runTest {
        port.known[GPS] = fix("tarkka", agoSeconds = 8, accuracy = 5f)
        port.known[NETWORK] = fix("masto", agoSeconds = 1, accuracy = 2_500f)

        assertEquals("tarkka", core.current()?.name)
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun `tarkkojen pisteiden välillä lähdejärjestys ratkaisee`() {
        port.known[GPS] = fix("gps", agoSeconds = 1, accuracy = 3f)
        port.known[FUSED] = fix("fused", agoSeconds = 5, accuracy = 20f)

        assertEquals("fused", core.last()?.name)
    }

    @Test
    fun `ilman tarkkaa pistettä pienin tarkkuusarvo voittaa`() {
        port.known[FUSED] = fix("huono", agoSeconds = 1, accuracy = 900f)
        port.known[GPS] = fix("parempi", agoSeconds = 2, accuracy = 40f)

        assertEquals("parempi", core.last()?.name)
    }

    @Test
    fun `vanhoista pisteistä tuorein`() {
        port.known[FUSED] = fix("tarkka", agoSeconds = 3_600, accuracy = 5f)
        port.known[NETWORK] = fix("uudempi", agoSeconds = 60, accuracy = 500f)

        assertEquals("uudempi", core.last()?.name)
    }

    @Test
    fun `tuore piste voittaa vanhan tarkan`() {
        port.known[FUSED] = fix("tarkka", agoSeconds = 3_600, accuracy = 5f)
        port.known[NETWORK] = fix("tuore", agoSeconds = 5, accuracy = 500f)

        assertEquals("tuore", core.last()?.name)
    }

    @Test
    fun `tulevaisuuteen leimattu tunnettu piste ei kelpaa`() = runTest {
        port.known[GPS] = fix("tulevaisuus", agoSeconds = -3_600)
        port.known[FUSED] = fix("oikea", agoSeconds = 3_600)

        assertEquals("oikea", core.last()?.name)
    }

    @Test
    fun `tulevaisuuteen leimattu näyte ei jäädytä virtaa`() = runTest {
        val received = mutableListOf<Fix>()
        collectUpdates(received)

        port.listeners.getValue(NETWORK)(fix("tulevaisuus", agoSeconds = -3_600))
        val next = fix("oikea")
        port.listeners.getValue(GPS)(next)
        runCurrent()

        assertEquals(listOf(next), received)
    }

    @Test
    fun `sekunnin kelloero sallitaan`() = runTest {
        val received = mutableListOf<Fix>()
        collectUpdates(received)

        val ahead = Fix("hieman edellä", clock + 1_000_000_000)
        port.listeners.getValue(GPS)(ahead)
        runCurrent()

        assertEquals(listOf(ahead), received)
    }

    @Test
    fun `odottamaton poikkeus tilauksessa ei estä muita`() = runTest {
        port.failing += GPS
        val job = collectUpdates(mutableListOf())
        runCurrent()

        assertTrue(job.isActive)
        assertEquals(setOf(FUSED, NETWORK), port.listeners.keys)
    }

    @Test
    fun `sulkemisen poikkeus ei jätä muita auki`() = runTest {
        port.failingClose += FUSED
        val job = collectUpdates(mutableListOf())

        job.cancel()
        runCurrent()

        assertEquals(setOf(GPS, NETWORK), port.closed.toSet())
    }

    @Test
    fun `odottamaton poikkeus kertahaussa ei kaada`() = runTest {
        port.failing += listOf(FUSED, GPS, NETWORK)

        assertNull(core.current())
    }

    @Test
    fun `epäonnistunut kertahaku muistetaan 30 sekuntia`() = runTest {
        val first = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        advanceTimeBy(8_001)
        runCurrent()
        assertNull(first.await())
        port.requests.clear()
        port.known[GPS] = fix("vanha", agoSeconds = 100)

        clock += 29_000_000_000
        val second = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()
        assertTrue(second.isCompleted, "ei uutta odotusta")
        assertEquals("vanha", second.await()?.name)
        assertTrue(port.requests.isEmpty())

        clock += 2_000_000_000
        val third = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()
        assertEquals(setOf(FUSED, GPS, NETWORK), port.requests.keys, "30 s jälkeen yritetään uudelleen")
        third.cancel()
    }

    @Test
    fun `onnistunut kertahaku ei jätä muistia`() = runTest {
        val first = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()
        port.requests.getValue(GPS)(fix("vanha", agoSeconds = 40))
        first.await()
        port.requests.clear()

        val second = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        assertEquals(setOf(FUSED, GPS, NETWORK), port.requests.keys)
        second.cancel()
    }

    @Test
    fun `viimeisin on null ilman pisteitä`() {
        assertNull(core.last())
    }

    @Test
    fun `kertahaku palauttaa alle 30 sekunnin pisteen ilman pyyntöä`() = runTest {
        port.known[GPS] = fix("tuore", agoSeconds = 30)

        assertEquals("tuore", core.current()?.name)
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun `kertahaun ensimmäinen vastaus voittaa ja muut suljetaan`() = runTest {
        port.known[GPS] = fix("vanha", agoSeconds = 31)
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()
        assertEquals(setOf(FUSED, GPS, NETWORK), port.requests.keys)

        port.requests.getValue(NETWORK)(fix("verkko"))
        runCurrent()

        assertEquals("verkko", answer.await()?.name)
        assertEquals(setOf(FUSED, GPS, NETWORK), port.closedRequests.toSet())
    }

    @Test
    fun `tyhjä vastaus odottaa muita lähteitä`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        port.requests.getValue(FUSED)(null)
        runCurrent()
        assertFalse(answer.isCompleted)

        port.requests.getValue(GPS)(fix("gps"))
        runCurrent()
        assertEquals("gps", answer.await()?.name)
    }

    @Test
    fun `tulevaisuuteen leimattu tarkka vastaus ei lopeta kertahakua`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        port.requests.getValue(FUSED)(fix("tulevaisuus", agoSeconds = -3_600))
        runCurrent()
        assertFalse(answer.isCompleted)

        port.requests.getValue(GPS)(fix("oikea"))
        runCurrent()
        assertEquals("oikea", answer.await()?.name)
    }

    @Test
    fun `tulevaisuuteen leimattu epätarkka vastaus ei jää varalle`() = runTest {
        port.known[GPS] = fix("vanha", agoSeconds = 3_600)
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        port.requests.values.toList().forEach { it(fix("tulevaisuus", agoSeconds = -3_600, accuracy = 500f)) }
        runCurrent()

        assertTrue(answer.isCompleted)
        assertEquals("vanha", answer.await()?.name)
    }

    @Test
    fun `kertahaussa sekunnin kelloero sallitaan`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        port.requests.getValue(GPS)(Fix("hieman edellä", clock + 1_000_000_000))
        runCurrent()

        assertEquals("hieman edellä", answer.await()?.name)
    }

    @Test
    fun `kaikkien tyhjät vastaukset palauttavat vanhan pisteen heti`() = runTest {
        port.known[GPS] = fix("vanha", agoSeconds = 3_600)
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        port.requests.values.toList().forEach { it(null) }
        runCurrent()

        assertTrue(answer.isCompleted)
        assertEquals("vanha", answer.await()?.name)
    }

    @Test
    fun `kahdeksan sekunnin jälkeen palautuu vanha piste`() = runTest {
        port.known[GPS] = fix("vanha", agoSeconds = 3_600)
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }

        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(answer.isCompleted)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(answer.isCompleted)
        assertEquals("vanha", answer.await()?.name)
        assertEquals(setOf(FUSED, GPS, NETWORK), port.closedRequests.toSet())
    }

    @Test
    fun `ilman pisteitä kertahaku on null`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }

        advanceTimeBy(8_001)
        runCurrent()

        assertNull(answer.await())
    }

    @Test
    fun `kertahaun lupavirhe ei estä muita`() = runTest {
        port.denied += FUSED
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()
        assertEquals(setOf(GPS, NETWORK), port.requests.keys)

        port.requests.getValue(GPS)(null)
        port.requests.getValue(NETWORK)(null)
        runCurrent()

        assertTrue(answer.isCompleted, "kielletty lähde ei jää odotettavaksi")
        assertNull(answer.await())
    }

    @Test
    fun `ilman lähteitä kertahaku palaa heti`() = runTest {
        port.available = emptyList()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        assertTrue(answer.isCompleted)
        assertNull(answer.await())
    }

    @Test
    fun `kutsujan peruutus sulkee kertahaun`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        answer.cancel()
        runCurrent()

        assertEquals(setOf(FUSED, GPS, NETWORK), port.closedRequests.toSet())
        assertTrue(answer.isCancelled)
    }

    @Test
    fun `välitön epäonnistuminen ei jää muistiin`() = runTest {
        port.denied += listOf(FUSED, GPS, NETWORK)
        assertNull(core.current())

        port.denied.clear()
        val second = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        assertEquals(setOf(FUSED, GPS, NETWORK), port.requests.keys, "luvan myöntäminen tuottaa sijainnin heti")
        second.cancel()
    }

    @Test
    fun `kaikkien tyhjät vastaukset eivät jää muistiin`() = runTest {
        val first = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()
        port.requests.values.toList().forEach { it(null) }
        assertNull(first.await())
        port.requests.clear()

        val second = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        assertEquals(setOf(FUSED, GPS, NETWORK), port.requests.keys)
        second.cancel()
    }

    @Test
    fun `epätarkka vastaus ei lopeta kertahakua`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        port.requests.getValue(NETWORK)(fix("masto", accuracy = 3_000f))
        runCurrent()
        assertFalse(answer.isCompleted)

        port.requests.getValue(GPS)(fix("gps", accuracy = 5f))
        runCurrent()
        assertEquals("gps", answer.await()?.name)
    }

    @Test
    fun `pelkkä epätarkka vastaus palautuu kun muut ovat vastanneet`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        port.requests.getValue(NETWORK)(fix("masto", accuracy = 3_000f))
        port.requests.getValue(FUSED)(null)
        port.requests.getValue(GPS)(null)
        runCurrent()

        assertTrue(answer.isCompleted)
        assertEquals("masto", answer.await()?.name)
    }

    @Test
    fun `useasta epätarkasta vastauksesta palautuu tarkin`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        port.requests.getValue(FUSED)(fix("kohtalainen", accuracy = 500f))
        port.requests.getValue(NETWORK)(fix("masto", accuracy = 3_000f))
        port.requests.getValue(GPS)(null)
        runCurrent()

        assertEquals("kohtalainen", answer.await()?.name)
    }

    @Test
    fun `pelkkä epätarkka vastaus palautuu aikarajalla`() = runTest {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()
        port.requests.getValue(NETWORK)(fix("masto", accuracy = 3_000f))

        advanceTimeBy(8_001)
        runCurrent()

        assertEquals("masto", answer.await()?.name)
    }

    @Test
    fun `tuore mastopaikannus ei riitä kertahaulle`() = runTest {
        port.known[NETWORK] = fix("masto", agoSeconds = 1, accuracy = 3_000f)
        val answer = async(start = CoroutineStart.UNDISPATCHED) { core.current() }
        runCurrent()

        assertFalse(answer.isCompleted)
        assertEquals(setOf(FUSED, GPS, NETWORK), port.requests.keys)
        answer.cancel()
    }

    @Test
    fun `yli sekunnin tulevaisuudessa oleva näyte hylätään`() = runTest {
        val received = mutableListOf<Fix>()
        collectUpdates(received)

        port.listeners.getValue(GPS)(Fix("edellä", clock + 1_500_000_000))
        runCurrent()

        assertTrue(received.isEmpty())
    }
}
