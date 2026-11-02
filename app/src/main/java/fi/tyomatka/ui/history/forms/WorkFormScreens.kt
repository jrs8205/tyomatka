package fi.tyomatka.ui.history.forms

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fi.tyomatka.R
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.form.FormScreen
import fi.tyomatka.ui.form.FormSection
import fi.tyomatka.ui.form.NoteField
import fi.tyomatka.ui.form.HelperLine
import fi.tyomatka.ui.form.HelperTone
import fi.tyomatka.ui.form.PlateChips
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatWhole
import fi.tyomatka.ui.history.OdometerField

/**
 * Aloita tai jatka töitä: auto chippeinä, lukema esitäytettynä auton viimeisimmästä lukemasta. [latestReadings]:
 * rekisterinumero → auton viimeisin lukema, jotta auton vaihto chipistä vaihtaa koskemattoman esitäytön
 * ([WorkStartForm.withPlate]).
 */
@Composable
fun WorkStartFormScreen(
    title: String,
    prefillKm: Long?,
    prefillPlate: String?,
    knownPlates: List<String>,
    /** Autot, joiden polttoaine tiedetään (profiilista tai tankkauksista); muilta kysytään vapaaehtoisesti. */
    knownFuelPlates: Set<String>,
    latestReadings: Map<String, Long>,
    onStart: (km: Long?, plate: String?, fuel: FuelKind?, note: String) -> Unit,
    onDismiss: () -> Unit,
    /** Päivän nykyiset lisätiedot (Jatka työt -tilanne); yksi teksti päivää kohti. */
    prefillNote: String = "",
) {
    // Avaimellinen remember: heti käynnistyksen jälkeen esitäyttö voi valmistua vasta lomakkeen avauduttua.
    var form by remember(prefillKm, prefillPlate, prefillNote) {
        mutableStateOf(WorkStartForm(km = prefillKm?.toString() ?: "", plate = prefillPlate ?: "", prefillKm = prefillKm, note = prefillNote))
    }
    FormScreen(
        title = title,
        onClose = onDismiss,
        primaryLabel = title,
        primaryEnabled = form.canSave,
        onPrimary = { onStart(form.kmValue, form.plate, form.fuelToSave(knownFuelPlates), form.note) },
    ) {
        HelperLine(stringResource(R.string.work_time_auto_note))
        FormSection(stringResource(R.string.work_section_car)) {
            PlateChips(form.plate, { form = form.withPlate(it, latestReadings) }, knownPlates, stringResource(R.string.work_plate))
            if (form.asksFuel(knownFuelPlates)) OptionalFuelChips(form.fuel) { form = form.withFuel(it) }
        }
        FormSection(stringResource(R.string.work_section_odometer)) {
            OdometerField(
                form.km,
                { form = form.withKm(it) },
                stringResource(R.string.work_odometer_optional),
                modifier = Modifier.fillMaxWidth(),
            )
            // Lomakkeen oma esitäyttö: auton vaihto on voinut vaihtaa sen tai tyhjentää kentän.
            val formPrefill = form.prefillKm
            if (formPrefill != null) HelperLine(stringResource(R.string.work_prefill_note))
            val over = form.overPrefillBy
            if (over != null && formPrefill != null) {
                HelperLine(stringResource(R.string.work_start_diff_note, formatWhole(over), formatWhole(formPrefill)), HelperTone.ACCENT)
                TextButton(onClick = Tap { form = form.usePrefill() }) { Text(stringResource(R.string.work_use_prev_reading)) }
            }
        }
        FormSection(stringResource(R.string.note_label)) {
            NoteField(form.note, { form = form.withNote(it) }, stringResource(R.string.note_hint_work))
        }
    }
}

