package fi.tyomatka.ui.history.forms

import fi.tyomatka.data.NOTE_MAX_LENGTH
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.normalizePlate

/**
 * Polttoainetta kysytään autolta, jonka polttoainetta ei tiedetä (ei profiilissa eikä tankkauksista); valinta on
 * vapaaehtoinen, ja seuraava polttoainetankkaus kirjaa sen muuten.
 */
private fun fuelUnknown(plate: String, knownFuelPlates: Set<String>): Boolean =
    normalizePlate(plate)?.let { it !in knownFuelPlates } ?: false

/** Lukema on yhä esitäytetty (tai tyhjä ilman esitäyttöä): käyttäjä ei ole kirjoittanut omaa lukemaa. */
private fun untouched(km: String, prefillKm: Long?): Boolean = km == (prefillKm?.toString() ?: "")

/**
 * Esitäytetty lukema seuraa autoa: koskematon esitäyttö vaihtuu uuden auton viimeisimmäksi lukemaksi tai tyhjenee,
 * jos sitä ei tunneta; itse kirjoitettu lukema säilyy. Palauttaa uuden lukeman ja esitäytön tai null, kun ne säilyvät.
 */
private fun prefillFor(plate: String, km: String, prefillKm: Long?, latestReadings: Map<String, Long>): Pair<String, Long?>? {
    if (!untouched(km, prefillKm)) return null
    val reading = normalizePlate(plate)?.let { latestReadings[it] }
    return (reading?.toString() ?: "") to reading
}

/**
 * Aloita/Jatka työt: lukema valinnainen; esitäytön ylitys kerrotaan, jotta alkupään ajot eivät jää pois.
 * Valinnainen polttoaine autolle, jonka polttoainetta ei tiedetä. [prefillKm] on sen auton lukema, josta [km] on
 * esitäytetty; auton vaihto päivittää molemmat ([withPlate]).
 */
data class WorkStartForm(
    val km: String,
    val plate: String,
    val prefillKm: Long?,
    val fuel: FuelKind? = null,
    /** Päivän lisätiedot sellaisenaan kentästä (enintään [NOTE_MAX_LENGTH]); tallennus normalisoi. */
    val note: String = "",
) {
    fun withNote(value: String) = copy(note = value.take(NOTE_MAX_LENGTH))
    val kmValue: Long? get() = km.toLongOrNull()
    val overPrefillBy: Long?
        get() {
            val value = kmValue ?: return null
            val prefill = prefillKm ?: return null
            return (value - prefill).takeIf { it > 0 }
        }
    val canSave: Boolean get() = km.isBlank() || kmValue != null

    fun withKm(value: String) = copy(km = value)

    /** [latestReadings]: rekisterinumero → auton viimeisin lukema; koskematon esitäyttö seuraa autoa ([prefillFor]). */
    fun withPlate(value: String, latestReadings: Map<String, Long>): WorkStartForm {
        val (newKm, newPrefill) = prefillFor(value, km, prefillKm, latestReadings) ?: return copy(plate = value)
        return copy(plate = value, km = newKm, prefillKm = newPrefill)
    }

    fun usePrefill() = copy(km = prefillKm?.toString() ?: km)
    fun withFuel(value: FuelKind?) = copy(fuel = value?.takeIf { it.isMainFuel })

    fun asksFuel(knownFuelPlates: Set<String>): Boolean = fuelUnknown(plate, knownFuelPlates)

    /** Valinta vain kysytylle autolle: rekkarin vaihto tunnettuun autoon ei siirrä sitä. */
    fun fuelToSave(knownFuelPlates: Set<String>): FuelKind? = fuel?.takeIf { asksFuel(knownFuelPlates) }
}

/** Lopeta työt: lukema valinnainen, mutta ei alle aloituslukeman. */
data class WorkEndForm(val km: String, val minKm: Long?, val note: String = "") {
    fun withNote(value: String) = copy(note = value.take(NOTE_MAX_LENGTH))
    val kmValue: Long? get() = km.toLongOrNull()
    val tooSmall: Boolean
        get() {
            val value = kmValue ?: return false
            val min = minKm ?: return false
            return value < min
        }
    val canSave: Boolean get() = !tooSmall && (km.isBlank() || kmValue != null)

    fun withKm(value: String) = copy(km = value)
}

/**
 * Vaihda auto: vanhan auton lopetuslukema ja uuden aloituslukema, molemmat valinnaisia; uudelle autolle polttoaine.
 * [prefillStartKm] on sen auton lukema, josta [startKm] on esitäytetty; uuden auton valinta päivittää molemmat
 * ([withNewPlate]).
 */
data class SwitchCarForm(
    val endKm: String,
    val startKm: String,
    val newPlate: String,
    val minEndKm: Long?,
    val fuel: FuelKind? = null,
    val prefillStartKm: Long? = null,
) {
    val endValue: Long? get() = endKm.toLongOrNull()
    val startValue: Long? get() = startKm.toLongOrNull()
    val tooSmall: Boolean
        get() {
            val value = endValue ?: return false
            val min = minEndKm ?: return false
            return value < min
        }
    val canSave: Boolean
        get() = !tooSmall && (endKm.isBlank() || endValue != null) && (startKm.isBlank() || startValue != null)

    fun withEndKm(value: String) = copy(endKm = value)
    fun withStartKm(value: String) = copy(startKm = value)
    /** [latestReadings]: rekisterinumero → auton viimeisin lukema; koskematon esitäyttö seuraa autoa ([prefillFor]). */
    fun withNewPlate(value: String, latestReadings: Map<String, Long>): SwitchCarForm {
        val (newKm, newPrefill) = prefillFor(value, startKm, prefillStartKm, latestReadings) ?: return copy(newPlate = value)
        return copy(newPlate = value, startKm = newKm, prefillStartKm = newPrefill)
    }
    fun withFuel(value: FuelKind?) = copy(fuel = value?.takeIf { it.isMainFuel })

    fun asksFuel(knownFuelPlates: Set<String>): Boolean = fuelUnknown(newPlate, knownFuelPlates)

    fun fuelToSave(knownFuelPlates: Set<String>): FuelKind? = fuel?.takeIf { asksFuel(knownFuelPlates) }
}
