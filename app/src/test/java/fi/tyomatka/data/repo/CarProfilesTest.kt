package fi.tyomatka.data.repo

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CarProfilesTest {
    @Test
    fun `rekisterinumeroehdotuksissa ovat käytetyt ensin ja sitten pelkät profiilit`() {
        // Esittelyn tai Autot-näkymän auto näkyy lomakkeiden napeissa jo ennen ensimmäistä kirjausta.
        val cars = listOf(CarEntity("UUS-1", FuelKind.DIESEL, null), CarEntity("ABC-123", null, "SEK"))
        assertEquals(listOf("ABC-123", "XYZ-789", "UUS-1"), suggestionPlates(listOf("ABC-123", "XYZ-789"), cars))
        assertEquals(listOf("UUS-1", "ABC-123"), suggestionPlates(emptyList(), cars))
    }

    private fun fillup(id: Long, plate: String, epochSec: Long, kind: FuelKind) = FuelFillupEntity(
        id = id, epochSec = epochSec, licensePlate = plate, odometerKm = 1000 + id, liters = 10.0,
        totalCost = 0.0, pricePerLiter = 0.0, kind = kind, currency = "EUR",
    )

    @Test
    fun `auton laji tulee profiilista, sitten viimeisimmästä polttoainetankkauksesta, muuten tuntematon`() {
        val fillups = listOf(
            fillup(1, "ABC-123", 100, FuelKind.PETROL),
            fillup(2, "ABC-123", 200, FuelKind.ADBLUE),
            fillup(3, "XYZ-789", 300, FuelKind.GAS),
        )
        val cars = mapOf("XYZ-789" to CarEntity("XYZ-789", FuelKind.ELECTRIC, null))

        assertEquals(FuelKind.PETROL, carFuel("ABC-123", cars, fillups))
        assertEquals(FuelKind.ELECTRIC, carFuel("XYZ-789", cars, fillups))
        assertNull(carFuel("UUS-1", cars, fillups))
    }

    @Test
    fun `tankkauksen lajin korjaus päivittää päätellyn polttoaineen mutta ei suoraan annettua`() {
        // Väärä oletus (diesel) korjataan bensiiniksi: oletus seuraa korjausta, koska tankkaus ei kirjoita profiiliin.
        val corrected = listOf(fillup(1, "ABC-123", 100, FuelKind.PETROL))
        assertEquals(FuelKind.PETROL, carFuel("ABC-123", mapOf("ABC-123" to CarEntity("ABC-123", null, "SEK")), corrected))
        assertEquals(FuelKind.ELECTRIC, carFuel("ABC-123", mapOf("ABC-123" to CarEntity("ABC-123", FuelKind.ELECTRIC, null)), corrected))
    }

    @Test
    fun `autolista yhdistää profiilit ja tunnetut rekisterinumerot`() {
        val cars = listOf(CarEntity("XYZ-789", FuelKind.ELECTRIC, "NOK"))
        val list = carProfiles(cars, knownPlates = listOf("ABC-123", "XYZ-789"))
        assertEquals(listOf("ABC-123", "XYZ-789"), list.map { it.plate })
        assertEquals(listOf(null, FuelKind.ELECTRIC), list.map { it.fuel })
    }
}
