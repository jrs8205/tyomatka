package fi.tyomatka.ui.history.forms

import fi.tyomatka.R
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.WorkSegmentEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class FillWordTest {
    private val electricCar = CarEntity("ABC-123", FuelKind.ELECTRIC, null)
    private val gasCar = CarEntity("XYZ-789", FuelKind.GAS, null)

    private fun fillup(plate: String, kind: FuelKind, at: Long = 1_000) = FuelFillupEntity(
        id = at, epochSec = at, licensePlate = plate, odometerKm = 1000, liters = 40.0, totalCost = 80.0, pricePerLiter = 2.0,
        currency = "EUR", kind = kind,
    )

    private fun openSegment(plate: String?) = WorkSegmentEntity(id = 1, routeDayId = 1, startEpochSec = 10, licensePlate = plate)

    @Test
    fun `Tänään-kortin sana on tankkaus, kunnes käyttövoima tiedetään`() {
        // Ei autoa, ei kirjauksia, ei työjaksoa.
        assertEquals(FillWord.FILL, defaultFillWord(null, null, emptyList(), emptyMap()))
        // Auto tiedetään, mutta käyttövoimaa ei.
        val unknownCar = CarEntity("ABC-123", null, null)
        assertEquals(FillWord.FILL, defaultFillWord(null, "ABC-123", emptyList(), mapOf("ABC-123" to unknownCar)))
        assertEquals(FillWord.FILL, defaultFillWord(openSegment("ABC-123"), "ABC-123", emptyList(), emptyMap()))
        // Avoin työjakso ilman rekisterinumeroa.
        assertEquals(FillWord.FILL, defaultFillWord(openSegment(null), "ABC-123", emptyList(), mapOf("ABC-123" to electricCar)))
    }

    @Test
    fun `ensimmäinen tieto käyttövoimasta vaihtaa sanan`() {
        // Autoprofiilista (esittely tai Autot-näkymä), ennen yhtäkään kirjausta.
        assertEquals(FillWord.CHARGE, defaultFillWord(null, "ABC-123", emptyList(), mapOf("ABC-123" to electricCar)))
        // Ensimmäisestä kirjauksesta ilman profiilia; myös ilman työjaksoja, jolloin oletusauto tulee kirjauksesta.
        val charge = fillup("ABC-123", FuelKind.ELECTRIC)
        assertEquals(FillWord.CHARGE, defaultFillWord(null, "ABC-123", listOf(charge), emptyMap()))
        assertEquals(FillWord.CHARGE, defaultFillWord(null, null, listOf(charge), emptyMap()))
        assertEquals(FillWord.GAS, defaultFillWord(null, null, listOf(fillup("XYZ-789", FuelKind.GAS)), emptyMap()))
        // AdBlue ei kerro käyttövoimaa: yksin se jättää sanan tankkaukseksi, eikä se viimeisimpänä kirjauksena syrjäytä
        // sähköauton latausta.
        assertEquals(FillWord.FILL, defaultFillWord(null, null, listOf(fillup("ABC-123", FuelKind.ADBLUE)), emptyMap()))
        val adBlueLast = listOf(fillup("ABC-123", FuelKind.ELECTRIC, at = 1_000), fillup("ABC-123", FuelKind.ADBLUE, at = 2_000))
        assertEquals(FillWord.CHARGE, defaultFillWord(null, null, adBlueLast, emptyMap()))
    }

    @Test
    fun `autonvaihto ja auton korjaus vaihtavat sanan avoimen työjakson auton mukaan`() {
        val cars = mapOf("ABC-123" to electricCar, "XYZ-789" to gasCar)
        // Päivä alkoi sähköautolla; kesken päivän vaihdettiin kaasuautoon, ja oletusauto on vielä edellinen.
        assertEquals(FillWord.CHARGE, defaultFillWord(openSegment("ABC-123"), "ABC-123", emptyList(), cars))
        assertEquals(FillWord.GAS, defaultFillWord(openSegment("XYZ-789"), "ABC-123", emptyList(), cars))
        // Avoimen jakson rekisterinumero korjattiin toiseksi autoksi.
        val fixed = openSegment("ABC-123").copy(licensePlate = "XYZ-789")
        assertEquals(FillWord.GAS, defaultFillWord(fixed, "XYZ-789", emptyList(), cars))
        // Toisen auton käyttövoima tulee sen kirjauksista, kun profiilia ei ole.
        val fillups = listOf(fillup("ABC-123", FuelKind.ELECTRIC, at = 2_000), fillup("XYZ-789", FuelKind.DIESEL, at = 1_000))
        assertEquals(FillWord.FILL, defaultFillWord(openSegment("XYZ-789"), "ABC-123", fillups, emptyMap()))
    }

    @Test
    fun `tuonnin ilmoituksen sana kertoo varmuuskopion kirjausten lajit`() {
        assertEquals(R.string.work_import_kinds_fill, importKindsWord(emptySet()))
        assertEquals(R.string.work_import_kinds_fill, importKindsWord(setOf(FuelKind.DIESEL, FuelKind.PETROL, FuelKind.ADBLUE)))
        assertEquals(R.string.work_import_kinds_charge, importKindsWord(setOf(FuelKind.ELECTRIC)))
        assertEquals(R.string.work_import_kinds_gas, importKindsWord(setOf(FuelKind.GAS)))
        assertEquals(R.string.work_import_kinds_fill_charge, importKindsWord(setOf(FuelKind.ELECTRIC, FuelKind.DIESEL)))
        assertEquals(R.string.work_import_kinds_fill_gas, importKindsWord(setOf(FuelKind.ADBLUE, FuelKind.GAS)))
        assertEquals(R.string.work_import_kinds_charge_gas, importKindsWord(setOf(FuelKind.GAS, FuelKind.ELECTRIC)))
        assertEquals(R.string.work_import_kinds_all, importKindsWord(setOf(FuelKind.GAS, FuelKind.ELECTRIC, FuelKind.PETROL)))
    }

    @Test
    fun `sähköauto ladataan, kaasuauto täytetään ja muut tai tuntematon tankataan`() {
        // ennen kuin käyttövoima tiedetään, kirjaus on tankkaus.
        assertEquals(FillWord.CHARGE, fillWord(FuelKind.ELECTRIC))
        assertEquals(FillWord.GAS, fillWord(FuelKind.GAS))
        listOf(FuelKind.DIESEL, FuelKind.PETROL, FuelKind.ADBLUE, null).forEach { assertEquals(FillWord.FILL, fillWord(it)) }
        assertEquals(R.string.fuel_add_gas, FillWord.GAS.pick(R.string.fuel_add, R.string.fuel_add_charge, R.string.fuel_add_gas))
        assertEquals(R.string.fuel_add, FillWord.FILL.pick(R.string.fuel_add, R.string.fuel_add_charge, R.string.fuel_add_gas))
    }

    @Test
    fun `autorivien selite kertoo jakson kirjausten lajit`() {
        assertEquals(R.string.fuel_car_period_note, carPeriodNote(listOf(FuelKind.DIESEL, FuelKind.ADBLUE)))
        assertEquals(R.string.fuel_car_period_note_charge, carPeriodNote(listOf(FuelKind.ELECTRIC)))
        assertEquals(R.string.fuel_car_period_note_gas, carPeriodNote(listOf(FuelKind.GAS)))
        assertEquals(R.string.fuel_car_period_note_mixed, carPeriodNote(listOf(FuelKind.PETROL, FuelKind.ELECTRIC)))
        assertEquals(R.string.fuel_car_period_note_fill_gas, carPeriodNote(listOf(FuelKind.DIESEL, FuelKind.GAS)))
        assertEquals(R.string.fuel_car_period_note_charge_gas, carPeriodNote(listOf(FuelKind.GAS, FuelKind.ELECTRIC)))
        assertEquals(R.string.fuel_car_period_note_all, carPeriodNote(listOf(FuelKind.DIESEL, FuelKind.ELECTRIC, FuelKind.GAS)))
    }
}
