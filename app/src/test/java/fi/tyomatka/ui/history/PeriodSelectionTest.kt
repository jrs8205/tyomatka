package fi.tyomatka.ui.history

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Työmatka-sivun valitun jakson säännöt; rivit ovat uusimmasta vanhimpaan kuten workPeriodRows ne antaa. */
class PeriodSelectionTest {

    private val keys = listOf("w2026-46", "w2026-45", "w2026-44")

    @Test
    fun `ilman valintaa valitaan uusin`() {
        assertEquals("w2026-46", followSelection(keys, keys, null))
        assertEquals("w2026-46", followSelection(emptyList(), keys, null))
    }

    @Test
    fun `uusin valittuna valinta seuraa uutta uusinta rivia`() {
        assertEquals("w2026-47", followSelection(keys, listOf("w2026-47") + keys, "w2026-46"))
    }

    @Test
    fun `vanhempi valittuna valinta pysyy kun uusi rivi tulee`() {
        assertEquals("w2026-45", followSelection(keys, listOf("w2026-47") + keys, "w2026-45"))
    }

    @Test
    fun `valinta pysyy kun rivi lisataan keskelle`() {
        val days = listOf("d20402", "d20400")
        assertEquals("d20402", followSelection(days, listOf("d20402", "d20401", "d20400"), "d20402"))
        assertEquals("d20400", followSelection(days, listOf("d20402", "d20401", "d20400"), "d20400"))
    }

    @Test
    fun `kadonnut valinta siirtyy uusimpaan`() {
        assertEquals("w2026-46", followSelection(keys, listOf("w2026-46", "w2026-44"), "w2026-45"))
    }

    @Test
    fun `tyhja lista ei valitse mitaan`() {
        assertNull(followSelection(keys, emptyList(), "w2026-45"))
        assertNull(followSelection(keys, emptyList(), null))
    }

    @Test
    fun `samat rivit pitavat valinnan`() {
        assertEquals("w2026-45", followSelection(keys, keys, "w2026-45"))
    }

    @Test
    fun `vanhempi ja uudempi jakso`() {
        assertEquals("w2026-45", olderKey(keys, "w2026-46"))
        assertEquals("w2026-44", olderKey(keys, "w2026-45"))
        assertEquals("w2026-46", newerKey(keys, "w2026-45"))
    }

    @Test
    fun `paissa ei ole seuraavaa`() {
        assertNull(olderKey(keys, "w2026-44"))
        assertNull(newerKey(keys, "w2026-46"))
    }

    @Test
    fun `ilman valintaa uusin on valittuna`() {
        assertEquals("w2026-45", olderKey(keys, null))
        assertNull(newerKey(keys, null))
    }

    @Test
    fun `tuntematon avain ei liiku`() {
        assertNull(olderKey(keys, "w2026-10"))
        assertNull(newerKey(keys, "w2026-10"))
        assertNull(olderKey(emptyList(), null))
    }

    private fun days(from: LocalDate, to: LocalDate) = from.toEpochDay()..to.toEpochDay()

    @Test
    fun `viikon paivat ma-su`() {
        assertEquals(days(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15)), periodDayRange("w2026-46"))
    }

    @Test
    fun `vuoden ensimmainen viikko alkaa edellisena vuonna`() {
        assertEquals(days(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)), periodDayRange("w2026-1"))
    }

    @Test
    fun `kuukauden ja vuoden paivat`() {
        assertEquals(days(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), periodDayRange("m2026-02"))
        assertEquals(days(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), periodDayRange("y2026"))
    }

    @Test
    fun `paivan avain on yksi paiva`() {
        assertEquals(20402L..20402L, periodDayRange("d20402"))
    }

    @Test
    fun `tuntematon avain ei anna valia`() {
        assertNull(periodDayRange(""))
        assertNull(periodDayRange("x"))
        assertNull(periodDayRange("w2026"))
        assertNull(periodDayRange("m2026-13"))
        assertNull(periodDayRange("w2026-54"))
        assertNull(periodDayRange("dabc"))
    }

    @Test
    fun `jokaisen paivan avaimet antavat tarkan valin`() {
        var date = LocalDate.of(2019, 12, 20)
        while (date <= LocalDate.of(2028, 1, 10)) {
            val monday = date.with(DayOfWeek.MONDAY)
            assertEquals(days(monday, monday.plusDays(6)), periodDayRange(workPeriodKey(WorkPeriod.WEEK, date)), "$date viikko")
            val month = YearMonth.from(date)
            assertEquals(days(month.atDay(1), month.atEndOfMonth()), periodDayRange(workPeriodKey(WorkPeriod.MONTH, date)), "$date kuukausi")
            assertEquals(
                days(LocalDate.of(date.year, 1, 1), LocalDate.of(date.year, 12, 31)),
                periodDayRange(workPeriodKey(WorkPeriod.YEAR, date)),
                "$date vuosi",
            )
            assertEquals(days(date, date), periodDayRange(workPeriodKey(WorkPeriod.DAY, date)), "$date paiva")
            date = date.plusDays(1)
        }
    }

    @Test
    fun `vuoden ensimmainen viikko kun vuosi alkaa perjantaina`() {
        assertEquals(days(LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 10)), periodDayRange("w2027-1"))
        assertEquals(days(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)), periodDayRange("w2026-53"))
    }

    @Test
    fun `olematon viikko 53 ei anna valia`() {
        assertNull(periodDayRange("w2025-53"))
    }

    @Test
    fun `vain sovelluksen oma avainmuoto kelpaa`() {
        assertNull(periodDayRange("w2026-46-1"))
        assertNull(periodDayRange("w2026-046"))
        assertNull(periodDayRange("w+2026-+46"))
        assertNull(periodDayRange("d+5"))
        assertNull(periodDayRange("y+2026"))
        assertNull(periodDayRange("d9999999999999"))
    }
}
