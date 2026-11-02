package fi.tyomatka.diag

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Kaappaa oman prosessin Androidin lokin tiedostoon: `logcat --pid=<oma>` puskureista main, system ja crash.
 * Tunniste `Diag` vaimennetaan, koska omat tapahtumat kirjoitetaan tiedostoon suoraan ([DiagStartup]); näin ne eivät
 * tuplaannu eivätkä katoa, vaikka kaappaus ei käynnistyisi. Kun `logcat` päättyy (välimuistijäädytys, logd:n katkos),
 * se käynnistetään uudelleen viimeisen kaapatun rivin aikaleimasta (`-T`); saman millisekunnin riveistä ohitetaan vain
 * ne, jotka on jo kirjoitettu. Odotus kasvaa [restartDelayMs]:stä enintään 12-kertaiseksi myös virhepaluukoodin jälkeen.
 * Ennen kaappausta luetaan edellisen ajon kaatuminen crash-puskurista kerran ([crashDump]); viimeksi luettu aikaleima
 * muistetaan tiedostossa `crash.last`. Mikään tässä ei saa kaataa sovellusta: koko silmukka on suojattu.
 */
class LogcatCapture(
    private val writer: DiagWriter,
    private val pid: Int,
    private val restartDelayMs: Long = 5_000,
    private val command: (pid: Int, since: String?) -> List<String> = ::defaultCommand,
    private val crashDump: Boolean = true,
    private val stateDir: File? = null,
) {
    @Volatile
    private var running = false

    @Volatile
    private var process: Process? = null

    /** Viimeisin kirjoitettu aikaleima ja sen millisekunnin jo kirjoitetut rivit. */
    @Volatile
    private var since: String? = null
    private val seenAtSince = mutableListOf<String>()

    @Synchronized
    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "diag-logcat").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        process?.destroy()
    }

    private fun loop() {
        try {
            if (crashDump) dumpPreviousCrash()
            var failures = 0
            while (running) {
                val exit = runCatching { capture() }
                if (!running) break
                val code = exit.getOrNull()
                failures = nextFailures(failures, code, exit.isFailure)
                writer.event(
                    Diag.TAG,
                    "[diag] logcat päättyi (${exit.exceptionOrNull()?.toString() ?: "paluukoodi $code"}), käynnistetään uudelleen",
                    level = 'W',
                )
                Thread.sleep(restartDelayMs * minOf(1 + failures, 12))
            }
        } catch (_: Throwable) {
            // Lokitus ei saa koskaan kaataa sovellusta; keskeytys lopettaa silmukan.
        }
    }

    /** Ajaa logcatin päättymiseen asti ja palauttaa paluukoodin; rivit tiedostoon ja aikaleima muistiin. */
    private fun capture(): Int {
        val p = ProcessBuilder(command(pid, since)).redirectErrorStream(true).start()
        process = p
        if (!running) p.destroy()
        val startedFrom = since
        val seen = synchronized(seenAtSince) { seenAtSince.toList() }
        BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
            skipReplayed(lines, startedFrom, seen).forEach { line ->
                sinceOf(line)?.let { stamp ->
                    synchronized(seenAtSince) {
                        if (stamp != since) seenAtSince.clear()
                        seenAtSince += line
                    }
                    since = stamp
                }
                writer.write(line)
            }
        }
        return p.waitFor()
    }

    /** Edellisen ajon kaatuminen voi olla vielä puhelimen crash-puskurissa; liitetään kerran, ei joka käynnistyksessä. */
    private fun dumpPreviousCrash() {
        runCatching {
            val marker = stateDir?.let { File(it, "crash.last") }
            val last = marker?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            val cmd = mutableListOf("logcat", "-b", "crash", "-d", "-v", "time")
            if (last != null) cmd += listOf("-T", last)
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val lines = BufferedReader(InputStreamReader(p.inputStream)).useLines { seq ->
                seq.filter { it.isNotBlank() && !it.startsWith("---------") }.toList()
            }
            p.waitFor()
            // -T sisällyttää myös täsmälleen muistetun hetken rivin: ohitetaan jo nähty.
            val fresh = if (last != null) lines.dropWhile { sinceOf(it) == last } else lines
            if (fresh.isNotEmpty()) {
                writer.event(Diag.TAG, "[diag] crash-puskuri käynnistyksessä (${fresh.size} riviä):")
                fresh.forEach { writer.write(it) }
                fresh.asReversed().firstNotNullOfOrNull(::sinceOf)?.let { stamp -> marker?.let { runCatching { it.writeText(stamp) } } }
            }
        }
    }

    companion object {
        private val STAMP = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}")

        /** `logcat -v time` -rivin aikaleima ("MM-DD HH:MM:SS.mmm") tai null, jos rivi ei ala sillä. */
        fun sinceOf(line: String): String? = STAMP.find(line)?.value

        /** Virhelaskuri uusintaviivettä varten: nollautuu vain siistin päättymisen (paluukoodi 0) jälkeen. */
        fun nextFailures(failures: Int, code: Int?, failed: Boolean): Int = if (failed || code != 0) failures + 1 else 0

        /**
         * Uudelleenkäynnistyksen alussa ohitetaan puskurin otsikot ja ne muistetun hetken rivit, jotka on jo kirjoitettu
         * ([seen], esiintymien mukaan): `-T` sisällyttää koko millisekunnin, mutta katkos on voinut osua sen keskelle.
         */
        fun skipReplayed(lines: Sequence<String>, since: String?, seen: List<String>): Sequence<String> {
            if (since == null) return lines
            val remaining = seen.toMutableList()
            var skipping = true
            return lines.filter { line ->
                if (skipping) {
                    val stamp = sinceOf(line)
                    if (stamp == null) return@filter false
                    if (stamp == since && remaining.remove(line)) return@filter false
                    skipping = false
                }
                true
            }
        }

        fun defaultCommand(pid: Int, since: String?): List<String> = buildList {
            addAll(listOf("logcat", "-v", "time", "--pid=$pid", "-b", "main,system,crash"))
            if (since != null) addAll(listOf("-T", since))
            addAll(listOf("*:V", "${Diag.TAG}:S"))
        }
    }
}
