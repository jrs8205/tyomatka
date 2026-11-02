package fi.tyomatka.core.location

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Sijaintipalvelun rajapinta. Kutsut voivat heittää lähdekohtaisesti, esimerkiksi ilman lupaa. */
interface LocationPort<F> {
    /** Puhelimessa olemassa olevat lähteet. */
    fun sources(): List<LocationSource>

    fun lastKnown(source: LocationSource): F?

    fun subscribe(source: LocationSource, onFix: (F) -> Unit): AutoCloseable

    /** Yksi nykyinen sijainti; null, kun lähde ei sitä saa. */
    fun currentOnce(source: LocationSource, onResult: (F?) -> Unit): AutoCloseable
}

/** Puhelimen oman sijainnin päätökset ilman Androidia: jatkuva virta, kertahaku ja viimeisin tunnettu piste. */
class LocationCore<F : Any>(
    private val port: LocationPort<F>,
    private val describe: (LocationSource, F) -> LocationSample,
    private val nowNanos: () -> Long,
    private val onSourceChanged: (LocationSource) -> Unit = {},
) {
    @Volatile
    private var missNanos: Long? = null

    /** Kaikki lähteet tilataan, ja vain [FixSelector]in hyväksymät näytteet julkaistaan. Ilman lupaa pisteitä ei vain tule. */
    fun updates(): Flow<F> = callbackFlow {
        val selector = FixSelector()
        val subscriptions = port.sources().mapNotNull { source ->
            guarded {
                port.subscribe(source) { fix ->
                    if (synchronized(selector) { select(selector, source, fix) }) trySend(fix)
                }
            }
        }
        awaitClose { subscriptions.forEach { subscription -> guarded { subscription.close() } } }
    }

    private fun select(selector: FixSelector, source: LocationSource, fix: F): Boolean {
        val sample = describe(source, fix)
        if (isFromFuture(sample, nowNanos())) return false
        val before = selector.active
        val accepted = selector.accept(sample)
        if (selector.active != before) onSourceChanged(source)
        return accepted
    }

    /**
     * Tuore riittävän tarkka piste, jos sellainen on tiedossa; muuten ensimmäinen riittävän tarkka vastaus lähteiltä enintään
     * [WAIT_MS] ajassa. Lopuksi tarkin saatu vastaus tai paras tunnettu piste iästä riippumatta, jotta kartta ja haku saavat
     * edes jonkin sijainnin. Aikakatkaisu muistetaan hetken, ettei jokainen ennuste odota samaa aikarajaa ja käynnistä GPS:ää.
     */
    suspend fun current(): F? {
        val now = nowNanos()
        val known = bestKnown(now)
        if (known != null && now - known.second.elapsedNanos <= KNOWN_FRESH_NANOS && known.second.isUsableAnswer()) return known.first
        val miss = missNanos
        if (miss != null && now - miss < MISS_MEMORY_NANOS) return known?.first
        val rough = AtomicReference<F?>(null)
        var finished = false
        val answer = withTimeoutOrNull(WAIT_MS) { firstAnswer(rough).also { finished = true } }
        // Vain aikakatkaisu muistetaan: välitön epäonnistuminen (esimerkiksi lupa puuttuu) voi korjautua heti.
        missNanos = if (finished) null else nowNanos()
        return answer ?: rough.get() ?: last()
    }

    private suspend fun firstAnswer(rough: AtomicReference<F?>): F? = callbackFlow {
        val sources = port.sources()
        val pending = AtomicInteger(sources.size)
        val answered = { if (pending.decrementAndGet() <= 0) channel.close() }
        if (sources.isEmpty()) channel.close()
        val requests = sources.mapNotNull { source ->
            guarded {
                port.currentOnce(source) { fix ->
                    // Tulevaisuuteen leimattu piste on sama kuin tyhjä vastaus: se ei lopeta hakua eikä jää varalle.
                    val sample = fix?.let { describe(source, it) }?.takeUnless { isFromFuture(it, nowNanos()) }
                    when {
                        fix == null || sample == null -> answered()
                        sample.isUsableAnswer() -> trySend(fix)
                        else -> {
                            rough.updateAndGet { old -> better(old, fix, source) }
                            answered()
                        }
                    }
                }
            }.also { if (it == null) answered() }
        }
        awaitClose { requests.forEach { request -> guarded { request.close() } } }
    }.firstOrNull()

    /** Mastopaikannus voi olla kilometrien päässä, joten se ei lopeta hakua eikä riitä tuoreeksi pisteeksi. */
    private fun LocationSample.isUsableAnswer() = (accuracyM ?: Float.MAX_VALUE) <= ANSWER_ACCURACY_M

    private fun better(old: F?, fix: F, source: LocationSource): F {
        val oldAccuracy = old?.let { describe(source, it).accuracyM ?: Float.MAX_VALUE } ?: return fix
        return if ((describe(source, fix).accuracyM ?: Float.MAX_VALUE) < oldAccuracy) fix else old
    }

    /** Paras tunnettu piste kaikista lähteistä ilman uutta hakua. */
    fun last(): F? = bestKnown(nowNanos())?.first

    /** Tuoreista ensin järjestyksessä ensimmäinen tarkka, sitten tarkin; autossa `network` voi olla kilometrien päässä. Muuten tuorein. */
    private fun bestKnown(now: Long): Pair<F, LocationSample>? {
        val known = port.sources()
            .mapNotNull { source -> guarded { port.lastKnown(source) }?.let { it to describe(source, it) } }
            .filterNot { isFromFuture(it.second, now) }
        val recent = known.filter { now - it.second.elapsedNanos <= KNOWN_FRESH_NANOS }
        return recent.firstOrNull { it.second.isGood() }
            ?: recent.minByOrNull { it.second.accuracyM ?: Float.MAX_VALUE }
            ?: known.maxByOrNull { it.second.elapsedNanos }
    }

    /** Järjestelmä ei aina hylkää tulevaisuuteen leimattua näytettä, ja se jäädyttäisi valinnan. */
    private fun isFromFuture(sample: LocationSample, now: Long) = sample.elapsedNanos - now > MAX_FUTURE_NANOS

    /** Sijaintipalvelun virhe ei saa kaataa sovellusta: lohkoissa ei ole keskeytyviä kutsuja, joten peruutus ei jää tähän. */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        const val KNOWN_FRESH_NANOS = 30_000_000_000L
        const val MISS_MEMORY_NANOS = 30_000_000_000L
        const val MAX_FUTURE_NANOS = 1_000_000_000L
        const val WAIT_MS = 8_000L
        const val ANSWER_ACCURACY_M = 100f
    }
}
