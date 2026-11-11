package fi.tyomatka.ui.history.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.ui.FullScreenDialog
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.form.FormScreen
import fi.tyomatka.ui.form.FormSection
import fi.tyomatka.ui.form.HelperLine
import fi.tyomatka.ui.form.HelperTone
import fi.tyomatka.ui.history.PlateField
import fi.tyomatka.ui.components.AppFilterChip
import fi.tyomatka.ui.components.AppButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.ui.text.style.TextAlign
import fi.tyomatka.ui.components.AppSnackbar

/**
 * Autot: tunnetut autot polttoaineineen ([carListRows]); napautus avaa auton lomakkeen, ja
 * Lisää auto tuo auton, jolla ei vielä ole kirjauksia. [message] (esimerkiksi auto, jota ei poistettu) näytetään
 * tässä ikkunassa, koska Työmatka-sivun ilmoitus jäisi sen alle; [onMessageShown] kuittaa sen.
 *
 * Rivin Valitse ([carRowChoice]): työpäivän ollessa käynnissä ([ongoing], avoimen jakson auto
 * [openPlate]) toinen auto avaa Vaihda auto -lomakkeen ([onSwitchCar]); muuten auto valitaan seuraavaan ajoon
 * ([onChooseNext]), ja seuraavan ajon auto [nextPlate] on merkitty.
 */
@Composable
fun CarsScreen(
    rows: List<CarListRow>,
    message: String?,
    ongoing: Boolean,
    openPlate: String?,
    nextPlate: String?,
    onMessageShown: () -> Unit,
    onSave: (plate: String, fuel: FuelKind?) -> Unit,
    onDelete: (String) -> Unit,
    onSwitchCar: (String) -> Unit,
    onChooseNext: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Muokattava auto rekisterinumerona: rivi haetaan joka kerta tuoreesta listasta, koska varmuuskopion tuonti voi
    // tuoda autolle kirjauksia lomakkeen ollessa auki, jolloin poisto ei ole enää sallittu.
    var editingPlate by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long)
        onMessageShown()
    }
    FullScreenDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                    ) {
                        IconButton(onClick = Tap(onDismiss)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                        }
                        Text(
                            stringResource(R.string.cars_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        TextButton(onClick = Tap { adding = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.car_add))
                        }
                    }
                    if (rows.isEmpty()) {
                        Text(
                            stringResource(R.string.cars_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    // Autot yhtenä ryhmänä erottimin: ensimmäisellä ja viimeisellä rivillä pyöreät kulmat.
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.car.plate }) { index, row ->
                            val top = if (index == 0) 24.dp else 0.dp
                            val bottom = if (index == rows.lastIndex) 24.dp else 0.dp
                            Surface(
                                onClick = Tap { editingPlate = row.car.plate },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column {
                                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.DirectionsCar,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(row.car.plate, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            row.fuel?.let { fuelKindName(it) } ?: stringResource(R.string.car_fuel_unknown),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // Valinta on lisätoiminto muokkauksen rinnalla: nappi tai merkintä kynän vieressä.
                                    val plate = row.car.plate
                                    when (carRowChoice(plate, ongoing, openPlate, nextPlate)) {
                                        // Merkintä saa rivittyä ("Nästa / körning"), ettei rekisterinumero katkea.
                                        CarRowChoice.IN_USE -> ChoiceMark(stringResource(R.string.car_in_use), Modifier.weight(1f, fill = false))
                                        CarRowChoice.NEXT -> ChoiceMark(stringResource(R.string.car_next), Modifier.weight(1f, fill = false))
                                        CarRowChoice.SWITCH_CAR -> SelectButton(plate) { onSwitchCar(plate) }
                                        CarRowChoice.CHOOSE_NEXT -> SelectButton(plate) { onChooseNext(plate) }
                                    }
                                    // Kynä kertoo, että autoa voi muokata.
                                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.car_edit), tint = MaterialTheme.colorScheme.primary)
                                }
                                }
                            }
                        }
                    }
                }
                SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter)) { AppSnackbar(it) }
            }
        }
    }
    editingPlate?.let { plate -> rows.firstOrNull { it.car.plate == plate } }?.let { row ->
        CarFormScreen(
            row = row,
            initial = CarForm.initial(row.car),
            existingPlates = emptySet(),
            onSave = { plate, fuel ->
                onSave(plate, fuel)
                editingPlate = null
            },
            onDelete = if (row.removable) {
                {
                    onDelete(row.car.plate)
                    editingPlate = null
                }
            } else {
                null
            },
            onDismiss = { editingPlate = null },
        )
    }
    if (adding) {
        CarFormScreen(
            row = CarListRow(CarEntity("", null, null), fuel = null, fuelFromFillups = null),
            initial = CarForm.blank(),
            existingPlates = rows.map { it.car.plate }.toSet(),
            onSave = { plate, fuel ->
                onSave(plate, fuel)
                adding = false
            },
            // Listalla jo oleva auto avautuu muokattavaksi.
            onEditExisting = { plate ->
                adding = false
                editingPlate = plate
            },
            onDismiss = { adding = false },
        )
    }
}

