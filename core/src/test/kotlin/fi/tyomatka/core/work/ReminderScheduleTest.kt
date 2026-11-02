package fi.tyomatka.core.work

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReminderScheduleTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private val seven = LocalTime.of(7, 0)
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone)

    @Test
    fun `sama päivä ennen aikaa`() {
        assertEquals(at(2026, 11, 2, 7), nextReminder(at(2026, 11, 2, 6), seven, weekdays))
    }

    @Test
    fun `tasan aikaan seuraava päivä`() {
        assertEquals(at(2026, 11, 3, 7), nextReminder(at(2026, 11, 2, 7), seven, weekdays))
    }

    @Test
    fun `ajan jälkeen seuraava päivä`() {
        assertEquals(at(2026, 11, 3, 7), nextReminder(at(2026, 11, 2, 8), seven, weekdays))
    }

    @Test
    fun `viikonvaihde ohitetaan`() {
        assertEquals(at(2026, 11, 9, 7), nextReminder(at(2026, 11, 6, 8), seven, weekdays))
    }

    @Test
    fun `yksi viikonpäivä ajan jälkeen on viikon päässä`() {
        assertEquals(at(2026, 11, 9, 7), nextReminder(at(2026, 11, 2, 8), seven, setOf(MONDAY)))
    }

    @Test
    fun `ilman päiviä ei muistutusta`() {
        assertNull(nextReminder(at(2026, 11, 2, 6), seven, emptySet()))
    }

    @Test
    fun `kesäaikaan siirtyminen ei pudota päivää`() {
        val next = nextReminder(at(2026, 3, 28, 8), LocalTime.of(3, 30), DayOfWeek.entries.toSet())

        assertEquals(LocalDate.of(2026, 3, 29), next?.toLocalDate())
    }

    @Test
    fun `talviaikaan siirtyminen ei kahdenna päivää ensimmäisen kerran jälkeen`() {
        val afterFirst = ZonedDateTime.ofLocal(LocalDateTime.of(2026, 10, 25, 3, 40), zone, ZoneOffset.ofHours(3))

        assertEquals(LocalDate.of(2026, 10, 26), nextReminder(afterFirst, LocalTime.of(3, 30), DayOfWeek.entries.toSet())?.toLocalDate())
    }

    @Test
    fun `talviaikaan siirtyminen ei kahdenna päivää toisella kerralla`() {
        val secondPass = ZonedDateTime.ofLocal(LocalDateTime.of(2026, 10, 25, 3, 10), zone, ZoneOffset.ofHours(2))

        assertEquals(LocalDate.of(2026, 10, 26), nextReminder(secondPass, LocalTime.of(3, 30), DayOfWeek.entries.toSet())?.toLocalDate())
    }
}
