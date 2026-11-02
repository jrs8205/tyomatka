package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.FuelType
import fi.tyomatka.core.poi.ReportField
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportFormTest {
    private val empty = ReportForm()

    @Test
    fun `valitut polttoaineet lajeina`() {
        assertEquals(listOf(ReportField.FUELS to "diesel;hvo"), empty.copy(fuels = setOf(FuelType.HVO, FuelType.DIESEL)).pairs())
    }

    @Test
    fun `ei yhtään polttoainetta on none, valitsematon ei ilmoita mitään`() {
        assertEquals(listOf(ReportField.FUELS to "none"), empty.copy(fuels = emptySet()).pairs())
        assertEquals(emptyList(), empty.pairs())
    }

    @Test
    fun `nykyisen valikoiman voi vahvistaa sellaisenaan, tuntematonta ei lähetetä`() {
        assertEquals(ReportField.FUELS to "diesel;petrol95", empty.copy(fuels = setOf(FuelType.PETROL95, FuelType.DIESEL)).fuelsReport())
        assertEquals(ReportField.FUELS to "none", empty.copy(fuels = emptySet()).fuelsReport())
        assertEquals(null, empty.fuelsReport())
    }

    @Test
    fun `korkeus pilkulla muuttuu pisteeksi ja kelvoton jää pois`() {
        assertEquals(listOf(ReportField.MAX_HEIGHT to "4.15"), empty.copy(maxHeight = "4,15").pairs())
        assertEquals(emptyList(), empty.copy(maxHeight = "abc").pairs())
        assertEquals(listOf(ReportField.MAX_WEIGHT to "40"), empty.copy(maxWeight = " 40 ").pairs())
    }

    @Test
    fun `aukiolo kentittäin`() {
        val hours = HoursForm(DayHours.Range("06:00", "18:00"), DayHours.Closed, DayHours.Closed)
        assertEquals(listOf(ReportField.HOURS_RESTAURANT to "Mo-Fr 06:00-18:00"), empty.copy(hours = mapOf(ReportField.HOURS_RESTAURANT to hours)).pairs())
        val closed = HoursForm(DayHours.Closed, DayHours.Closed, DayHours.Closed)
        assertEquals(emptyList(), empty.copy(hours = mapOf(ReportField.HOURS_SHOP to closed)).pairs())
    }
}
