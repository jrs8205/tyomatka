package fi.tyomatka.ui.history.forms

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarFormTest {
    @Test
    fun `lomake tallentaa vain käyttäjän antamat tiedot`() {
        // Tankkauksista päätelty polttoaine ei muutu suoraksi tiedoksi.
        val automatic = CarForm.initial(CarEntity("ABC-123", null, null))
        assertNull(automatic.fuel)

        val own = CarForm.initial(CarEntity("ABC-123", FuelKind.ELECTRIC, null))
        assertEquals(FuelKind.ELECTRIC, own.fuel)
        assertNull(own.withFuel(null).fuel)
    }

    @Test
    fun `muokattu auto tallentuu vain muutoksesta`() {
        val original = CarForm.initial(CarEntity("ABC-123", FuelKind.DIESEL, "SEK"))
        assertTrue(original.isUnchanged(original))
        assertFalse(original.withFuel(null).isUnchanged(original))
        assertTrue(original.withFuel(null).withFuel(FuelKind.DIESEL).isUnchanged(original))
        // Tuonnin autolle tuoma valuutta ei avaa Tallenna-nappia, kun lomake on auki.
        val opened = CarForm.initial(CarEntity("ABC-123", FuelKind.DIESEL, null))
        assertTrue(opened.isUnchanged(original))
    }

    @Test
    fun `Autot-lista näyttää tunnetut autot viimeisin ensin ja polttoaineen profiilista tai tankkauksista`() {
        val cars = mapOf(
            "EL-1" to CarEntity("EL-1", FuelKind.ELECTRIC, "NOK"),
            "NEW-1" to CarEntity("NEW-1", null, "SEK"),
        )
        val fillups = listOf(
            FuelFillupEntity(id = 1, epochSec = 100, licensePlate = "ABC-123", odometerKm = 1000, liters = 40.0, totalCost = 0.0, pricePerLiter = 0.0, kind = FuelKind.GAS, fullTank = true, currency = "EUR"),
            FuelFillupEntity(id = 2, epochSec = 200, licensePlate = "ABC-123", odometerKm = 1100, liters = 5.0, totalCost = 0.0, pricePerLiter = 0.0, kind = FuelKind.ADBLUE, fullTank = true, currency = "EUR"),
        )

        val rows = carListRows(cars, knownPlates = listOf("ABC-123", "EL-1", "OLD-9"), fillups = fillups)

        assertEquals(listOf("ABC-123", "EL-1", "OLD-9", "NEW-1"), rows.map { it.car.plate })
        assertEquals(listOf(FuelKind.GAS, FuelKind.ELECTRIC, null, null), rows.map { it.fuel })
        // Automaattisen valinnan ohje: pelkistä tankkauksista, vaikka profiilissa olisi oma polttoaine.
        assertEquals(listOf(FuelKind.GAS, null, null, null), rows.map { it.fuelFromFillups })
        assertEquals("NOK", rows[1].car.currency)
        assertNull(rows[0].car.currency)
    }

    @Test
    fun `uusi auto tarvitsee rekisterinumeron, jota ei ole jo listalla`() {
        val form = CarForm.blank()
        assertFalse(form.canSave(existingPlates = setOf("ABC-123")))
        // Sama auto eri kirjoitusasulla on jo listalla: sitä muokataan listasta.
        assertTrue(form.withPlate("abc123").plateExists(setOf("ABC-123")))
        assertFalse(form.withPlate("abc123").canSave(existingPlates = setOf("ABC-123")))
        val added = form.withPlate("XYZ789").withFuel(FuelKind.ELECTRIC)
        assertTrue(added.canSave(existingPlates = setOf("ABC-123")))
        assertEquals("XYZ-789", added.savedPlate)
        assertEquals(FuelKind.ELECTRIC, added.fuel)
        // Ulkomainen kirjoitusasu ilman väliviivaa on sama auto; pelkkä väliviiva ei ole rekisterinumero.
        assertTrue(form.withPlate("AB12345").plateExists(setOf("AB-12345")))
        assertFalse(form.withPlate("--").canSave(existingPlates = emptySet()))
        // Muokattavan auton tallennus ei riipu listasta.
        assertTrue(CarForm.initial(CarEntity("ABC-123", null, null)).canSave(existingPlates = setOf("ABC-123")))
    }

    @Test
    fun `vain auton, jolla ei ole työjaksoja eikä kirjauksia, voi poistaa`() {
        val cars = mapOf("UUS-1" to CarEntity("UUS-1", FuelKind.DIESEL, null), "ABC-123" to CarEntity("ABC-123", null, null))
        val rows = carListRows(cars, knownPlates = listOf("ABC-123"), fillups = emptyList())
        assertEquals(mapOf("ABC-123" to false, "UUS-1" to true), rows.associate { it.car.plate to it.removable })
    }

    @Test
    fun `Autot-rivillä käynnissä olevan päivän auto on käytössä ja muut vaihdetaan, muuten seuraava ajo tai valinta`() {
        // Työpäivä käynnissä: avoimen jakson auto on käytössä, toinen auto valitaan Vaihda auto -lomakkeelle.
        assertEquals(CarRowChoice.IN_USE, carRowChoice("ABC-123", ongoing = true, openPlate = "ABC-123", nextPlate = "EVO-456"))
        assertEquals(CarRowChoice.SWITCH_CAR, carRowChoice("EVO-456", ongoing = true, openPlate = "ABC-123", nextPlate = "EVO-456"))
        // Avoin jakso ilman rekisterinumeroa: jokaisen auton voi valita vaihtoon.
        assertEquals(CarRowChoice.SWITCH_CAR, carRowChoice("ABC-123", ongoing = true, openPlate = null, nextPlate = "ABC-123"))
        // Työpäivä ei käynnissä: seuraavan ajon auto on merkitty, muut voi valita seuraavaan ajoon.
        assertEquals(CarRowChoice.NEXT, carRowChoice("EVO-456", ongoing = false, openPlate = null, nextPlate = "EVO-456"))
        assertEquals(CarRowChoice.CHOOSE_NEXT, carRowChoice("ABC-123", ongoing = false, openPlate = null, nextPlate = "EVO-456"))
        assertEquals(CarRowChoice.CHOOSE_NEXT, carRowChoice("ABC-123", ongoing = false, openPlate = null, nextPlate = null))
    }

    @Test
    fun `AdBlue ei ole auton polttoaine`() {
        val form = CarForm.initial(CarEntity("ABC-123", null, null))
        assertNull(form.withFuel(FuelKind.ADBLUE).fuel)
    }
}
