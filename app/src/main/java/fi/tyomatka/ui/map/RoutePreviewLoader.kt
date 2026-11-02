package fi.tyomatka.ui.map

import fi.tyomatka.core.api.RoutePreview
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Reittivertailun haku: sama reitti ei maksa uutta hakua, stoppien muutos poistaa vanhan vertailun heti, ja pelkkä
 * sijainnin muutos pitää vanhan näkyvissä, kunnes uusi on valmis. Epäonnistunutta reittiä ei yritetä heti uudelleen, koska
 * palvelin on yhteinen ajon reittihakujen kanssa. Samasta syystä kesken olevan haun voi perua keskeyttämättä kutsujan
 * muuta työtä; peruttu haku ei enää julkaise mitään.
 */
class RoutePreviewLoader(
    private val fetch: suspend (GeoPoint, List<GeoPoint>, RouteChoice) -> RoutePreview,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var loadedKey: String? = null
    private var stopsKey: String? = null
    private var current: Map<RouteChoice, RoutePreview> = emptyMap()
    private var failedKey: String? = null
    private var failedAtMs = 0L
    private var running: Job? = null

    /** Vanhat viivat eivät saa näkyä uusien stoppien päällä. */
    fun stopsChanged(stops: List<Pair<Long, GeoPoint>>, publish: (Map<RouteChoice, RoutePreview>) -> Unit) {
        val key = RouteComparison.stopsKey(stops)
        if (key != stopsKey) clear(publish)
        stopsKey = key
    }

    /** Näkyvä vertailu jää, ja peruttu reitti haetaan seuraavalla kerralla uudelleen. */
    fun cancel() {
        running?.cancel()
    }

    fun clear(publish: (Map<RouteChoice, RoutePreview>) -> Unit) {
        cancel()
        forget(publish)
    }

    private fun forget(publish: (Map<RouteChoice, RoutePreview>) -> Unit) {
        current = emptyMap()
        loadedKey = null
        publish(current)
    }

    suspend fun load(
        origin: GeoPoint?,
        stops: List<Pair<Long, GeoPoint>>,
        first: RouteChoice,
        publish: (Map<RouteChoice, RoutePreview>) -> Unit,
    ) {
        if (origin == null) {
            clear(publish)
            return
        }
        stopsChanged(stops, publish)
        val key = RouteComparison.key(origin, stops)
        if (key == loadedKey) return
        if (key == failedKey && nowMs() - failedAtMs < RETRY_AFTER_MS) {
            clear(publish)
            return
        }
        // Oma työ: peruminen katkaisee vain tämän haun, ei kutsujaa.
        coroutineScope {
            running = launch { fetchBoth(key, origin, stops, first, publish) }
        }
    }

    private suspend fun fetchBoth(
        key: String,
        origin: GeoPoint,
        stops: List<Pair<Long, GeoPoint>>,
        first: RouteChoice,
        publish: (Map<RouteChoice, RoutePreview>) -> Unit,
    ) {
        // Ensimmäinen haku näyttää valitun viivan heti; vanhan päivitys vaihtaa molemmat kerralla.
        val progressive = current.isEmpty()
        val fresh = LinkedHashMap<RouteChoice, RoutePreview>()
        try {
            RouteChoice.entries.sortedBy { it != first }.forEach { choice ->
                fresh[choice] = fetch(origin, stops.map { it.second }, choice)
                if (progressive) publish(fresh.toMap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Jonossa ollut virhe voi ehtiä perumisen ohi: peruttu haku ei tyhjennä vertailua eikä jätä estoa.
            currentCoroutineContext().ensureActive()
            failedKey = key
            failedAtMs = nowMs()
            forget(publish)
            throw PreviewFailed(e)
        }
        current = fresh.toMap()
        loadedKey = key
        failedKey = null
        if (!progressive) publish(current)
    }

    /** Vertailu puuttuu, mutta ajon voi aloittaa: kutsuja kirjaa syyn eikä näytä virhettä. */
    class PreviewFailed(cause: Exception) : Exception(cause)

    private companion object {
        const val RETRY_AFTER_MS = 5 * 60_000L
    }
}
