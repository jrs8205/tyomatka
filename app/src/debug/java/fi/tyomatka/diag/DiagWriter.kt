package fi.tyomatka.diag

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Päiväkohtainen lokitiedosto `dir/YYYY-MM-DD.log`:
 * vuorokauden vaihtuessa uusi tiedosto, [keepDays] päivää vanhemmat poistetaan [prune]-kutsulla. Kaapatuilla
 * logcat-riveillä ([write]) on oma päiväbudjetti [maxCaptureBytesPerDay], jotta äänekäs kirjasto ei syö omien
 * tapahtumien ja kaatumisten ([event]) tilaa; koko tiedoston katto on [maxBytesPerDay]. Budjetit palautetaan saman
 * päivän tiedostosta uudelleenkäynnistyksessä. Kumpikin raja kirjoittaa yhden aikaleimallisen loppurivin.
 *
 * Rivit kirjoitetaan [executor]-suorittajassa (oletuksena yksi taustasäie), ei kutsujan säikeessä: pääsäie ja
 * navigointiytimen lukko eivät odota tallennusta. Jono on rajattu ([queueCapacity]); ylivuodossa rivit
 * pudotetaan ja määrä kerrotaan. Kaatuminen kirjoitetaan heti ohi jonon ([eventNow]). Joka rivi huuhdotaan heti,
 * rivinvaihto on aina LF. Kirjoitusvirhe ei saa kaataa sovellusta: se nielaistaan, ja seuraava kirjoitus yrittää
 * avata tiedoston uudelleen.
 */
