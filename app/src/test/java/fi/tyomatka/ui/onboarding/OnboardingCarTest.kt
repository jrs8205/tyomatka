package fi.tyomatka.ui.onboarding

import fi.tyomatka.data.db.FuelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingCarTest {
    @Test
    fun `tyhjä rekisterinumero ohittaa vaiheen, vaikka käyttövoima olisi valittu`() {
        val car = OnboardingCar().withFuel(FuelKind.ELECTRIC)
        assertTrue(car.skips)
        assertNull(car.plateToSave)
        assertTrue(OnboardingCar(plate = "   ").skips)
        assertTrue(OnboardingCar(plate = "-").skips)
    }

    @Test
    fun `rekisterinumero tallentuu normalisoituna ja käyttövoima valinnaisena`() {
        val car = OnboardingCar().withPlate("abc 123").withFuel(FuelKind.GAS)
        assertFalse(car.skips)
        assertEquals("ABC-123", car.plateToSave)
        assertEquals(FuelKind.GAS, car.fuel)
        assertNull(car.withFuel(null).fuel)
        // AdBlue ei ole auton käyttövoima.
        assertNull(OnboardingCar().withFuel(FuelKind.ADBLUE).fuel)
    }
}
