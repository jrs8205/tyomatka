package fi.tyomatka.ui.onboarding

import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.profilePlate

/**
 * Esittelyn autosivun tila (autoprofiili heti alussa): rekisterinumero ja valinnainen käyttövoima.
 * Tyhjä rekisterinumero ohittaa vaiheen, eikä pelkkä käyttövoima tallennu.
 */
data class OnboardingCar(val plate: String = "", val fuel: FuelKind? = null) {
    /** Tallennettava rekisterinumero yhtenäisessä muodossa; null = vaihe ohitetaan (myös pelkkä "-"). */
    val plateToSave: String? get() = profilePlate(plate)

    val skips: Boolean get() = plateToSave == null

    fun withPlate(value: String) = copy(plate = value)

    fun withFuel(value: FuelKind?) = copy(fuel = value?.takeIf { it.isMainFuel })
}
