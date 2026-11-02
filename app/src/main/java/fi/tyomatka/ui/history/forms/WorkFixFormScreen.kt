package fi.tyomatka.ui.history.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.ui.form.FormScreen
import fi.tyomatka.ui.form.FormSection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import fi.tyomatka.ui.form.NoteField
import fi.tyomatka.ui.form.HelperLine
import fi.tyomatka.ui.form.HelperTone
import fi.tyomatka.ui.form.PlateChips
import fi.tyomatka.ui.history.FixEdit
import fi.tyomatka.ui.history.fixesChanged
import fi.tyomatka.ui.history.OdometerField
import fi.tyomatka.ui.history.SegmentFix
import fi.tyomatka.ui.history.resolveSegmentFix
import fi.tyomatka.ui.parseClockMinutes
import java.time.ZoneId

/**
 * Segmenttien korjaus: kellonajat ja mittarilukemat rinnakkain, rekkari chippeinä.
 * Avoimen segmentin lopetusajan voi täyttää — se päättää unohtuneen päivän; tyhjänä päivä pysyy kesken.
 */
@Composable
fun WorkFixFormScreen(
    segments: List<WorkSegmentEntity>,
    knownPlates: List<String>,
    onSave: (fixes: Map<Long, SegmentFix>, note: String) -> Unit,
    onDismiss: () -> Unit,
    /** Päivän lisätiedot; yksi teksti päivää kohti autojaksoista riippumatta. */
    note: String = "",
) {
    val zone = remember { ZoneId.systemDefault() }
    var noteText by remember(note) { mutableStateOf(note) }
    val edits = remember {
        mutableStateMapOf<Long, FixEdit>().apply {
            segments.forEach { seg -> put(seg.id, FixEdit.of(seg)) }
        }
    }
    fun fixOf(seg: WorkSegmentEntity): SegmentFix? = edits[seg.id]?.let { resolveSegmentFix(seg, it, zone) }
    val allValid = segments.all { fixOf(it) != null }

    FormScreen(
        title = stringResource(R.string.work_fix),
        onClose = onDismiss,
        primaryLabel = stringResource(R.string.save),
        primaryEnabled = allValid && (noteText != note || fixesChanged(segments, edits)),
        onPrimary = { onSave(segments.associate { seg -> seg.id to requireNotNull(fixOf(seg)) }, noteText) },
    ) {
        segments.forEachIndexed { index, seg ->
            val edit = edits[seg.id] ?: return@forEachIndexed
            val startValid = parseClockMinutes(edit.startTime) != null
            val valid = fixOf(seg) != null
            val title = if (segments.size > 1) {
                stringResource(R.string.work_fix_section_segment, index + 1, edit.plate.ifBlank { "–" })
            } else {
                stringResource(R.string.work_fix_section_single)
            }
            FormSection(title) {
                PlateChips(edit.plate, { edits[seg.id] = edit.copy(plate = it) }, knownPlates, stringResource(R.string.work_plate))
                // Kentät saman korkuisina rivillä ja otsikot yksirivisinä, jotta kaikki neljä ovat saman kokoiset
                // ("(kesken)" venytti lopetuskentän).
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField(
                        edit.startTime,
                        { edits[seg.id] = edit.copy(startTime = it) },
                        stringResource(R.string.work_time_start),
                        isError = !startValid,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    OdometerField(
                        edit.startKm,
                        { edits[seg.id] = edit.copy(startKm = it) },
                        stringResource(R.string.work_km_start_short),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField(
                        edit.endTime,
                        { edits[seg.id] = edit.copy(endTime = it) },
                        stringResource(R.string.work_time_end),
                        isError = startValid && !valid,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    OdometerField(
                        edit.endKm,
                        { edits[seg.id] = edit.copy(endKm = it) },
                        stringResource(R.string.work_km_end_short),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (seg.endEpochSec == null) HelperLine(stringResource(R.string.work_fix_open_hint), HelperTone.INFO)
                if (!valid) HelperLine(stringResource(R.string.work_fix_invalid), HelperTone.ERROR)
            }
        }
        FormSection(stringResource(R.string.note_label)) {
            NoteField(noteText, { noteText = it }, stringResource(R.string.note_hint_work))
        }
    }
}

/** Kellonaikakenttä: numerot ja erotin (piste/kaksoispiste). */
@Composable
private fun TimeField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ':' || c == ',' }) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
