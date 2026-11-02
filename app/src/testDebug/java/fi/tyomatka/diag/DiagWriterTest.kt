package fi.tyomatka.diag

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagWriterTest {
    private val dir: File = Files.createTempDirectory("diag-test").toFile().also { it.deleteOnExit() }
    private var today: LocalDate = LocalDate.of(2026, 10, 22)

    /** Testit kirjoittavat suoraan (Executor ajaa tehtävän heti); jonotestit antavat oman suorittajan. */
    private fun writer(
        maxBytes: Long = DiagWriter.MAX_BYTES,
        keepDays: Int = 3,
        captureBytes: Long = DiagWriter.MAX_CAPTURE_BYTES,
        executor: Executor = Executor { it.run() },
        capacity: Int = DiagWriter.QUEUE_CAPACITY,
    ) = DiagWriter(
        dir, maxBytesPerDay = maxBytes, maxCaptureBytesPerDay = captureBytes, keepDays = keepDays,
        today = { today }, now = { "10-22 07:15:02.123" }, pid = 42, executor = executor, queueCapacity = capacity,
    )

    @Test
    fun `rivi menee paivan tiedostoon`() {
        val w = writer()
        w.write("10-22 07:15:01.000 I/Test(42): hei")
        assertEquals("2026-10-22.log", w.currentFile().name)
        assertEquals("10-22 07:15:01.000 I/Test(42): hei\n", File(dir, "2026-10-22.log").readText())
    }

    @Test
    fun `event muotoilee logcat-rivin`() {
        val w = writer()
        w.event("Diag", "ajo: aloitus")
        assertEquals("10-22 07:15:02.123 I/Diag(42): ajo: aloitus\n", File(dir, "2026-10-22.log").readText())
    }

    @Test
    fun `paivan vaihtuessa avataan uusi tiedosto`() {
        val w = writer()
        w.write("eka")
        today = LocalDate.of(2026, 10, 23)
        w.write("toka")
        assertEquals("eka\n", File(dir, "2026-10-22.log").readText())
        assertEquals("toka\n", File(dir, "2026-10-23.log").readText())
    }

    @Test
    fun `ylaraja lopettaa kirjoituksen`() {
        val w = writer(maxBytes = 120, captureBytes = 120)
        repeat(10) { w.event("Diag", "rivi $it") }
        val text = File(dir, "2026-10-22.log").readText()
        assertTrue(text.endsWith("W/Diag(42): " + DiagWriter.LIMIT_LINE + "\n"), text)
        val size = File(dir, "2026-10-22.log").length()
        w.event("Diag", "ei enaa")
        w.write("ei enaa")
        assertEquals(size, File(dir, "2026-10-22.log").length())
        assertFalse(text.contains("ei enaa"))
    }

    @Test
    fun `kaapattujen rivien budjetti ei sulje omia tapahtumia`() {
        // Kaapattu logcat loppuu omaan rajaansa aikaleimallisella loppurivillä; omat tapahtumat ja kaatumiset jatkuvat.
        val w = writer(maxBytes = 10_000, captureBytes = 50)
        repeat(5) { w.write("kaapattu rivi numero $it") }
        w.event("Diag", "oma tapahtuma")
        val text = File(dir, "2026-10-22.log").readText()
        assertTrue(text.contains("10-22 07:15:02.123 W/Diag(42): " + DiagWriter.CAPTURE_LIMIT_LINE + "\n"), text)
        assertTrue(text.endsWith("I/Diag(42): oma tapahtuma\n"), text)
        assertEquals(1, text.split(DiagWriter.CAPTURE_LIMIT_LINE).size - 1)
        assertFalse(text.contains("kaapattu rivi numero 4"))
    }

    @Test
    fun `epaonnistunut uudelleenavaus ei jumita kirjoitusta`() {
        val w = writer()
        w.write("eka")
        today = LocalDate.of(2026, 10, 23)
        File(dir, "2026-10-23.log").mkdir() // uuden päivän tiedosto ei avaudu
        w.write("katoaa")
        File(dir, "2026-10-23.log").delete()
        w.write("toka")
        assertEquals("toka\n", File(dir, "2026-10-23.log").readText())
    }

    @Test
    fun `tapahtumat kirjoitetaan taustasuorittajassa eika kutsujan saikeessa`() {
        val tasks = ArrayDeque<Runnable>()
        val w = writer(executor = { tasks += it })
        w.event("Diag", "a")
        w.write("b")
        assertFalse(File(dir, "2026-10-22.log").exists())
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals("10-22 07:15:02.123 I/Diag(42): a\nb\n", File(dir, "2026-10-22.log").readText())
    }

    @Test
    fun `jonon ylivuoto pudottaa rivit ja kertoo maaran`() {
        val tasks = ArrayDeque<Runnable>()
        val w = writer(executor = { tasks += it }, capacity = 2)
        w.write("1")
        w.write("2")
        w.write("3")
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        val text = File(dir, "2026-10-22.log").readText()
        assertTrue(text.startsWith("1\n2\n"), text)
        assertFalse(text.contains("3\n"))
        assertTrue(text.contains("W/Diag(42): [diag] 1 riviä pudotettu jonon täytyttyä"), text)
    }

    @Test
    fun `kaatuminen kirjoitetaan heti ohi jonon`() {
        val tasks = ArrayDeque<Runnable>()
        val w = writer(executor = { tasks += it })
        w.write("jonossa")
        w.eventNow("Diag", "[crash] heti", level = 'E')
        val text = File(dir, "2026-10-22.log").readText()
        assertEquals("jonossa\n10-22 07:15:02.123 E/Diag(42): [crash] heti\n", text)
    }

    @Test
    fun `kaappausbudjetti sailyy saman paivan uudelleenkaynnistyksessa`() {
        val first = writer(maxBytes = 10_000, captureBytes = 50)
        repeat(3) { first.write("kaapattu rivi numero $it") }
        val second = writer(maxBytes = 10_000, captureBytes = 50)
        second.write("uuden prosessin kaapattu rivi")
        second.event("Diag", "oma tapahtuma")
        val text = File(dir, "2026-10-22.log").readText()
        assertFalse(text.contains("uuden prosessin"), text)
        assertTrue(text.endsWith("I/Diag(42): oma tapahtuma\n"), text)
    }

    @Test
    fun `prune poistaa yli kolme paivaa vanhat`() {
        listOf(5L, 3L, 2L, 0L).forEach { File(dir, "${today.minusDays(it)}.log").writeText("x") }
        File(dir, "muu.txt").writeText("x")
        writer().prune()
        assertEquals(
            setOf("2026-10-19.log", "2026-10-20.log", "2026-10-22.log", "muu.txt"),
            dir.list()!!.toSet(),
        )
    }
}
