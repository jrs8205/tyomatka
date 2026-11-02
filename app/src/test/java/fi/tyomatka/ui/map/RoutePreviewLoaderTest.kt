package fi.tyomatka.ui.map

import fi.tyomatka.core.api.ApiException
import fi.tyomatka.core.api.RoutePreview
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutePreviewLoaderTest {
    private val origin = GeoPoint(60.2905, 24.8530)
    private val moved = GeoPoint(60.3105, 24.8530)
    private val stops = listOf(1L to GeoPoint(60.1, 24.1))
    private val otherStops = listOf(2L to GeoPoint(60.2, 24.2))

    private val calls = mutableListOf<RouteChoice>()
    private var failure: Exception? = null
    private var clock = 1_000_000L
    private val published = mutableListOf<Set<RouteChoice>>()

    /** Monenneksi tullut pyyntö jää odottamaan; muut valmistuvat heti. */
    private var holdCall = 0
    private val gate = CompletableDeferred<Unit>()
    private var cancelledRequests = 0
    private var onFetch: () -> Unit = {}

    private val loader = RoutePreviewLoader(
        fetch = { _, _, choice ->
            calls += choice
            if (calls.size == holdCall) {
                try {
                    gate.await()
                } catch (e: CancellationException) {
                    cancelledRequests++
                    throw e
                }
            }
            onFetch()
            failure?.let { throw it }
            RoutePreview(listOf(GeoPoint(60.0, 24.0), GeoPoint(60.1, 24.1)), 1_000, 60)
        },
        nowMs = { clock },
    )

    private fun publish(previews: Map<RouteChoice, RoutePreview>) {
        published += previews.keys
    }

    @Test
    fun `molemmat reitit haetaan valittu ensin`() = runTest {
        loader.load(origin, stops, RouteChoice.SHORTEST, ::publish)

        assertEquals(listOf(RouteChoice.SHORTEST, RouteChoice.NORMAL), calls)
        assertEquals(RouteChoice.entries.toSet(), published.last())
    }

    @Test
    fun `sama reitti ei tee uutta hakua`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        calls.clear()

        loader.load(GeoPoint(60.2906, 24.8531), stops, RouteChoice.NORMAL, ::publish)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `ilman sijaintia vanha vertailu poistuu`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)

        loader.load(null, otherStops, RouteChoice.NORMAL, ::publish)

        assertEquals(emptySet(), published.last())
        calls.clear()
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        assertEquals(2, calls.size, "tyhjennyksen jälkeen haetaan uudelleen")
    }

    @Test
    fun `stoppien muutos poistaa vanhan vertailun heti`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        published.clear()

        loader.stopsChanged(otherStops, ::publish)

        assertEquals(listOf(emptySet<RouteChoice>()), published)
    }

    @Test
    fun `samat stopit eivät poista vertailua`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        published.clear()

        loader.stopsChanged(stops, ::publish)

        assertTrue(published.isEmpty())
    }

    @Test
    fun `pelkkä sijainnin muutos pitää vanhan vertailun kunnes uusi on valmis`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        published.clear()

        loader.load(moved, stops, RouteChoice.NORMAL, ::publish)

        assertEquals(listOf(RouteChoice.entries.toSet()), published, "ei tyhjää eikä vajaata välivaihetta")
    }

    @Test
    fun `epäonnistunut haku poistaa vertailun eikä yritä heti uudelleen`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        failure = ApiException("HTTP 429", 429)
        calls.clear()

        assertFailsWith<RoutePreviewLoader.PreviewFailed> { loader.load(moved, stops, RouteChoice.NORMAL, ::publish) }
        assertEquals(emptySet(), published.last())
        assertEquals(1, calls.size)

        calls.clear()
        clock += 60_000
        loader.load(moved, stops, RouteChoice.NORMAL, ::publish)
        assertTrue(calls.isEmpty(), "minuutin päästä ei vielä uutta yritystä")

        failure = null
        clock += 5 * 60_000
        loader.load(moved, stops, RouteChoice.NORMAL, ::publish)
        assertEquals(2, calls.size)
        assertEquals(RouteChoice.entries.toSet(), published.last())
    }

    @Test
    fun `kutsujan peruutus katkaisee pyynnön eikä merkitse reittiä haetuksi`() = runTest {
        holdCall = 1
        var continued = false
        val job = launch {
            loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
            continued = true
        }
        runCurrent()

        job.cancel()
        job.join()
        assertEquals(1, cancelledRequests)
        assertFalse(continued, "peruutus nousee kutsujalle")

        holdCall = 0
        calls.clear()
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        assertEquals(2, calls.size)
    }

    @Test
    fun `peruminen katkaisee kesken olevan pyynnön eikä hae toista reittiä`() = runTest {
        holdCall = 1
        val job = launch { loader.load(origin, stops, RouteChoice.NORMAL, ::publish) }
        runCurrent()
        assertEquals(1, calls.size)

        loader.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(1, cancelledRequests)
        assertEquals(1, calls.size, "toista reittiä ei enää haeta")
        assertTrue(published.all { it.isEmpty() }, "peruttu haku ei julkaise reittejä")
    }

    @Test
    fun `peruminen ei koske kutsujan muuta työtä`() = runTest {
        holdCall = 1
        var continued = false
        val job = launch {
            loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
            continued = true
        }
        runCurrent()

        loader.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(1, cancelledRequests)
        assertTrue(continued, "vain vertailu perutaan, ei kutsujaa")
    }

    @Test
    fun `peruminen ei haittaa seuraavaa hakua`() = runTest {
        holdCall = 1
        val job = launch { loader.load(origin, stops, RouteChoice.NORMAL, ::publish) }
        runCurrent()
        loader.cancel()
        gate.complete(Unit)
        job.join()

        holdCall = 0
        calls.clear()
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)

        assertEquals(2, calls.size)
        assertEquals(RouteChoice.entries.toSet(), published.last())
    }

    @Test
    fun `peruminen ilman hakua ei tee mitään`() = runTest {
        loader.cancel()

        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)

        assertEquals(RouteChoice.entries.toSet(), published.last())
    }

    @Test
    fun `tyhjennys peruu haun eikä tulos palauta vertailua`() = runTest {
        holdCall = 2
        val job = launch { loader.load(origin, stops, RouteChoice.NORMAL, ::publish) }
        runCurrent()
        assertEquals(setOf(RouteChoice.NORMAL), published.last())

        loader.clear(::publish)
        gate.complete(Unit)
        job.join()

        assertEquals(emptySet(), published.last())
        assertEquals(1, cancelledRequests)
    }

    @Test
    fun `stoppien muutos poistaa osittain julkaistun vertailun ja peruu haun`() = runTest {
        holdCall = 2
        val job = launch { loader.load(origin, stops, RouteChoice.NORMAL, ::publish) }
        runCurrent()
        assertEquals(setOf(RouteChoice.NORMAL), published.last())

        loader.stopsChanged(otherStops, ::publish)
        assertEquals(emptySet(), published.last(), "vanha viiva ei saa jäädä uusien stoppien päälle")

        gate.complete(Unit)
        job.join()
        assertEquals(emptySet(), published.last())
        assertEquals(1, cancelledRequests)
    }

    @Test
    fun `samat stopit eivät poista osittaista vertailua eivätkä peru hakua`() = runTest {
        holdCall = 2
        val job = launch { loader.load(origin, stops, RouteChoice.NORMAL, ::publish) }
        runCurrent()

        loader.stopsChanged(stops, ::publish)
        assertEquals(setOf(RouteChoice.NORMAL), published.last())

        gate.complete(Unit)
        job.join()
        assertEquals(0, cancelledRequests)
        assertEquals(RouteChoice.entries.toSet(), published.last())
    }

    @Test
    fun `lykätty haku poistaa myös osittaisen vertailun`() = runTest {
        failure = ApiException("HTTP 429", 429)
        assertFailsWith<RoutePreviewLoader.PreviewFailed> { loader.load(moved, stops, RouteChoice.NORMAL, ::publish) }
        failure = null

        calls.clear()
        holdCall = 2
        val job = launch { loader.load(origin, stops, RouteChoice.NORMAL, ::publish) }
        runCurrent()
        assertEquals(setOf(RouteChoice.NORMAL), published.last())
        loader.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(setOf(RouteChoice.NORMAL), published.last())

        holdCall = 0
        calls.clear()
        loader.load(moved, stops, RouteChoice.NORMAL, ::publish)

        assertTrue(calls.isEmpty(), "epäonnistunutta reittiä ei vielä yritetä")
        assertEquals(emptySet(), published.last(), "toisen paikan viiva ei saa jäädä näkyviin")
    }

    @Test
    fun `peruminen jättää täyden vertailun näkyviin ja uusi haku vaihtaa sen kerralla`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        published.clear()
        calls.clear()
        holdCall = 1
        val job = launch { loader.load(moved, stops, RouteChoice.NORMAL, ::publish) }
        runCurrent()
        loader.cancel()
        gate.complete(Unit)
        job.join()
        assertTrue(published.isEmpty(), "näkyvä vertailu jää")

        holdCall = 0
        calls.clear()
        loader.load(moved, stops, RouteChoice.NORMAL, ::publish)

        assertEquals(2, calls.size, "peruttu reitti haetaan uudelleen")
        assertEquals(listOf(RouteChoice.entries.toSet()), published, "ei vajaata välivaihetta")
    }

    @Test
    fun `perutun päivityksen jälkeen näkyvä reitti ei tee uutta hakua`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        calls.clear()
        holdCall = 1
        val job = launch { loader.load(moved, stops, RouteChoice.NORMAL, ::publish) }
        runCurrent()
        loader.cancel()
        gate.complete(Unit)
        job.join()

        holdCall = 0
        calls.clear()
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)

        assertTrue(calls.isEmpty(), "näkyvä vertailu on jo tästä paikasta")
    }

    @Test
    fun `peruttu haku ei tulkitse myöhästynyttä virhettä epäonnistumiseksi`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        published.clear()
        calls.clear()
        failure = ApiException("HTTP 429", 429)
        onFetch = { loader.cancel() }

        loader.load(moved, stops, RouteChoice.NORMAL, ::publish)
        assertTrue(published.isEmpty(), "vertailu ei tyhjene")

        failure = null
        onFetch = {}
        calls.clear()
        loader.load(moved, stops, RouteChoice.NORMAL, ::publish)
        assertEquals(2, calls.size, "reitti ei jää estoon")
    }

    @Test
    fun `virheen jälkeen paluu edelliseen paikkaan hakee uudelleen`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        failure = ApiException("HTTP 429", 429)
        assertFailsWith<RoutePreviewLoader.PreviewFailed> { loader.load(moved, stops, RouteChoice.NORMAL, ::publish) }
        failure = null

        calls.clear()
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)

        assertEquals(2, calls.size, "vertailu tyhjeni virheessä")
        assertEquals(RouteChoice.entries.toSet(), published.last())
    }

    @Test
    fun `haun itse heittämä peruutus ei jätä estoa`() = runTest {
        failure = CancellationException("peruttu")
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)

        failure = null
        calls.clear()
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)

        assertEquals(2, calls.size)
    }

    @Test
    fun `tyhjennys unohtaa haetun reitin`() = runTest {
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)

        loader.clear(::publish)
        assertEquals(emptySet(), published.last())

        calls.clear()
        loader.load(origin, stops, RouteChoice.NORMAL, ::publish)
        assertEquals(2, calls.size)
    }
}
