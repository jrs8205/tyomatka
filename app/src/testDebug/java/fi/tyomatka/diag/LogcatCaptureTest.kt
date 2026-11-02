package fi.tyomatka.diag

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogcatCaptureTest {
    private val dir: File = Files.createTempDirectory("diag-logcat").toFile().also { it.deleteOnExit() }
    private val writer = DiagWriter(dir, today = { LocalDate.of(2026, 10, 22) }, now = { "10-22 07:15:02.123" }, pid = 42, executor = { it.run() })

    @Test
    fun `uudelleenkaynnistys jatkaa viimeisen kaapatun rivin aikaleimasta`() {
        assertEquals("10-22 07:14:59.880", LogcatCapture.sinceOf("10-22 07:14:59.880 I/Zygote (19075): Process created"))
        assertNull(LogcatCapture.sinceOf("--------- beginning of main"))
        assertNull(LogcatCapture.sinceOf(""))
        val cmd = LogcatCapture.defaultCommand(42, "10-22 07:14:59.880")
        assertTrue(cmd.containsAll(listOf("logcat", "-v", "time", "--pid=42", "-T", "10-22 07:14:59.880", "*:V", "Diag:S")), cmd.toString())
        assertTrue("-T" !in LogcatCapture.defaultCommand(42, null))
    }

    @Test
    fun `uudelleenkaynnistys ohittaa jo kirjoitetut rajarivit ja otsikot`() {
        // -T sisällyttää myös täsmälleen muistetun hetken rivit, jotka on jo kirjoitettu.
        val lines = listOf(
            "--------- beginning of main",
            "10-22 07:00:00.000 I/A( 1): nahty",
            "10-22 07:00:00.000 I/A( 1): nahty2",
            "10-22 07:00:01.000 I/A( 1): uusi",
            "10-22 07:00:01.000 I/A( 1): uusi2",
        )
        val seen = listOf("10-22 07:00:00.000 I/A( 1): nahty", "10-22 07:00:00.000 I/A( 1): nahty2")
        assertEquals(lines.drop(3), LogcatCapture.skipReplayed(lines.asSequence(), "10-22 07:00:00.000", seen).toList())
        assertEquals(lines, LogcatCapture.skipReplayed(lines.asSequence(), null, emptyList()).toList())
    }

    @Test
    fun `saman millisekunnin lukematon rivi sailyy uudelleenkaynnistyksessa`() {
        // logcat katkesi kesken saman millisekunnin rivijoukon: vain jo kirjoitetut rivit ohitetaan.
        val lines = listOf(
            "10-22 07:00:00.000 I/A( 1): nahty",
            "10-22 07:00:00.000 I/A( 1): lukematon",
            "10-22 07:00:01.000 I/A( 1): uusi",
        )
        val seen = listOf("10-22 07:00:00.000 I/A( 1): nahty")
        assertEquals(lines.drop(1), LogcatCapture.skipReplayed(lines.asSequence(), "10-22 07:00:00.000", seen).toList())
    }

    @Test
    fun `virhepaluukoodi kasvattaa uusintaviivetta kuten kaatuminenkin`() {
        assertEquals(0, LogcatCapture.nextFailures(3, code = 0, failed = false))
        assertEquals(4, LogcatCapture.nextFailures(3, code = 1, failed = false))
        assertEquals(4, LogcatCapture.nextFailures(3, code = null, failed = true))
        assertEquals(1, LogcatCapture.nextFailures(0, code = 143, failed = false))
    }

    @Test
    fun `kuollut tai puuttuva logcat ei pyorita silmukkaa kuumana eika kaada`() {
        val starts = mutableListOf<String?>()
        val capture = LogcatCapture(
            writer, pid = 42, restartDelayMs = 60,
            command = { _, since -> starts += since; listOf("ei-olemassa-olevaa-komentoa-${System.nanoTime()}") },
            crashDump = false,
        )
        capture.start()
        Thread.sleep(400)
        capture.stop()
        Thread.sleep(150)
        val attempts = starts.size
        assertTrue(attempts in 2..8, "yrityksiä $attempts")
        val text = File(dir, "2026-10-22.log").readText()
        assertTrue(text.contains("[diag] logcat"), text)
        Thread.sleep(200)
        assertEquals(attempts, starts.size, "stop() lopettaa uudet yritykset")
    }
}