class DiagWriter(
    private val dir: File,
    private val maxBytesPerDay: Long = MAX_BYTES,
    private val maxCaptureBytesPerDay: Long = MAX_CAPTURE_BYTES,
    private val keepDays: Int = 3,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> String = { TIME.format(LocalDateTime.now()) },
    private val pid: Int = android.os.Process.myPid(),
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "diag-writer").apply { isDaemon = true } },
    queueCapacity: Int = QUEUE_CAPACITY,
) {
    private class Pending(val line: String, val capture: Boolean)

    private val queue = ArrayBlockingQueue<Pending>(queueCapacity)
    private val dropped = AtomicInteger(0)
    private val scheduled = AtomicBoolean(false)

    private var openDate: LocalDate? = null
    private var out: BufferedWriter? = null
    private var written: Long = 0
    private var captured: Long = 0
    private var limitReached = false
    private var captureLimitReached = false

    /** Kaapattu logcat-rivi sellaisenaan; kirjoitetaan taustalla, rivinvaihto lisätään. */
    fun write(line: String) = enqueue(Pending(line, capture = true))

    /** Oma tapahtuma samassa muodossa kuin `logcat -v time`: `MM-DD HH:MM:SS.mmm I/tag(pid): rivi`; taustalla. */
    fun event(tag: String, line: String, level: Char = 'I') = enqueue(Pending(stamp(tag, line, level), capture = false))

    /** Heti kutsujan säikeessä: jonossa olevat rivit ensin, sitten tämä; kaatuminen ei ehdi odottaa taustasäiettä. */
    fun eventNow(tag: String, line: String, level: Char = 'I') {
        flushQueue()
        put(stamp(tag, line, level), capture = false)
    }

    @Synchronized
    private fun flushQueue() {
        while (true) {
            val next = queue.poll() ?: break
            put(next.line, next.capture)
        }
        reportDropped()
    }

    private fun reportDropped() {
        val lost = dropped.getAndSet(0)
        if (lost > 0) put(stamp(Diag.TAG, "[diag] $lost riviä pudotettu jonon täytyttyä", 'W'), capture = false)
    }

    private fun enqueue(pending: Pending) {
        try {
            if (!queue.offer(pending)) {
                dropped.incrementAndGet()
                return
            }
            if (scheduled.compareAndSet(false, true)) executor.execute(::drain)
        } catch (_: Throwable) {
            // Lokitus ei saa koskaan kaataa sovellusta.
        }
    }

    private fun drain() {
        try {
            while (true) {
                val next = queue.poll()
                if (next == null) {
                    reportDropped()
                    scheduled.set(false)
                    // Jonoon ehti tulla rivi tyhjentämisen ja lipun nollauksen välissä: jatketaan samassa työssä.
                    if (queue.isEmpty() || !scheduled.compareAndSet(false, true)) return
                    continue
                }
                put(next.line, next.capture)
            }
        } catch (_: Throwable) {
            scheduled.set(false)
        }
    }

    @Synchronized
    private fun put(line: String, capture: Boolean) {
        try {
            val stream = open() ?: return
            if (limitReached) return
            val bytes = line.toByteArray().size + 1L
            if (capture && (captureLimitReached || captured + bytes > maxCaptureBytesPerDay)) {
                if (!captureLimitReached) {
                    captureLimitReached = true
                    emit(stream, stamp(Diag.TAG, CAPTURE_LIMIT_LINE, 'W'))
                }
                return
            }
            if (written + bytes > maxBytesPerDay) {
                limitReached = true
                emit(stream, stamp(Diag.TAG, LIMIT_LINE, 'W'))
                return
            }
            emit(stream, line)
            if (capture) captured += bytes
        } catch (_: Throwable) {
            // Lokitus ei saa koskaan kaataa sovellusta.
        }
    }

    private fun emit(stream: BufferedWriter, line: String) {
        stream.write(line)
        stream.write(NEWLINE)
        stream.flush()
        written += line.toByteArray().size + 1L
    }

    private fun stamp(tag: String, line: String, level: Char): String =
        runCatching { "${now()} $level/$tag($pid): $line" }.getOrElse { "??-?? ??:??:??.??? $level/$tag($pid): $line" }

    @Synchronized
    fun currentFile(): File = File(dir, "${today()}.log")

    /** Poistaa yli [keepDays] päivää vanhat lokitiedostot; muut tiedostot jätetään rauhaan. */
    @Synchronized
    fun prune() {
        runCatching {
            val oldest = today().minusDays(keepDays.toLong())
            dir.listFiles().orEmpty()
                .filter { it.name.endsWith(".log") }
                .filter { runCatching { LocalDate.parse(it.name.removeSuffix(".log")) }.getOrNull()?.isBefore(oldest) == true }
                .forEach { it.delete() }
        }
    }

    @Synchronized
    fun close() {
        runCatching { out?.close() }
        out = null
        openDate = null
    }

    /**
     * Avaa päivän tiedoston ja palauttaa budjetit sen sisällöstä (omat rivit tunnistaa `/Diag(`-tunniste); epäonnistunut
     * avaus jättää tilan tyhjäksi, jotta seuraava kirjoitus yrittää uudelleen.
     */
    private fun open(): BufferedWriter? {
        val date = today()
        if (out != null && date == openDate) return out
        runCatching { out?.close() }
        out = null
        openDate = null
        dir.mkdirs()
        val file = File(dir, "$date.log")
        val (total, capturedBefore) = measure(file)
        val stream = BufferedWriter(FileWriter(file, true))
        written = total
        captured = capturedBefore
        limitReached = false
        captureLimitReached = false
        openDate = date
        out = stream
        return stream
    }

    /** Tiedoston koko ja kaapattujen rivien tavut (muut kuin `/Diag(`-rivit); virhe → vain koko. */
    private fun measure(file: File): Pair<Long, Long> {
        if (!file.exists()) return 0L to 0L
        val total = file.length()
        val capturedBytes = runCatching {
            var sum = 0L
            file.bufferedReader().useLines { lines ->
                lines.forEach { if (!it.contains(OWN_MARK)) sum += it.toByteArray().size + 1L }
            }
            sum
        }.getOrDefault(0L)
        return total to capturedBytes
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        /** Kaapatut rivit saavat enintään 16 Mt; loput 4 Mt jää omille tapahtumille ja kaatumisille. */
        const val MAX_CAPTURE_BYTES = 16L * 1024 * 1024
        const val QUEUE_CAPACITY = 4_000
        const val LIMIT_LINE = "[diag] päivän yläraja täynnä, kirjoitus loppuu"
        const val CAPTURE_LIMIT_LINE = "[diag] kaapatun lokin yläraja täynnä, vain omat tapahtumat jatkuvat"
        private const val NEWLINE = "\n"
        private const val OWN_MARK = "/" + Diag.TAG + "("
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}
