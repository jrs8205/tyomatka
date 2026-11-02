package fi.tyomatka.ui

import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelUnit
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatTest {

    @Test
    fun `kellonaika jasentyy eri muodoissa`() {
        assertEquals(9 * 60 + 45, parseClockMinutes("9.45"))
        assertEquals(9 * 60 + 45, parseClockMinutes("09.45"))
        assertEquals(9 * 60 + 45, parseClockMinutes("9:45"))
        assertEquals(9 * 60 + 45, parseClockMinutes("945"))
        assertEquals(9 * 60 + 45, parseClockMinutes("0945"))
        assertEquals(0, parseClockMinutes("0.00"))
        assertEquals(23 * 60 + 59, parseClockMinutes("23.59"))
        assertEquals(17 * 60 + 18, parseClockMinutes(" 17.18 "))
        assertEquals(9 * 60 + 45, parseClockMinutes("9,45"))
    }

    @Test
    fun `kelvoton kellonaika hylataan`() {
        assertNull(parseClockMinutes(""))
        assertNull(parseClockMinutes("9"))
        assertNull(parseClockMinutes("24.00"))
        assertNull(parseClockMinutes("12.60"))
        assertNull(parseClockMinutes("9.5"))
        assertNull(parseClockMinutes("9.455"))
        assertNull(parseClockMinutes("abc"))
        assertNull(parseClockMinutes("12.3a"))
    }

    @Test
    fun `nelinumeroinen ilman väliä ja viidestä numerosta alkaen tuhaterotin`() {
        // alle 10 000 ilman väliä, siitä alkaen sitova välilyönti (suomen yleiskielen tapa).
        assertEquals("999", formatWhole(999))
        assertEquals("1100", formatWhole(1100))
        assertEquals("9999", formatWhole(9999))
        assertEquals("10\u00A0000", formatWhole(10_000))
        assertEquals("475\u00A0279", formatWhole(475_279))
        assertEquals("-12\u00A0345", formatWhole(-12_345))
    }

    @Test
    fun `polttoainemuotoilut suomalaisella pilkulla`() {
        assertEquals("54,3 l", formatAmount(54.32, FuelKind.DIESEL))
        assertEquals("98,45 €", formatMoney(98.449, "EUR"))
        assertEquals("–", formatMoney(0.0, "EUR"))
        assertEquals("1,812 €/l", formatUnitPrice(1.8123, "EUR", FuelUnit.LITER))
        assertEquals("–", formatUnitPrice(0.0, "EUR", FuelUnit.LITER))
        assertEquals("7,8 l/100 km", formatConsumption(7.84, FuelKind.DIESEL))
        assertEquals("0,15 €/km", formatCostPerKm(0.149, "EUR"))
        assertEquals("–", formatCostPerKm(null, "EUR"))
        // Usean valuutan jaksolla ei ole valuuttaa eikä hintaa kilometriltä.
        assertEquals("–", formatCostPerKm(0.15, null))
    }

    @Test
    fun `paivavali Kielitoimiston ohjeen mukaan`() {
        fun range(from: LocalDate, to: LocalDate) = formatDayRange(from.toEpochDay(), to.toEpochDay())
        assertEquals("1.–18.10.2026", range(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18)))
        assertEquals("1.9.–18.10.2026", range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 18)))
        assertEquals("28.12.2025–3.1.2026", range(LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 3)))
        assertEquals("16.10.2026", range(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)))
    }

    @Test
    fun `lyhyt paivamaara`() {
        assertEquals("Pe 25.9.2026", formatShortDate(LocalDate.of(2026, 9, 25).toEpochDay()))
        assertEquals("La 17.10.2026", formatShortDate(LocalDate.of(2026, 10, 17).toEpochDay()))
        // Lauseen sisällä pienellä: "(pe 25.9.2026 lopetus)".
        assertEquals("pe 25.9.2026", formatShortDate(LocalDate.of(2026, 9, 25).toEpochDay(), capitalize = false))
    }

    @Test
    fun `numbers dates and 24 hour clock follow an English system language`() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("98.45 €", formatMoney(98.45, "EUR"))
            assertEquals("1.812 €/l", formatUnitPrice(1.812, "EUR", FuelUnit.LITER))
            assertEquals("Fri 25 Sep 2026", formatShortDate(LocalDate.of(2026, 9, 25).toEpochDay()))
            assertEquals("23:05", formatMinutesOfDay(23 * 60 + 5))
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    @Test
    fun `rahamäärä, litrahinta, kulutus ja osuus valuutan ja lajin mukaan`() {
        assertEquals("45,20 €", formatMoney(45.2, "EUR"))
        assertEquals("512,00 SEK", formatMoney(512.0, "SEK"))
        assertEquals("–", formatMoney(0.0, "EUR"))
        assertEquals("45,20 € + 512,00 SEK", formatMoneyByCurrency(linkedMapOf("EUR" to 45.2, "SEK" to 512.0)))
        assertEquals("–", formatMoneyByCurrency(emptyMap()))
        assertEquals("1,859 €/l", formatUnitPrice(1.859, "EUR", FuelUnit.LITER))
        assertEquals("18,900 SEK/l", formatUnitPrice(18.9, "SEK", FuelUnit.LITER))
        assertEquals("0,250 €/kWh", formatUnitPrice(0.25, "EUR", FuelUnit.KILOWATT_HOUR))
        assertEquals("1,990 €/kg", formatUnitPrice(1.99, "EUR", FuelUnit.KILOGRAM))
        assertEquals("9,1 l/100 km", formatConsumption(9.1, FuelKind.DIESEL))
        assertEquals("1,6 l/1000 km", formatConsumption(1.6, FuelKind.ADBLUE))
        assertEquals("18,5 kWh/100 km", formatConsumption(18.5, FuelKind.ELECTRIC))
        assertEquals("4,2 kg/100 km", formatConsumption(4.2, FuelKind.GAS))
        assertEquals("35,0 kWh", formatAmount(35.0, FuelKind.ELECTRIC))
        assertEquals("45,2 l", formatAmount(45.2, FuelKind.PETROL))
        assertEquals("0,16 €/km", formatCostPerKm(0.16, "EUR"))
        assertEquals("–", formatCostPerKm(null, "SEK"))
        assertEquals("30,00 USD", formatMoney(30.0, "USD"))
        assertEquals("3,1 %", formatPercent(3.14))
    }
}