/** Valitse-nappi: ruudunlukijalle "Valitse EVO-456", jotta rivin napit erottuvat toisistaan. */
@Composable
private fun SelectButton(plate: String, onClick: () -> Unit) {
    val name = stringResource(R.string.car_select_named, plate)
    TextButton(onClick = Tap(onClick), modifier = Modifier.semantics { contentDescription = name }) {
        Text(stringResource(R.string.car_select))
    }
}

/** Käytössä olevan tai seuraavan ajon auton merkintä napin paikalla, samalla sisennyksellä kuin napin teksti. */
@Composable
private fun ChoiceMark(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.End,
        modifier = modifier.padding(horizontal = 12.dp),
    )
}

/**
 * Auton lomake: polttoaine, automaattinen tai käyttäjän valitsema. Uudelle autolle myös
 * rekisterinumero, joka ei saa olla jo listalla ([existingPlates]); listalla oleva avautuu muokattavaksi
 * ([onEditExisting]). Auton, jolla ei ole työjaksoja eikä kirjauksia, voi poistaa ([onDelete]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarFormScreen(
    row: CarListRow,
    initial: CarForm,
    existingPlates: Set<String>,
    onSave: (plate: String, fuel: FuelKind?) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onEditExisting: (String) -> Unit = {},
) {
    // Avain on rekisterinumero, ei rivi: rivin muut tiedot voivat päivittyä lomakkeen ollessa auki (varmuuskopion
    // tuonti), eivätkä kesken olevat valinnat saa silloin nollautua.
    var form by remember(row.car.plate) { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    FormScreen(
        title = if (form.isNew) stringResource(R.string.car_add) else row.car.plate,
        onClose = onDismiss,
        primaryLabel = stringResource(R.string.save),
        primaryEnabled = form.canSave(existingPlates) && (form.isNew || !form.isUnchanged(initial)),
        onPrimary = { if (form.canSave(existingPlates)) onSave(form.savedPlate, form.fuel) },
        topAction = onDelete?.let {
            {
                TextButton(onClick = Tap { confirmDelete = true }) {
                    Text(stringResource(R.string.car_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        if (form.isNew) {
            FormSection(stringResource(R.string.fuel_section_car)) {
                val existing = form.existingPlate(existingPlates)
                PlateField(
                    form.plate,
                    { form = form.withPlate(it) },
                    stringResource(R.string.fuel_plate),
                    knownPlates = emptyList(),
                    isError = existing != null,
                )
                if (existing != null) {
                    // Ilmoitus luetaan ääneen heti, ja auton voi avata muokattavaksi.
                    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        HelperLine(stringResource(R.string.car_exists), HelperTone.ERROR)
                    }
                    TextButton(onClick = Tap { onEditExisting(existing) }) {
                        Text(stringResource(R.string.car_edit_existing, existing))
                    }
                }
            }
        }
        FormSection(stringResource(R.string.car_section_fuel)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilterChip(
                    selected = form.fuel == null,
                    onClick = Tap { form = form.withFuel(null) },
                    label = { Text(stringResource(R.string.car_auto)) },
                )
                FuelKind.mainFuels.forEach { kind ->
                    AppFilterChip(
                        selected = form.fuel == kind,
                        onClick = Tap { form = form.withFuel(kind) },
                        label = { Text(fuelKindName(kind)) },
                    )
                }
            }
            // Automaattinen kertoo, mitä auton kirjauksista nyt päätellään. Tekstit seuraavat käyttövoimaa (lataus,
            // täyttö); tuntemattomalla autolla kirjaus on tankkaus.
            val derived = row.fuelFromFillups
            val ownFuel = form.fuel
            HelperLine(
                when {
                    ownFuel != null -> stringResource(
                        fillWord(ownFuel).pick(R.string.car_fuel_own_note, R.string.car_fuel_own_note_charge, R.string.car_fuel_own_note_gas)
                    )
                    derived != null -> stringResource(
                        fillWord(derived).pick(R.string.car_fuel_auto_note, R.string.car_fuel_auto_note_charge, R.string.car_fuel_auto_note_gas),
                        fuelKindName(derived),
                    )
                    else -> stringResource(R.string.car_fuel_auto_none)
                }
            )
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.car_delete_confirm, row.car.plate)) },
            text = { Text(stringResource(R.string.car_delete_note)) },
            confirmButton = {
                Button(colors = AppButtonDefaults.dangerColors(), 
                    onClick = Tap {
                        confirmDelete = false
                        onDelete()
                    }
                ) { Text(stringResource(R.string.car_delete)) }
            },
            dismissButton = {
                TextButton(onClick = Tap { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Valinnainen polttoaine autolle, jonka polttoainetta ei tiedetä; uusi napautus poistaa valinnan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OptionalFuelChips(selected: FuelKind?, label: String? = null, onSelect: (FuelKind?) -> Unit) {
    Text(
        label ?: stringResource(R.string.work_fuel_optional),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FuelKind.mainFuels.forEach { kind ->
            AppFilterChip(
                selected = selected == kind,
                onClick = Tap { onSelect(kind.takeIf { it != selected }) },
                label = { Text(fuelKindName(kind)) },
            )
        }
    }
}
