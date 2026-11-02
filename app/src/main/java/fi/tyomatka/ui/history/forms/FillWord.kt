package fi.tyomatka.ui.history.forms

import fi.tyomatka.R
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.carFuel
import fi.tyomatka.data.repo.defaultFuelPlate

/**
 * Kirjauksen nimi auton käyttövoiman mukaan: sähköautoa ladataan, kaasuautoa täytetään ja
 * muita tankataan. Ennen kuin käyttövoima tiedetään (profiilista tai ensimmäisestä kirjauksesta), kirjaus on
 * tankkaus.
 */
enum class FillWord {
    FILL,
    CHARGE,
    GAS;

    /** Sanan mukainen teksti: tankkaus, lataus tai täyttö. */
    fun pick(fill: Int, charge: Int, gas: Int): Int = when (this) {
        FILL -> fill
        CHARGE -> charge
        GAS -> gas
    }
}

fun fillWord(kind: FuelKind?): FillWord = when (kind) {
    FuelKind.ELECTRIC -> FillWord.CHARGE
    FuelKind.GAS -> FillWord.GAS
    else -> FillWord.FILL
}

/**
 * Tänään-kortin napin ja tyhjän listan sana: oletusauton ([defaultFuelPlate]: avoimen työjakson auto, muuten oletusauto
 * tai viimeisimmän kirjauksen auto) käyttövoima profiilista tai sen kirjauksista ([carFuel]). Sana vaihtuu, kun
 * käyttövoima saadaan ensimmäisen kerran tietoon, ja seuraa autoa, kun avoin työjakso vaihtuu toiseen autoon tai sen
 * rekisterinumero korjataan.
 */
fun defaultFillWord(
    openSegment: WorkSegmentEntity?,
    defaultPlate: String?,
    fillups: List<FuelFillupEntity>,
    cars: Map<String, CarEntity>,
): FillWord = fillWord(defaultFuelPlate(openSegment, defaultPlate, fillups)?.let { carFuel(it, cars, fillups) })

/** Jaksokortin autorivien selite rivien lajien mukaan: "tankkausväleiltä", "lataus- ja täyttöväleiltä"… */
fun carPeriodNote(kinds: Collection<FuelKind>): Int {
    val words = kinds.map(::fillWord).toSet()
    val fill = FillWord.FILL in words
    val charge = FillWord.CHARGE in words
    val gas = FillWord.GAS in words
    return when {
        fill && charge && gas -> R.string.fuel_car_period_note_all
        charge && gas -> R.string.fuel_car_period_note_charge_gas
        fill && gas -> R.string.fuel_car_period_note_fill_gas
        fill && charge -> R.string.fuel_car_period_note_mixed
        gas -> R.string.fuel_car_period_note_gas
        charge -> R.string.fuel_car_period_note_charge
        else -> R.string.fuel_car_period_note
    }
}

/**
 * Tuonnin ilmoituksen kirjausten sana varmuuskopion kirjausten (tuodut ja ohitetut) lajien mukaan: "tankkauksia",
 * "latauksia", "täyttöjä" tai yhdistelmä samassa järjestyksessä kuin jaksokorttien selitteissä. Ilman kirjauksia
 * sana on tankkauksia.
 */
fun importKindsWord(kinds: Collection<FuelKind>): Int {
    val words = kinds.map(::fillWord).toSet()
    val fill = FillWord.FILL in words
    val charge = FillWord.CHARGE in words
    val gas = FillWord.GAS in words
    return when {
        fill && charge && gas -> R.string.work_import_kinds_all
        charge && gas -> R.string.work_import_kinds_charge_gas
        fill && gas -> R.string.work_import_kinds_fill_gas
        fill && charge -> R.string.work_import_kinds_fill_charge
        gas -> R.string.work_import_kinds_gas
        charge -> R.string.work_import_kinds_charge
        else -> R.string.work_import_kinds_fill
    }
}
