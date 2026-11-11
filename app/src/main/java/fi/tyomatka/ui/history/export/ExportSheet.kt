package fi.tyomatka.ui.history.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.tyomatka.R
import fi.tyomatka.data.report.ExportChoice
import fi.tyomatka.data.report.ExportPeriod
import fi.tyomatka.data.report.ExportSummary
import fi.tyomatka.data.report.FillGroup
import fi.tyomatka.data.report.exportFileName
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.form.AppDateLocale
import fi.tyomatka.ui.formatMonth
import fi.tyomatka.ui.formatDayRange
import fi.tyomatka.ui.formatDecimal
import java.time.LocalDate
import fi.tyomatka.ui.components.AppButtonDefaults
import fi.tyomatka.ui.components.AppDragHandle

private const val DAY_MILLIS = 86_400_000L
private const val NO_DAY = Long.MIN_VALUE

/** Paneelin valinta prosessin yli: valinta ja oman välin päivät epoch-päivinä. */
val ExportFormSaver: Saver<ExportForm, Any> = listSaver(
    save = { listOf(it.choice.name, it.custom?.first?.toEpochDay() ?: NO_DAY, it.custom?.second?.toEpochDay() ?: NO_DAY) },
    restore = { values ->
        val from = values[1] as Long
        val to = values[2] as Long
        val custom = if (from != NO_DAY && to != NO_DAY) LocalDate.ofEpochDay(from) to LocalDate.ofEpochDay(to) else null
        ExportForm(ExportChoice.valueOf(values[0] as String), custom)
    },
)

private val PERIOD_CHOICES = listOf(
    ExportChoice.THIS_MONTH to R.string.export_this_month,
    ExportChoice.PREVIOUS_MONTH to R.string.export_previous_month,
    ExportChoice.THIS_YEAR to R.string.export_this_year,
    ExportChoice.PREVIOUS_YEAR to R.string.export_previous_year,
    ExportChoice.CUSTOM to R.string.export_custom,
)

private val GROUP_PLURALS = mapOf(
    FillGroup.FILL to R.plurals.export_fills,
    FillGroup.CHARGE to R.plurals.export_charges,
    FillGroup.GAS to R.plurals.export_gas_fills,
)

/**
 * Viennin alapaneeli (määrittely: Vienti sovelluksessa): jaksot yhtenä radioryhmänä, tai koko varmuuskopio ilman jaksoja,
 * kun se avataan asetuksista; yhteenveto määrineen ja kokovaroitus. Vie avaa järjestelmän tiedostonvalitsimen.
 * [summary] on null, kun valitun jakson määriä lasketaan tai omaa väliä ei ole valittu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    form: ExportForm,
    onFormChange: (ExportForm) -> Unit,
    summary: ExportSummary?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickRange by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(dragHandle = { AppDragHandle() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(if (form.choice == ExportChoice.FULL_BACKUP) R.string.export_full_backup else R.string.export_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (form.choice != ExportChoice.FULL_BACKUP) {
                Text(
                    stringResource(R.string.export_period),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Column(Modifier.selectableGroup()) {
                    PERIOD_CHOICES.forEach { (choice, label) ->
                        ChoiceRow(stringResource(label), form.choice == choice) {
                            onFormChange(form.copy(choice = choice))
                            if (choice == ExportChoice.CUSTOM && form.custom == null) pickRange = true
                        }
                    }
                    if (form.choice == ExportChoice.CUSTOM) {
                        Row(Modifier.fillMaxWidth().padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            val range = form.custom?.let { (from, to) -> formatDayRange(from.toEpochDay(), to.toEpochDay()) }
                            Text(range ?: stringResource(R.string.export_no_days), modifier = Modifier.weight(1f))
                            TextButton(onClick = Tap { pickRange = true }) { Text(stringResource(R.string.export_choose_days)) }
                        }
                    }
                }
            }
            form.period(today)?.let { period -> SummaryBox(period, summary, today) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = Tap(onExport), enabled = form.canExport(summary), colors = AppButtonDefaults.filledColors()) { Text(stringResource(R.string.export_button)) }
            }
        }
    }
    if (pickRange) {
        RangePickerDialog(
            initial = form.custom,
            today = today,
            onDismiss = { pickRange = false },
            onConfirm = {
                onFormChange(form.copy(choice = ExportChoice.CUSTOM, custom = it))
                pickRange = false
            },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = Tap(onClick)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Yhteenveto: jakso, määrät oikeilla sanoilla, koko, huomautus ja tiedoston nimi. Muutos luetaan ruudunlukijalle. */
