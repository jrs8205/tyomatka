package fi.tyomatka.ui.poi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.poi.FuelType
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiServices
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.localDecimal
import java.time.LocalDate
import fi.tyomatka.ui.components.AppTextField
import fi.tyomatka.ui.components.AppFilterChip
import fi.tyomatka.ui.components.AppButtonDefaults

/**
 * Ilmoita muutos (luonnos C2): valmiit valinnat, ei vapaata tekstiä. Kyllä/ei-valinta lähtee heti ([onReport]);
 * polttoaineet, aukiolo ja rajat lähtevät osion Lähetä-napista. Voimassa oleva ilmoitus näkyy valittuna, ja sen alla lukee,
 * onko se lähetetty vai odottaako se verkkoa; [notice] kertoo viimeisimmän napautuksen tuloksen.
 * Latauspisteellä ja lepopaikalla näytetään vain niille kuuluvat osat.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportSheet(
    selection: PoiSelection,
    notice: String?,
    onReport: (ReportField, String) -> Unit,
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 0.9f,
) {
    val item = selection.item
    val station = item.category == PoiCategory.FUEL || item.category == PoiCategory.GAS
    val rest = item.category == PoiCategory.REST
    val latest = ReportChoices.latest(selection.reports, selection.info, selection.chargingNearby)
    val saved = ReportChoices.savedForm(selection.info, latest)
    var edits by remember(item.id) { mutableStateOf(ReportEdits()) }
    val stored = SavedReport(saved, latest.filterValues { it.state == ReportState.WAITING }.keys.toSet())
    var settledAt by remember(item.id) { mutableStateOf(stored) }
    LaunchedEffect(item.id, stored) {
        edits = edits.settled(settledAt, stored)
        settledAt = stored
    }
    val form = edits.over(saved)
    var hoursField by remember(item.id) { mutableStateOf<ReportField?>(null) }
    var confirm by remember(item.id) { mutableStateOf<Pair<ReportField, String>?>(null) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * maxHeightFraction).dp
    val context = LocalContext.current
    val strings: (Int, Array<out Any>) -> String = { id, args -> context.getString(id, *args) }
    val today = LocalDate.now().toEpochDay()
    val labels = PoiCardText.hoursLabels(strings)

    /** Kaikille näkyvä ja kauaskantoinen ilmoitus (suljettu, lopettanut, ei polttoaineita) varmistetaan ensin. */
    fun report(field: ReportField, value: String) {
        if (ReportChoices.isRepeat(latest[field], value, today)) return
        if (confirmRes(field, value, item.category) != null) confirm = field to value else onReport(field, value)
    }
    fun send(field: ReportField) = form.pairs().firstOrNull { it.first == field }?.let { (f, v) -> report(f, v) }

    /** Kertavalinta: lähtee heti, ja voimassa oleva näkyy valittuna. */
    @Composable
    fun Option(text: String, field: ReportField, value: String) = Choice(text, latest[field]?.value == value) { report(field, value) }

    confirm?.let { (field, value) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.poi_report_confirm, stringResource(confirmRes(field, value, item.category) ?: R.string.poi_report))) },
            confirmButton = {
                TextButton(onClick = Tap {
                    confirm = null
                    onReport(field, value)
                }) { Text(stringResource(R.string.poi_report_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = Tap { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Otsikko ja tulos pysyvät näkyvissä, vaikka valintoja vieritetään.
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.poi_report), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(PoiServices.displayName(item) ?: stringResource(PoiCardText.categoryNameRes(item.category)), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.poi_report_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (notice != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {

                Group(stringResource(PoiCardText.statusHeadingRes(item.category))) {
                    Option(stringResource(PoiCardText.closedRes(item.category)), ReportField.CLOSED, "yes")
                    Option(stringResource(PoiCardText.runningRes(item.category)), ReportField.CLOSED, "no")
                }
                Status(latest[ReportField.CLOSED], strings)

                if (!rest) {
                    val kinds = buildList {
                        add(ReportField.HOURS_STATION to PoiCardText.mainHoursRes(item.category))
                        if (station) {
                            add(ReportField.HOURS_RESTAURANT to R.string.poi_report_hours_restaurant)
                            add(ReportField.HOURS_LUNCH to R.string.poi_report_hours_lunch)
                            add(ReportField.HOURS_SHOP to R.string.poi_report_hours_shop)
                        }
                    }
                    Group(stringResource(R.string.poi_report_hours)) {
                        kinds.forEach { (field, label) ->
                            // Lajisiru vain avaa muokkauksen, joten siinä ei ole lähetetyn merkkiä.
                            Choice(stringResource(label), hoursField == field, check = false) { hoursField = field }
                        }
                    }
                    kinds.forEach { (field, label) ->
                        Status(latest[field], strings, stringResource(label), latest[field]?.let { PoiCardText.hoursText(it.value, labels) })
                    }
                    hoursField?.let { field ->
                        val hours = form.hours[field] ?: HoursForm.EMPTY
                        fun update(new: HoursForm) { edits = edits.copy(hours = edits.hours + (field to new)) }
                        DayRow(stringResource(R.string.poi_report_weekdays), hours.weekdays) { update(hours.copy(weekdays = it)) }
                        DayRow(stringResource(R.string.poi_report_saturday), hours.saturday) { update(hours.copy(saturday = it)) }
                        DayRow(stringResource(R.string.poi_report_sunday), hours.sunday) { update(hours.copy(sunday = it)) }
                        val value = hours.toOsm().takeIf(field::accepts)
                        SendButton(enabled = value != null && !ReportChoices.isRepeat(latest[field], value, today)) { send(field) }
                    }
                    // Jokainen laji erikseen: toisen lajin avaaminen ei piilota lähettämätöntä muutosta.
                    kinds.forEach { (field, label) -> EditNote(edits.state(field, saved), stringResource(label)) }
                }

                if (station) {
                    Group(stringResource(R.string.poi_report_fuels), hint = stringResource(R.string.poi_report_fuels_hint)) {
                        FuelType.entries.forEach { type ->
                            val selected = form.fuels.orEmpty()
                            Choice(stringResource(PoiCardText.fuelNameRes(type)), type in selected) {
                                val next = if (type in selected) selected - type else selected + type
                                edits = edits.copy(fuels = next.takeUnless { it == saved.fuels })
                            }
                        }
                    }
                    // Muuttumatonkin valikoima lähtee (kuljettaja vahvistaa myynnin jatkuneen, esim. Teboil), paitsi saman päivän
                    // oman toistona; "ei polttoaineita" kulkee varmistuksen kautta.
                    val fuels = form.fuelsReport()?.second
                    SendButton(enabled = fuels != null && !ReportChoices.isRepeat(latest[ReportField.FUELS], fuels, today)) {
                        form.fuelsReport()?.let { (f, v) -> report(f, v) }
                    }
                    val fuelsEdit = edits.state(ReportField.FUELS, saved)
                    if (fuelsEdit == EditState.NONE) Status(latest[ReportField.FUELS], strings) else EditNote(fuelsEdit)

                    Group("AdBlue") {
                        Option(stringResource(R.string.poi_report_adblue_pump), ReportField.ADBLUE, "pump")
                        Option(stringResource(R.string.poi_report_adblue_canister), ReportField.ADBLUE, "canister")
                        Option(stringResource(R.string.poi_report_adblue_no), ReportField.ADBLUE, "no")
                    }
                    Status(latest[ReportField.ADBLUE], strings)
                    Group(stringResource(R.string.poi_section_charging)) {
                        Option(stringResource(R.string.poi_report_yes), ReportField.CHARGING, "yes")
                        Option(stringResource(R.string.poi_report_no), ReportField.CHARGING, "no")
                    }
                    Status(latest[ReportField.CHARGING], strings)
                    Group(stringResource(R.string.poi_section_shop)) {
                        Option(stringResource(R.string.poi_report_yes), ReportField.SHOP, "yes")
                        Option(stringResource(R.string.poi_report_ended), ReportField.SHOP, "no")
                    }
                    Status(latest[ReportField.SHOP], strings)
                    Group(stringResource(R.string.poi_section_food)) {
                        Option(stringResource(R.string.poi_report_yes), ReportField.RESTAURANT, "yes")
                        Option(stringResource(R.string.poi_report_ended), ReportField.RESTAURANT, "no")
                        Option(stringResource(R.string.poi_report_lunch_yes), ReportField.LUNCH, "yes")
                        Option(stringResource(R.string.poi_report_no_lunch), ReportField.LUNCH, "no")
                    }
                    Status(latest[ReportField.RESTAURANT], strings, stringResource(R.string.poi_report_hours_restaurant))
                    Status(latest[ReportField.LUNCH], strings, stringResource(R.string.poi_report_hours_lunch))
                }

                if (station || rest) {
                    val services = listOf(
                        ReportField.TRUCK_PARKING to R.string.poi_truck_parking,
                        ReportField.SHOWER to R.string.poi_chip_shower,
                        ReportField.TOILETS to R.string.poi_chip_toilets,
                        ReportField.SAUNA to R.string.poi_sauna,
                        ReportField.OVERNIGHT to R.string.poi_report_overnight,
                    )
                    Group(stringResource(R.string.poi_report_services)) {
                        services.forEach { (field, label) ->
                            val name = stringResource(label)
                            Option(stringResource(R.string.poi_report_service_yes, name), field, "yes")
                            Option(stringResource(R.string.poi_report_service_no, name), field, "no")
                        }
                    }
                    services.forEach { (field, label) -> Status(latest[field], strings, stringResource(label)) }
                    Group(stringResource(R.string.poi_report_length)) {
                        Option(stringResource(R.string.poi_report_length_14), ReportField.TRUCK_LENGTH, "14")
                        Option(stringResource(R.string.poi_report_length_25), ReportField.TRUCK_LENGTH, "25.25")
                        Option(stringResource(R.string.poi_report_length_34), ReportField.TRUCK_LENGTH, "34.5")
                    }
                    Status(latest[ReportField.TRUCK_LENGTH], strings)
                    Heading(stringResource(R.string.poi_report_limits))
                    NumberField(form.maxHeight, stringResource(R.string.poi_fix_max_height), edits.state(ReportField.MAX_HEIGHT, saved) == EditState.INVALID) {
                        edits = edits.copy(maxHeight = it)
                    }
                    NumberField(form.maxWeight, stringResource(R.string.poi_fix_max_weight), edits.state(ReportField.MAX_WEIGHT, saved) == EditState.INVALID) {
                        edits = edits.copy(maxWeight = it)
                    }
                    // Vain muuttunut raja lähtee: toisen kentän vanha arvo ei saa uutta päivää.
                    val changed = listOf(ReportField.MAX_HEIGHT, ReportField.MAX_WEIGHT).filter { edits.state(it, saved) == EditState.UNSENT }
                    SendButton(enabled = changed.isNotEmpty()) { changed.forEach { send(it) } }
                    if (changed.isNotEmpty()) EditNote(EditState.UNSENT)
                    Status(latest[ReportField.MAX_HEIGHT], strings, stringResource(R.string.poi_fix_max_height), latest[ReportField.MAX_HEIGHT]?.let { localDecimal(it.value) })
                    Status(latest[ReportField.MAX_WEIGHT], strings, stringResource(R.string.poi_fix_max_weight), latest[ReportField.MAX_WEIGHT]?.let { localDecimal(it.value) })
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = Tap(onDismiss), modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.poi_close)) }
        }
    }
}

/** Varmistettavan ilmoituksen nimi; null = lähtee heti. */
private fun confirmRes(field: ReportField, value: String, category: PoiCategory): Int? = when {
    field == ReportField.CLOSED && value == "yes" -> PoiCardText.closedRes(category)
    field == ReportField.RESTAURANT && value == "no" -> R.string.poi_food_closed
    field == ReportField.SHOP && value == "no" -> R.string.poi_shop_closed
    field == ReportField.FUELS && value == "none" -> R.string.poi_report_fuels_none
    else -> null
}

@Composable
private fun Heading(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Heading(title)
    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** Valinta: valittu erottuu värin lisäksi merkistä ([check]). */
@Composable
private fun Choice(text: String, selected: Boolean, check: Boolean = true, onClick: () -> Unit) {
    AppFilterChip(
        selected = selected,
        onClick = Tap(onClick),
        label = { Text(text) },
        showCheck = check,
    )
}

/** Lomakkeella on muutos, joka ei ole talteen: lähettämätön tai sellainen, jota ei voi lähettää. Sulkeminen hävittäisi sen. */
@Composable
private fun EditNote(state: EditState, label: String? = null) {
    val text = stringResource(
        when (state) {
            EditState.NONE -> return
            EditState.UNSENT -> R.string.poi_report_unsent
            EditState.INVALID -> R.string.poi_report_invalid
        },
    )
    Spacer(Modifier.height(4.dp))
    Text(
        if (label == null) text else stringResource(R.string.poi_report_status_labeled, label, text),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Kentän voimassa olevan ilmoituksen tila; [label] nimeää kentän, kun osiossa on useampi, ja [value] kertoo arvon. */
@Composable
private fun Status(report: FieldReport?, strings: (Int, Array<out Any>) -> String, label: String? = null, value: String? = null) {
    if (report == null) return
    Spacer(Modifier.height(4.dp))
    Text(
        ReportChoices.statusLine(report, label, value, strings),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (report.state == ReportState.OTHERS) FontWeight.Normal else FontWeight.Medium,
        color = if (report.state == ReportState.OTHERS) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Button(onClick = Tap(onClick), enabled = enabled, colors = AppButtonDefaults.filledColors()) { Text(stringResource(R.string.poi_report_send)) }
}

@Composable
private fun NumberField(value: String, label: String, invalid: Boolean, onChange: (String) -> Unit) {
    Spacer(Modifier.height(4.dp))
    AppTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.poi_report_invalid)) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayRow(label: String, hours: DayHours, onChange: (DayHours) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(stringResource(R.string.poi_report_day_closed), hours == DayHours.Closed) { onChange(DayHours.Closed) }
        Choice(stringResource(R.string.poi_report_day_all), hours == DayHours.AllDay) { onChange(DayHours.AllDay) }
        Choice(stringResource(R.string.poi_report_day_open), hours is DayHours.Range) { if (hours !is DayHours.Range) onChange(DayHours.Range("08:00", "20:00")) }
    }
    if (hours is DayHours.Range) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                value = hours.open,
                onValueChange = { onChange(hours.copy(open = it)) },
                label = { Text(stringResource(R.string.poi_report_opens)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            AppTextField(
                value = hours.close,
                onValueChange = { onChange(hours.copy(close = it)) },
                label = { Text(stringResource(R.string.poi_report_closes)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