/** Lopeta työt: yhteenveto ja lopetuslukema. */
@Composable
fun WorkEndFormScreen(
    segments: List<WorkSegmentEntity>,
    minKm: Long?,
    plate: String?,
    onConfirm: (km: Long?, note: String) -> Unit,
    onDismiss: () -> Unit,
    /** Päivän lisätiedot esitäyttönä; tallennus kirjoittaa kentän sisällön päivälle, myös tyhjennyksen. */
    note: String = "",
) {
    var form by remember(note) { mutableStateOf(WorkEndForm(km = "", minKm = minKm, note = note)) }
    val title = stringResource(R.string.work_end)
    FormScreen(
        title = title,
        onClose = onDismiss,
        primaryLabel = title,
        primaryEnabled = form.canSave,
        onPrimary = { onConfirm(form.kmValue, form.note) },
    ) {
        val status = workDayStatus(segments, rememberNowEpochSec())
        val startedAt = status.startedAtEpochSec
        if (startedAt != null) {
            FormSection(stringResource(R.string.work_section_summary)) {
                Text(
                    stringResource(R.string.work_end_summary, formatClock(startedAt), formatDuration(status.workedSec)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                plate?.let {
                    Text(stringResource(R.string.work_end_summary_car, it), style = MaterialTheme.typography.bodyMedium)
                }
                // Aloituslukema (= pienin sallittu lopetuslukema) näkyviin, jotta lopetuslukeman voi suhteuttaa.
                minKm?.let {
                    Text(stringResource(R.string.work_end_summary_start_km, formatWhole(it)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        FormSection(stringResource(R.string.work_section_odometer)) {
            OdometerField(
                form.km,
                { form = form.withKm(it) },
                stringResource(R.string.work_odometer_optional),
                modifier = Modifier.fillMaxWidth(),
                isError = form.tooSmall,
            )
            if (form.tooSmall && minKm != null) {
                HelperLine(stringResource(R.string.work_km_too_small, formatWhole(minKm)), HelperTone.ERROR)
            }
        }
        FormSection(stringResource(R.string.note_label)) {
            NoteField(form.note, { form = form.withNote(it) }, stringResource(R.string.note_hint_work))
        }
    }
}

/**
 * Vaihda auto: vanhan auton lopetuslukema, uuden auton rekkari ja aloituslukema. [prefillPlate]: Autot-näkymästä
 * valittu uusi auto valmiiksi täytettynä ja [prefillStartKm] sen viimeisin tunnettu lukema
 *; vanhan auton lopetuslukema syötetään kuten muutenkin. [latestReadings]: rekisterinumero →
 * auton viimeisin lukema, jotta uuden auton valinta chipistä vaihtaa koskemattoman esitäytön ([SwitchCarForm.withNewPlate]).
 */
@Composable
fun SwitchCarFormScreen(
    minEndKm: Long?,
    currentPlate: String?,
    knownPlates: List<String>,
    knownFuelPlates: Set<String>,
    latestReadings: Map<String, Long>,
    onConfirm: (endKm: Long?, startKm: Long?, newPlate: String?, fuel: FuelKind?) -> Unit,
    onDismiss: () -> Unit,
    prefillPlate: String? = null,
    prefillStartKm: Long? = null,
) {
    var form by remember {
        mutableStateOf(SwitchCarForm("", prefillStartKm?.toString() ?: "", prefillPlate ?: "", minEndKm, prefillStartKm = prefillStartKm))
    }
    val title = stringResource(R.string.work_switch_car)
    FormScreen(
        title = title,
        onClose = onDismiss,
        primaryLabel = title,
        primaryEnabled = form.canSave,
        onPrimary = { onConfirm(form.endValue, form.startValue, form.newPlate, form.fuelToSave(knownFuelPlates)) },
    ) {
        FormSection(stringResource(R.string.work_section_current_car)) {
            currentPlate?.let {
                Text(stringResource(R.string.work_switch_current_plate, it), style = MaterialTheme.typography.bodyLarge)
            }
            OdometerField(
                form.endKm,
                { form = form.withEndKm(it) },
                stringResource(R.string.work_switch_end_km),
                modifier = Modifier.fillMaxWidth(),
                isError = form.tooSmall,
            )
            if (form.tooSmall && minEndKm != null) {
                HelperLine(stringResource(R.string.work_km_too_small, formatWhole(minEndKm)), HelperTone.ERROR)
            }
        }
        FormSection(stringResource(R.string.work_section_new_car)) {
            PlateChips(
                form.newPlate,
                { form = form.withNewPlate(it, latestReadings) },
                knownPlates.filter { it != currentPlate },
                stringResource(R.string.work_switch_new_plate),
            )
            if (form.asksFuel(knownFuelPlates)) OptionalFuelChips(form.fuel) { form = form.withFuel(it) }
            OdometerField(
                form.startKm,
                { form = form.withStartKm(it) },
                stringResource(R.string.work_switch_start_km),
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.prefillStartKm != null) HelperLine(stringResource(R.string.work_prefill_note))
        }
    }
}
