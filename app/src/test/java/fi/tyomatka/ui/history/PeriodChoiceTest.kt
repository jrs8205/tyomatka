package fi.tyomatka.ui.history

import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.PeriodFuel
import fi.tyomatka.data.repo.PeriodFuels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Nuolirivin ja pagerin valinta sekä muiden jaksojen lista. */
class PeriodChoiceTest {

    private val keys = listOf("w2026-46", "w2026-45", "w2026-44")

    private fun row(key: String, fuel: PeriodFuels? = null) =
        WorkPeriodRow(key = key, label = key, km = 10, durationSec = 3600, ongoing = false, missingReadings = false, fuel = fuel)

    private fun fuel(kind: FuelKind, liters: Double) =
        PeriodFuel(kind, liters, mapOf("EUR" to liters * 1.8), 1.8, 9.2, 0.17, currency = "EUR")

    @Test
    fun `oletuksena valittuna on uusin`() {
        val choice = PeriodChoice(keys)
        assertEquals("w2026-46", choice.selected)
        assertEquals(0, choice.page)
    }

    @Test
    fun `uusimmalla ei ole uudempaa eika vanhimmalla vanhempaa`() {
        val newest = PeriodChoice(keys)
        assertNull(newest.newer)
        assertEquals("w2026-45", newest.older)
        val oldest = newest.select("w2026-44")
        assertNull(oldest.older)
        assertEquals("w2026-45", oldest.newer)
        assertEquals(2, oldest.page)
    }

    @Test
    fun `valinta sailyy rivien muuttuessa`() {
        val choice = PeriodChoice(keys).select("w2026-45").rowsChanged(listOf("w2026-47") + keys)
        assertEquals("w2026-45", choice.selected)
        assertEquals(2, choice.page)
    }

    @Test
    fun `uusin valinta seuraa uutta uusinta`() {
        val choice = PeriodChoice(keys).rowsChanged(listOf("w2026-47") + keys)
        assertEquals("w2026-47", choice.selected)
        assertEquals(0, choice.page)
    }

    @Test
    fun `kadonnut valinta siirtyy uusimpaan`() {
        val choice = PeriodChoice(keys).select("w2026-45").rowsChanged(listOf("w2026-46", "w2026-44"))
        assertEquals("w2026-46", choice.selected)
    }

    @Test
    fun `samat rivit eivat muuta valintaa`() {
        val choice = PeriodChoice(keys).select("w2026-44")
        assertEquals(choice, choice.rowsChanged(keys.toList()))
    }

    @Test
    fun `pyyhkaisy valitsee sivun jakson`() {
        assertEquals("w2026-45", PeriodChoice(keys).settled(1).selected)
        assertEquals("w2026-44", PeriodChoice(keys).settled(2).selected)
    }

    @Test
    fun `olematon sivu tai avain ei muuta valintaa`() {
        val choice = PeriodChoice(keys).select("w2026-45")
        assertEquals(choice, choice.settled(3))
        assertEquals(choice, choice.settled(-1))
        assertEquals(choice, choice.select("w2026-30"))
    }

    @Test
    fun `tuntematon avain tarkoittaa uusinta`() {
        val choice = PeriodChoice(keys, "w2026-30")
        assertEquals("w2026-46", choice.selected)
        assertEquals(0, choice.page)
        assertNull(choice.newer)
    }

    @Test
    fun `ilman riveja ei ole valintaa`() {
        val choice = PeriodChoice(emptyList())
        assertNull(choice.selected)
        assertEquals(0, choice.page)
        assertNull(choice.older)
        assertNull(choice.newer)
        assertEquals("w2026-46", choice.rowsChanged(keys).selected)
    }

    @Test
    fun `muiden jaksojen lista on uusimmasta vanhimpaan ilman valittua`() {
        val rows = keys.map { row(it) }
        assertEquals(listOf("w2026-45", "w2026-44"), otherPeriodRows(rows, PeriodChoice(keys).selected).map { it.key })
        assertEquals(listOf("w2026-46", "w2026-44"), otherPeriodRows(rows, "w2026-45").map { it.key })
    }

    @Test
    fun `listan napautus valitsee jakson ja palauttaa edellisen listaan`() {
        val rows = keys.map { row(it) }
        val choice = PeriodChoice(keys).select("w2026-44")
        assertEquals("w2026-44", choice.selected)
        assertEquals(2, choice.page)
        assertEquals(listOf("w2026-46", "w2026-45"), otherPeriodRows(rows, choice.selected).map { it.key })
    }

    @Test
    fun `viikon alarivilla on paivat ja keskenerainen merkitaan`() {
        val week = row("w2026-46").copy(ongoing = true)
        val parts = subtitleParts(week, WorkPeriod.WEEK, todayEpochDay = 20_403, showOngoing = true)
        assertEquals(periodDayRange("w2026-46"), parts.range)
        assertNull(parts.daysAgo)
        assertEquals(true, parts.ongoing)
    }

    @Test
    fun `paivan alarivi kertoo lahipaivan etaisyyden`() {
        val today = row("d20403").copy(date = 20_403, ongoing = true)
        val parts = subtitleParts(today, WorkPeriod.DAY, todayEpochDay = 20_403, showOngoing = false)
        assertNull(parts.range)
        assertEquals(0, parts.daysAgo)
        assertEquals(false, parts.ongoing)
        val yesterday = row("d20402").copy(date = 20_402)
        assertEquals(1, subtitleParts(yesterday, WorkPeriod.DAY, todayEpochDay = 20_403, showOngoing = true).daysAgo)
        val old = row("d20390").copy(date = 20_390)
        assertNull(subtitleParts(old, WorkPeriod.DAY, todayEpochDay = 20_403, showOngoing = true).daysAgo)
    }

    @Test
    fun `kuukauden alarivilla ei ole valia`() {
        val parts = subtitleParts(row("m2026-11"), WorkPeriod.MONTH, todayEpochDay = 20_403, showOngoing = true)
        assertEquals(SubtitleParts(range = null, daysAgo = null, ongoing = false), parts)
    }

    @Test
    fun `aikavali rivittyy vain viivan kohdalta`() {
        val nbsp = Char(0xA0)
        assertEquals("28${nbsp}Dec${nbsp}2025 – 3${nbsp}Jan${nbsp}2026", boundRange("28 Dec 2025 – 3 Jan 2026"))
        assertEquals("29.12.2025–4.1.2026", boundRange("29.12.2025–4.1.2026"))
    }

    @Test
    fun `listan rivin polttoaineet ilman AdBluea`() {
        val diesel = fuel(FuelKind.DIESEL, 38.0)
        val petrol = fuel(FuelKind.PETROL, 12.0)
        val adBlue = fuel(FuelKind.ADBLUE, 1.9)
        assertEquals(listOf(diesel, petrol), listFuelLines(PeriodFuels(listOf(diesel, petrol, adBlue))))
        assertEquals(emptyList(), listFuelLines(null))
    }

    @Test
    fun `pelkka AdBlue nakyy listan rivilla`() {
        val adBlue = fuel(FuelKind.ADBLUE, 1.9)
        assertEquals(listOf(adBlue), listFuelLines(PeriodFuels(listOf(adBlue))))
    }
}