@Composable
private fun SummaryBox(period: ExportPeriod, summary: ExportSummary?, today: LocalDate) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(R.string.export_summary_period, periodLabel(period)), fontWeight = FontWeight.SemiBold)
            when {
                summary == null -> Text(stringResource(R.string.export_counting))
                summary.isEmpty && period != ExportPeriod.Full -> Text(stringResource(R.string.export_empty))
                else -> {
                    Text(stringResource(R.string.export_summary_contents, contents(summary), sizeText(summary.estimatedBytes)))
                    if (summary.isLarge) {
                        Text(
                            stringResource(R.string.export_large),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        stringResource(if (period == ExportPeriod.Full) R.string.export_note_full else R.string.export_note_period),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Text(
                exportFileName(period, today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** "12 työpäivää, 3 tankkausta, 2 latausta ja 5 kuvaa". */
@Composable
private fun contents(summary: ExportSummary): String {
    val parts = buildList {
        add(pluralStringResource(R.plurals.export_work_days, summary.workDays, summary.workDays))
        FillGroup.entries.forEach { group ->
            val n = summary.counts[group] ?: 0
            if (n > 0) add(pluralStringResource(GROUP_PLURALS.getValue(group), n, n))
        }
        add(pluralStringResource(R.plurals.export_photos, summary.photos, summary.photos))
        if (summary.templates > 0) add(pluralStringResource(R.plurals.export_templates, summary.templates, summary.templates))
    }
    val and = stringResource(R.string.export_and)
    return parts.dropLast(1).joinToString(", ") + " $and " + parts.last()
}

/** Arvioitu koko megatavuina yhdellä desimaalilla, vähintään 0,1 Mt. */
@Composable
private fun sizeText(bytes: Long): String =
    stringResource(R.string.export_size_mb, formatDecimal(maxOf(bytes / (1024.0 * 1024.0), 0.1), 1))

@Composable
private fun periodLabel(period: ExportPeriod): String = when (period) {
    is ExportPeriod.Month ->
        formatMonth(period.month)
    is ExportPeriod.Year -> stringResource(R.string.export_period_year, period.year)
    is ExportPeriod.Range -> formatDayRange(period.from.toEpochDay(), period.to.toEpochDay())
    ExportPeriod.Full -> stringResource(R.string.export_period_full)
}

/**
 * Oma aikaväli koko näytön kalenterista; päivät voi myös kirjoittaa (valitsimen kynä). Ei tulevaisuuteen, ja
 * kalenteri on suomalainen kuten lomakkeen päiväkentässä.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(
    initial: Pair<LocalDate, LocalDate>?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (Pair<LocalDate, LocalDate>) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AppDateLocale {
            // DateRangePicker laskee UTC-millisekunneilla; epochDay * vuorokausi osuu keskiyöhön UTC.
            val state = rememberDateRangePickerState(
                initialSelectedStartDateMillis = initial?.first?.toEpochDay()?.times(DAY_MILLIS),
                initialSelectedEndDateMillis = initial?.second?.toEpochDay()?.times(DAY_MILLIS),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= today.toEpochDay() * DAY_MILLIS
                },
            )
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = Tap(onDismiss)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.weight(1f))
                        val start = state.selectedStartDateMillis
                        val end = state.selectedEndDateMillis
                        TextButton(
                            onClick = Tap {
                                if (start != null && end != null) {
                                    onConfirm(LocalDate.ofEpochDay(start / DAY_MILLIS) to LocalDate.ofEpochDay(end / DAY_MILLIS))
                                }
                            },
                            enabled = start != null && end != null,
                            colors = AppButtonDefaults.textColors(),
                        ) { Text(stringResource(R.string.export_range_save)) }
                    }
                    DateRangePicker(
                        state = state,
                        modifier = Modifier.weight(1f),
                        title = {
                            Text(
                                stringResource(R.string.export_range_title),
                                modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                            )
                        },
                        // Oma otsikko: valmisotsikko on niin suuri, että "Päättymispäivä" katkesi kahdelle riville.
                        headline = {
                            Text(
                                rangeHeadline(
                                    state.selectedStartDateMillis?.div(DAY_MILLIS),
                                    state.selectedEndDateMillis?.div(DAY_MILLIS),
                                    stringResource(R.string.export_range_start),
                                    stringResource(R.string.export_range_end),
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                // Valinta luetaan ruudunlukijalle kuten valmisotsikossa.
                                modifier = Modifier
                                    .padding(start = 24.dp, end = 12.dp, bottom = 12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        },
                    )
                }
            }
        }
    }
}
