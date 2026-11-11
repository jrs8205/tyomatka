package fi.tyomatka.ui.history

import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.PeriodFuel
import fi.tyomatka.data.repo.PeriodFuels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Jakson polttoaineruudut: jokainen laji omana parinaan, AdBlue erikseen pieniin lukuihin. */
class PeriodTilesTest {

    private fun fuel(kind: FuelKind, liters: Double) =
        PeriodFuel(kind, liters, mapOf("EUR" to liters * 1.8), 1.8, 9.2, 0.17, currency = "EUR")

    @Test
    fun `ilman tankkauksia ei ole ruutuja`() {
        val tiles = periodFuelTiles(null)
        assertEquals(emptyList(), tiles.main)
        assertNull(tiles.adBlue)
    }

    @Test
    fun `diesel ja AdBlue erotetaan`() {
        val diesel = fuel(FuelKind.DIESEL, 38.0)
        val adBlue = fuel(FuelKind.ADBLUE, 1.9)
        val tiles = periodFuelTiles(PeriodFuels(listOf(diesel, adBlue)))
        assertEquals(listOf(diesel), tiles.main)
        assertEquals(adBlue, tiles.adBlue)
    }

    @Test
    fun `useampi laji sailyttaa jarjestyksen`() {
        val diesel = fuel(FuelKind.DIESEL, 38.0)
        val petrol = fuel(FuelKind.PETROL, 12.0)
        val adBlue = fuel(FuelKind.ADBLUE, 1.9)
        val tiles = periodFuelTiles(PeriodFuels(listOf(diesel, petrol, adBlue)))
        assertEquals(listOf(diesel, petrol), tiles.main)
        assertEquals(adBlue, tiles.adBlue)
    }

    @Test
    fun `pelkka diesel ei tee AdBlue-ruutua`() {
        val diesel = fuel(FuelKind.DIESEL, 38.0)
        val tiles = periodFuelTiles(PeriodFuels(listOf(diesel)))
        assertEquals(listOf(diesel), tiles.main)
        assertNull(tiles.adBlue)
    }

    @Test
    fun `rivi vaihtuu vain tavallisen valilyonnin kohdalta`() {
        val nbsp = Char(0xA0)
        assertEquals(listOf("Förbrukning", "/", "arbetskilometer"), unbreakableParts("Förbrukning / arbetskilometer"))
        assertEquals(listOf("1${nbsp}234${nbsp}km"), unbreakableParts("1${nbsp}234${nbsp}km"))
        assertEquals(listOf("Diesel$nbsp·", "tankattu"), unbreakableParts("Diesel$nbsp· tankattu"))
        assertEquals(emptyList(), unbreakableParts(""))
    }

    @Test
    fun `pelkka AdBlue ei tee paaruutua`() {
        val adBlue = fuel(FuelKind.ADBLUE, 1.9)
        val tiles = periodFuelTiles(PeriodFuels(listOf(adBlue)))
        assertEquals(emptyList(), tiles.main)
        assertEquals(adBlue, tiles.adBlue)
    }
}
