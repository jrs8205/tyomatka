package fi.tyomatka.ui.history.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.db.FuelUnit
import fi.tyomatka.ui.currencySymbol
import fi.tyomatka.ui.form.DateField
import fi.tyomatka.ui.form.FormScreen
import fi.tyomatka.ui.form.FormSection
import fi.tyomatka.ui.form.NoteField
import fi.tyomatka.ui.form.HelperLine
import fi.tyomatka.ui.form.HelperTone
import fi.tyomatka.ui.form.PlateChips
import fi.tyomatka.ui.form.mutableStateSaver
import fi.tyomatka.ui.formatConsumption
import fi.tyomatka.ui.formatCostPerKm
import fi.tyomatka.ui.formatWhole
import fi.tyomatka.ui.history.DecimalField
import fi.tyomatka.ui.history.FillupSave
import fi.tyomatka.ui.history.FuelEntry
import fi.tyomatka.ui.history.OdometerField
import fi.tyomatka.ui.history.busy
import fi.tyomatka.ui.history.parseDecimal
import fi.tyomatka.ui.unitSymbol
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import fi.tyomatka.ui.components.AppFilterChip
import fi.tyomatka.ui.components.AppButtonDefaults
import androidx.compose.material3.Button

/** Tankkauksen kirjaus tai muokkaus koko näytön lomakkeena; logiikka on [FuelForm]issa. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuelFormScreen(
    existing: FuelFillupEntity?,
    defaultPlate: String?,
    /** Uuden kirjauksen esitäytetty päivä (päiväkortin tankkaus menneelle päivälle); null = tänään. */
    initialEpochDay: Long? = null,
    knownPlates: List<String>,
    fillups: List<FuelFillupEntity>,
    cars: Map<String, CarEntity>,
    /** Muokattavan kirjauksen tallennetut kuvat. */
    storedPhotos: List<FuelPhotoEntity>,
    photoActions: PhotoActions,
    /** Sovellukseen jaetut kuvat odottavat; [takeIncomingPhotos] ottaa ne lomakkeelle. */
    incomingPhotos: List<File>,
    takeIncomingPhotos: () -> List<File>,
    /** Tallennuksen vaihe: kiireinen ([busy]) estää toisen tallennuksen ja sulkemisen, virhe jättää lomakkeen auki ja näkyy. */
    save: FillupSave?,
    onSave: (FuelEntry, PhotoEdit) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone).toEpochDay() }
    // Kiireinen lomake (tallennus kesken tai valmis, sulkeutuu seuraavassa kehyksessä) pitää tallennusta edeltäneet
    // kirjaukset, autot ja kuvat, ettei juuri tallennettu rivi välähdä esikatselussa, lukemarajassa tai kuvarivillä.
    val busy = save.busy
    val data = remember { FuelFormData(fillups, cars, storedPhotos) }.refresh(busy, fillups, cars, storedPhotos)
    // Syötteet ja kuvamuutokset säilyvät prosessin lopetuksen yli (Android lopettaa prosessin kameran ollessa auki);
    // konteksti tulee tuoreena avattaessa ja palautettaessa. Oma tilan Saver: kelvoton tallenne johtaa alkuarvoon.
    val formContext = { FuelFormContext(data.fillups, existing, System.currentTimeMillis() / 1000, zone, data.cars) }
    var edited by rememberSaveable(existing?.id, saver = mutableStateSaver(fuelFormSaver(formContext))) {
        mutableStateOf(FuelForm.initial(formContext(), defaultPlate, today, initialEpochDay))
    }
    // Avatun kirjauksen syötteet vertailua varten: Tallenna on harmaana, kunnes jokin muuttuu.
    val original = remember(existing?.id) { FuelForm.initial(formContext(), defaultPlate, today, initialEpochDay) }
    // Rajat, oletukset ja esikatselu lasketaan aina tuoreista tiedoista (lataus tai tuonti voi valmistua lomakkeen ollessa auki).
    val form = remember(edited, data.fillups, data.cars) { edited.withFillups(data.fillups, data.cars) }
    var confirmDelete by remember { mutableStateOf(false) }
    var photoEdit by rememberSaveable(existing?.id, saver = mutableStateSaver(photoEditSaver { data.storedPhotos })) {
        mutableStateOf(PhotoEdit(data.storedPhotos))
    }
    // Tallennetut kuvat aina tuoreina: palautuksessa ne latautuvat vasta lomakkeen jälkeen, ja tuonti voi lisätä kuvan.
    // photos on vain näyttöä ja tallennusta varten; muutokset luetaan photoEdit-tilasta (ks. PhotoSection alla).
    val photos = remember(photoEdit, data.storedPhotos) { photoEdit.withStored(data.storedPhotos) }
    // Kuvan käsittely kesken: tallennus odottaa, ettei juuri otettu kuva jää pois kirjauksesta.
    var photosBusy by remember { mutableStateOf(false) }
    // Jaettu kuva liittyy auki olevaan lomakkeeseen; lähtöarvo tilasta, ettei se kumoa käsittelyn aikana tehtyä muutosta.
    LaunchedEffect(incomingPhotos) {
        if (incomingPhotos.isNotEmpty()) photoEdit = takeIncomingPhotos().fold(photoEdit) { edit, file -> edit.add(file) }
    }
    // Uudet kuvat odottavat tallennusta; sulkeminen tai poisto hylkää ne, poistetuiksi merkityt tallennetut säilyvät.
    // Kiireisenä ei suljeta: hylkäys veisi tiedostot, joita tallennus on juuri siirtämässä kirjaukselle.
    val close = {
        if (!busy) {
            photoActions.discard(photos.discardOnClose)
            onDismiss()
        }
    }

    // Sähköautoa ladataan, kaasuautoa täytetään ja muita tankataan.
    val word = fillWord(form.kind)
    FormScreen(
        title = stringResource(
            if (existing == null) {
                word.pick(R.string.fuel_add, R.string.fuel_add_charge, R.string.fuel_add_gas)
            } else {
                word.pick(R.string.fuel_edit, R.string.fuel_edit_charge, R.string.fuel_edit_gas)
            }
        ),
        onClose = close,
        primaryLabel = stringResource(R.string.save),
        primaryEnabled = form.canSave && !photosBusy && !busy && (existing == null || !edited.isUnchanged(original) || photoEdit.changed),
        // Poistettujen uusien kuvien hylkäys tehdään vasta onnistuneen tallennuksen jälkeen, jotta auki jäävä lomake
        // ja Kumoa toimivat.
        onPrimary = { form.entry()?.let { entry -> onSave(entry, photos) } },
        topAction = if (existing != null) {
            {
                TextButton(onClick = Tap { confirmDelete = true }, enabled = !busy, colors = AppButtonDefaults.textColors()) {
                    Text(stringResource(R.string.fuel_delete), color = if (busy) Color.Unspecified else MaterialTheme.colorScheme.error)
                }
            }
        } else {
            null
        },
        primaryError = (save as? FillupSave.Failed)?.let { stringResource(it.textRes) },
    ) {
        FormSection(stringResource(R.string.fuel_section_car)) {
            PlateChips(form.plate, { edited = form.withPlate(it) }, knownPlates, stringResource(R.string.fuel_plate))
        }
        FormSection(stringResource(R.string.fuel_section_kind)) {
            // Viisi lajia ei mahdu yhdelle napparivillä: chipit rivittyvät.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FuelKind.entries.forEach { kind ->
                    AppFilterChip(
                        selected = form.kind == kind,
                        onClick = Tap { edited = form.withKind(kind) },
                        label = { Text(fuelKindName(kind)) },
                    )
                }
            }
        }
        // Lukemien rajat tulevat saman lajin kirjauksista.
        FormSection(stringResource(R.string.fuel_section_day_odometer)) {
            DateField(
                form.epochDay,
                { edited = form.withDay(it) },
                stringResource(R.string.fuel_date_label),
                maxEpochDay = today,
                modifier = Modifier.fillMaxWidth(),
            )
            OdometerField(
                form.km,
                { edited = form.withKm(it) },
                stringResource(R.string.fuel_odometer),
                modifier = Modifier.fillMaxWidth(),
                isError = form.tooSmall || form.tooLarge,
            )
            val previousKm = form.previousKm
            val nextKm = form.nextKm
            when {
                form.tooSmall && previousKm != null -> HelperLine(
                    stringResource(
                        word.pick(R.string.fuel_km_too_small, R.string.fuel_km_too_small_charge, R.string.fuel_km_too_small_gas),
                        formatWhole(previousKm),
                    ),
                    HelperTone.ERROR,
                )
                form.tooLarge && nextKm != null -> HelperLine(
                    stringResource(
                        word.pick(R.string.fuel_km_too_large, R.string.fuel_km_too_large_charge, R.string.fuel_km_too_large_gas),
                        formatWhole(nextKm),
                    ),
                    HelperTone.ERROR,
                )
                previousKm != null ->
                    HelperLine(
                        stringResource(
                            word.pick(R.string.fuel_prev_fillup, R.string.fuel_prev_charge, R.string.fuel_prev_gas),
                            formatWhole(previousKm),
                        )
                    )
            }
        }
        FormSection(stringResource(word.pick(R.string.fuel_section_fillup, R.string.fuel_section_charge, R.string.fuel_section_gas))) {
            DecimalField(
                form.prices.liters,
                { edited = form.withLiters(it) },
                stringResource(
                    when (form.kind.unit) {
                        FuelUnit.LITER -> R.string.fuel_liters
                        FuelUnit.KILOGRAM -> R.string.fuel_amount_kg
                        FuelUnit.KILOWATT_HOUR -> R.string.fuel_amount_kwh
                    }
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = form.fullTank,
                        role = Role.Switch,
                        onValueChange = {
                            Tap.now()
                            edited = form.withFullTank(it)
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(word.pick(R.string.fuel_full_tank, R.string.fuel_full_charge, R.string.fuel_full_gas)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(word.pick(R.string.fuel_full_tank_desc, R.string.fuel_full_charge_desc, R.string.fuel_full_gas_desc)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = form.fullTank, onCheckedChange = null)
            }
            val symbol = currencySymbol(form.currency)
            // Kokonaishinta vasemmalla ja yksikköhinta oikealla: yksikköhinta syntyy määrästä ja kokonaishinnasta.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DecimalField(
                    form.prices.total,
                    { edited = form.withTotal(it) },
                    stringResource(R.string.fuel_total_in, symbol),
                    modifier = Modifier.weight(1f),
                    isError = form.prices.total.isNotBlank() && parseDecimal(form.prices.total) == null,
                )
                DecimalField(
                    form.prices.perLiter,
                    { edited = form.withPerLiter(it) },
                    stringResource(R.string.fuel_price_per_unit_in, symbol, unitSymbol(form.kind.unit)),
                    modifier = Modifier.weight(1f),
                    isError = form.prices.perLiter.isNotBlank() && parseDecimal(form.prices.perLiter) == null,
                )
            }
        }
        FormSection(stringResource(R.string.fuel_section_photos)) {
            PhotoSection(
                photos,
                // Muutos luetaan tilasta, ei koostuksen aikaisesta photos-arvosta: valitsimen kuvat valmistuvat
                // peräkkäin samassa korutiinissa samalla lambdalla, ja vanhentunut arvo laskisi jokaiselle
                // "vanha + kuva", jolloin vain viimeinen jäisi. JVM-testi ei huomaa tätä.
                update = { change -> photoEdit = change(photoEdit) },
                actions = photoActions,
                word = word,
                onBusy = { photosBusy = it },
            )
        }
        FormSection(stringResource(R.string.note_label)) {
            NoteField(form.note, { edited = form.withNote(it) }, stringResource(R.string.note_hint_fuel))
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            val preview = form.preview
            val partials = preview?.partialCount ?: 0
            val partialsRes = word.pick(R.plurals.fuel_preview_partials, R.plurals.fuel_preview_partials_charge, R.plurals.fuel_preview_partials_gas)
            val partialsText = if (partials > 0) " " + pluralStringResource(partialsRes, partials, partials) else ""
            val event = form.event
            val mergedText = if (event?.merged == true) {
                " " + stringResource(word.pick(R.string.fuel_preview_merged, R.string.fuel_preview_merged_charge, R.string.fuel_preview_merged_gas))
            } else {
                ""
            }
            val noneText = stringResource(word.pick(R.string.fuel_preview_none, R.string.fuel_preview_none_charge, R.string.fuel_preview_none_gas))
            Text(
                when {
                    // Myös saman lukeman täyteen kuuluva vajaa näyttää kerran välin.
                    preview != null -> stringResource(
                        R.string.fuel_preview_full,
                        formatConsumption(preview.consumption, preview.kind),
                        formatCostPerKm(preview.costPerKm, preview.currency),
                    ) + partialsText + mergedText
                    // Ketjun aloittava täysi kerta: sen määrät ovat lähtötaso, ja kulutus lasketaan seuraavasta.
                    event?.full == true && !form.tooSmall && !form.tooLarge -> noneText + mergedText
                    form.isPartial -> stringResource(
                        word.pick(R.string.fuel_preview_partial, R.string.fuel_preview_partial_charge, R.string.fuel_preview_partial_gas)
                    ) + mergedText
                    form.hasPrevious -> stringResource(
                        when (form.kind.unit) {
                            FuelUnit.KILOWATT_HOUR -> R.string.fuel_preview_hint_charge
                            FuelUnit.KILOGRAM -> R.string.fuel_preview_hint_kg
                            FuelUnit.LITER -> R.string.fuel_preview_hint
                        }
                    )
                    else -> noneText
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }

    if (confirmDelete) {
        // Poistetaan tallennettu kirjaus: sen laji, vaikka lomakkeen lajia olisi jo vaihdettu.
        val deletedKind = existing?.kind ?: form.kind
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    stringResource(
                        fillWord(deletedKind).pick(R.string.fuel_delete_confirm, R.string.fuel_delete_confirm_charge, R.string.fuel_delete_confirm_gas)
                    )
                )
            },
            confirmButton = {
                Button(colors = AppButtonDefaults.dangerColors(), 
                    onClick = Tap {
                        confirmDelete = false
                        photoActions.discard(photos.discardOnClose)
                        onDelete()
                    }
                ) { Text(stringResource(R.string.fuel_delete)) }
            },
            dismissButton = {
                TextButton(onClick = Tap { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Lajin nimi käyttöliittymässä. */
@Composable
fun fuelKindName(kind: FuelKind): String = stringResource(
    when (kind) {
        FuelKind.DIESEL -> R.string.fuel_section_diesel
        FuelKind.PETROL -> R.string.fuel_kind_petrol
        FuelKind.GAS -> R.string.fuel_kind_gas
        FuelKind.ELECTRIC -> R.string.fuel_kind_electric
        FuelKind.ADBLUE -> R.string.fuel_kind_adblue
    }
)
