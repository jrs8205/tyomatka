package fi.tyomatka.diag

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagCrashHandlerTest {
    @Test
    fun `kaatuminen kirjoitetaan ja valitetaan eteenpain`() {
        val dir: File = Files.createTempDirectory("diag-crash").toFile().also { it.deleteOnExit() }
        val writer = DiagWriter(dir, today = { LocalDate.of(2026, 10, 22) }, now = { "10-22 07:15:02.123" }, pid = 42, executor = { it.run() })
        var forwarded: Throwable? = null
        val handler = DiagCrashHandler(writer) { _, e -> forwarded = e }
        val error = IllegalStateException("x")

        handler.uncaughtException(Thread.currentThread().also { it.name = "main" }, error)

        val text = File(dir, "2026-10-22.log").readText()
        assertTrue(text.startsWith("10-22 07:15:02.123 E/Diag(42): [crash] main: java.lang.IllegalStateException: x"), text)
        assertTrue(text.contains("DiagCrashHandlerTest"), text)
        assertEquals(error, forwarded)
    }
}
