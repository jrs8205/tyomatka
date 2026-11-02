package fi.tyomatka.ui.history.forms

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.carFuel
import fi.tyomatka.data.repo.carProfiles
import fi.tyomatka.data.repo.normalizePlate
import fi.tyomatka.data.repo.plateKey
import fi.tyomatka.data.repo.profilePlate

/**
 * Autot-listan rivi: profiili, auton polttoaine (profiili, muuten auton viimeisin polttoainetankkaus), pelkistä
 * tankkauksista päätelty polttoaine (automaattisen valinnan ohje) ja voiko auton poistaa (ei työjaksoja eikä
 * kirjauksia: esimerkiksi kirjoitusvirhe esittelyssä).
 */
data class CarListRow(val car: CarEntity, val fuel: FuelKind?, val fuelFromFillups: FuelKind?, val removable: Boolean = false)

/** Tunnetut autot ([knownPlates]: työjaksot ja kirjaukset) viimeisin käyttö ensin, sitten pelkät profiilit ([carProfiles]). */
fun carListRows(cars: Map<String, CarEntity>, knownPlates: List<String>, fillups: List<FuelFillupEntity>): List<CarListRow> =
    carProfiles(cars.values.toList(), knownPlates).map {
        CarListRow(it, carFuel(it.plate, cars, fillups), carFuel(it.plate, emptyMap(), fillups), removable = it.plate !in knownPlates)
    }

/**
 * Autot-rivin valinta: Valitse-nappi avaa Vaihda auto -lomakkeen ([SWITCH_CAR]) tai merkitsee auton
 * seuraavaan ajoon ([CHOOSE_NEXT]); napin sijaan rivillä on merkintä "Käytössä" ([IN_USE]) tai "Seuraava ajo" ([NEXT]).
 */
enum class CarRowChoice { SWITCH_CAR, IN_USE, CHOOSE_NEXT, NEXT }

/**
 * Työpäivän ollessa käynnissä avoimen jakson auto on käytössä ja muut voi valita autonvaihtoon; muuten seuraavan ajon
 * auto ([nextPlate], [fi.tyomatka.data.repo.defaultPlateWithChoice]) on merkitty ja muut voi valita seuraavaan ajoon.
 */
fun carRowChoice(plate: String, ongoing: Boolean, openPlate: String?, nextPlate: String?): CarRowChoice = when {
    ongoing -> if (plate == openPlate) CarRowChoice.IN_USE else CarRowChoice.SWITCH_CAR
    plate == nextPlate -> CarRowChoice.NEXT
    else -> CarRowChoice.CHOOSE_NEXT
}

/**
 * Autoprofiilin lomake: polttoaine (null = automaattinen: auton viimeisimmästä polttoainetankkauksesta). Lomake tallentaa
 * vain käyttäjän antamat tiedot: päätelty polttoaine ei muutu suoraksi tiedoksi.
 */
data class CarForm(
    val plate: String,
    val fuel: FuelKind?,
    /** Uusi auto: rekisterinumero kirjoitetaan lomakkeelle; muokattavan auton rekisterinumero on kiinteä. */
    val isNew: Boolean = false,
) {
    fun withPlate(value: String) = copy(plate = value)
    fun withFuel(value: FuelKind?) = copy(fuel = value?.takeIf { it.isMainFuel })

    /** Listalla jo oleva auto, jolla on uuden auton rekisterinumero (myös eri kirjoitusasulla): sitä muokataan listasta. */
    fun existingPlate(existingPlates: Set<String>): String? =
        if (!isNew || profilePlate(plate) == null) null else existingPlates.firstOrNull { plateKey(it) == plateKey(plate) }

    fun plateExists(existingPlates: Set<String>): Boolean = existingPlate(existingPlates) != null

    fun canSave(existingPlates: Set<String>): Boolean =
        !isNew || (profilePlate(plate) != null && !plateExists(existingPlates))

    /** Tallennettava rekisterinumero. */
    val savedPlate: String get() = normalizePlate(plate) ?: plate

    /** Muokatun auton Tallenna aukeaa vasta, kun jokin valinta eroaa avatusta. */
    fun isUnchanged(original: CarForm): Boolean = this == original

    companion object {
        fun initial(car: CarEntity) = CarForm(car.plate, car.fuel)

        /** Autot-näkymän Lisää auto: polttoaine automaattinen, kunnes käyttäjä valitsee. */
        fun blank() = CarForm("", null, isNew = true)
    }
}
