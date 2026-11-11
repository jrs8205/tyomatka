package fi.tyomatka.ui.history.forms

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.data.photo.sampleSize
import fi.tyomatka.ui.FullScreenDialog
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.form.HelperLine
import fi.tyomatka.ui.form.HelperTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import fi.tyomatka.ui.components.AppButtonDefaults
import androidx.compose.material3.Button

/** Kuvien toiminnot lomakkeelle; toteutus on ViewModelissa (kuvan pakkaus ja tiedostot). */
interface PhotoActions {
    /** Tallennetun kuvan tiedosto. */
    fun storedFile(fileName: String): File

    /** Pakkaa kuvan (kamera, valitsin tai jako) odottavaksi tiedostoksi; heittää, jos kuvaa ei voi lukea. */
    suspend fun prepare(source: Uri): File

    /** Kameran raakakuvan tiedosto ja sen osoite kamerasovellukselle. */
    fun newCapture(): Pair<File, Uri>

    /** Hylkää odottavat kuvat (lomake suljettiin tallentamatta). */
    fun discard(files: List<File>)
}

/** Kuvavalitsimen enimmäismäärä yhdellä kertaa. */
private const val MAX_PICK = 10

/** Pienoiskuvan purun pitkä sivu: riittää 96 dp:n ruutuun tiheälläkin näytöllä. */
private const val THUMB_MAX_SIDE = 512

/** Katselun purun pitkä sivu: tallennettu kuva (enintään 2048 px) puretaan täydessä koossa. */
private const val VIEW_MAX_SIDE = 4096

/**
 * Kirjauksen kuvat: kuitti tai mittarin näyttö kamerasta, latausapin kuvakaappaus valitsimesta.
 * Muutokset kulkevat [update]n kautta, koska kuvan käsittely valmistuu vasta kameran tai valitsimen jälkeen, eikä
 * peräkkäin valmistuva käsittely saa hukata toistensa kuvia. [onBusy] kertoo, onko käsittely kesken (tallennus odottaa).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoSection(
    edit: PhotoEdit,
    update: ((PhotoEdit) -> PhotoEdit) -> Unit,
    actions: PhotoActions,
    word: FillWord,
    onBusy: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var processing by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<Int?>(null) }
    var viewing by remember { mutableStateOf<PhotoItem?>(null) }
    // Pienoiskuvan poistonapista valittu kuva, jonka poisto odottaa vahvistusta.
    var removing by remember { mutableStateOf<PhotoItem?>(null) }
    // Kameran raakakuvan polku säilyy, vaikka näkymä luotaisiin uudelleen tai prosessi lopetettaisiin kameran ollessa
    // auki: dialogi tallentaa sisältönsä tilan omalla tunnisteellaan, ja alla oleva kameran käynnistin saa samasta
    // tallenteesta avaimensa, jolla kameran tulos löytää sen. Lomakkeen muu tila palautuu dialogin ulkopuolelta.
    var capture by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(processing > 0) { onBusy(processing > 0) }

    fun prepareAll(sources: List<Uri>, cleanup: () -> Unit = {}) {
        if (sources.isEmpty()) return cleanup()
        error = null
        processing += sources.size
        scope.launch {
            try {
                sources.forEach { source ->
                    // Lukukelvoton kuva (tai muistin loppuminen) antaa virheen eikä kaada lomaketta.
                    val file = try {
                        actions.prepare(source)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                    processing -= 1
                    if (file == null) error = R.string.photo_failed else update { it.add(file) }
                }
            } finally {
                cleanup()
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = capture?.let(::File) ?: return@rememberLauncherForActivityResult
        capture = null
        // Raakakuva poistetaan pakkauksen jälkeen; peruutettu kuvaus poistaa sen heti.
        if (taken) prepareAll(listOf(Uri.fromFile(file))) { file.delete() } else file.delete()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        prepareAll(uris)
    }

    val shown = edit.shown
    if (shown.isNotEmpty() || processing > 0) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEachIndexed { index, item ->
                val file = item.file(actions)
                // Avain tiedostosta: poisto ei pura jäljelle jääviä pienoiskuvia uudelleen.
                key(file.path) {
                    PhotoThumbnail(
                        file = file,
                        description = stringResource(R.string.photo_open, index + 1, shown.size),
                        removeDescription = stringResource(R.string.photo_remove, index + 1, shown.size),
                        onClick = { viewing = item },
                        onRemove = { removing = item },
                    )
                }
            }
            repeat(processing) {
                Box(Modifier.size(THUMB).clip(THUMB_SHAPE).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                }
            }
        }
    }
    if (edit.undoable != null) {
        PhotoRemovedNotice(onUndo = { update { it.undo() } })
    }
    // Palautuksessa kadonnut kuva: kuljettaja ehtii ottaa sen uudelleen, kun saa tietää. Ilmoitus
    // pysyy kuittaukseen asti, koska odottava kameratulos lisää kuvan heti palautuksen jälkeen.
    if (edit.lostCount > 0) {
        PhotoLostNotice(edit.lostCount, onAcknowledge = { update { it.acknowledgeLost() } })
    }
    val context = LocalContext.current
    // Kameranappi vain, kun jokin sovellus vastaa kuvauspyyntöön: kaikki kamerasovellukset eivät vastaa
    // IMAGE_CAPTUREen, jolloin jää pelkkä valitsin. Manifestin <queries> sallii tarkistuksen Android 11:stä.
    val cameraAvailable = remember { Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (cameraAvailable) OutlinedButton(
            onClick = Tap {
                val (file, uri) = actions.newCapture()
                capture = file.path
                try {
                    camera.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    capture = null
                    file.delete()
                    error = R.string.photo_no_camera
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.photo_take))
        }
        OutlinedButton(
            onClick = Tap { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.photo_import))
        }
    }
    val errorRes = error
    if (errorRes != null) {
        HelperLine(stringResource(errorRes), HelperTone.ERROR)
    } else {
        HelperLine(stringResource(word.pick(R.string.photo_hint, R.string.photo_hint_charge, R.string.photo_hint_gas)))
    }

    viewing?.let { item ->
        PhotoViewer(
            file = item.file(actions),
            onDelete = {
                // Tiedosto säilyy kumoamista varten; poistetut uudet hylätään vasta tallennuksessa tai sulkemisessa.
                update { it.remove(item) }
                viewing = null
            },
            onClose = { viewing = null },
        )
    }
    removing?.let { item ->
        // Sama vahvistus ja poistopolku kuin kuvanäkymässä, joten "Kuva poistettu." ja Kumoa toimivat samoin.
        PhotoDeleteConfirmDialog(
            onConfirm = {
                update { it.remove(item) }
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

/** Poiston ilmoitus Kumoa-napilla: katselu sulkeutuu poistoon, joten lomake kertoo, mitä tapahtui. */
@Composable
private fun PhotoRemovedNotice(onUndo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            HelperLine(stringResource(R.string.photo_deleted))
        }
        TextButton(onClick = Tap(onUndo)) { Text(stringResource(R.string.photo_undo)) }
    }
}

/** Kadonneiden kuvien ilmoitus Selvä-napilla: näkyy, kunnes käyttäjä kuittaa sen tai lomake suljetaan. */
@Composable
private fun PhotoLostNotice(count: Int, onAcknowledge: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            HelperLine(pluralStringResource(R.plurals.photo_lost, count, count), HelperTone.ERROR)
        }
        TextButton(onClick = Tap(onAcknowledge)) { Text(stringResource(R.string.photo_lost_ok)) }
    }
}

private val THUMB = 96.dp
private val THUMB_SHAPE = RoundedCornerShape(8.dp)

private fun PhotoItem.file(actions: PhotoActions): File = when (this) {
    is PhotoItem.Stored -> actions.storedFile(photo.fileName)
    is PhotoItem.New -> file
}

/** Poistonapin siirto pienoiskuvan kulman yli: kosketusalue (48 dp) ulottuu reunan yli, muttei viereiseen kuvaan (väli 8 dp). */
private val REMOVE_OFFSET = 6.dp

/** Poistonapin kosketusalue todellisin rajoin: laajennettu vähimmäisalue häviäisi kuvan omalle osumalle. */
private val REMOVE_TARGET = 48.dp

/** Poistonapin painalluksen säde: pyöreän pohjan (32 dp) ympärille, ei koko kosketusalueen neliö. */
private val REMOVE_RIPPLE_RADIUS = 20.dp

/**
 * Pienoiskuva: napautus avaa kuvan, ja oikean yläkulman poistonappi on oma kohteensa. Kuva leikataan
 * omassa laatikossaan, jotta ulompi laatikko ei leikkaa reunan yli ulottuvaa nappia.
 */
@Composable
private fun PhotoThumbnail(
    file: File,
    description: String,
    removeDescription: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val load = rememberPhoto(file, maxSide = THUMB_MAX_SIDE)
    Box(Modifier.size(THUMB)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(THUMB_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = Tap(onClick)),
        ) {
            when (load) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                PhotoLoad.Missing -> Icon(
                    Icons.Filled.BrokenImage,
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                is PhotoLoad.Loaded -> Image(load.bitmap, description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = REMOVE_OFFSET, y = -REMOVE_OFFSET)
                .size(REMOVE_TARGET)
                // Painallus näkyy pyöreänä napin kokoisena, ei koko kosketusalueen neliönä kuvan päällä.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = REMOVE_RIPPLE_RADIUS),
                    role = Role.Button,
                    onClick = Tap(onRemove),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Tumma pyöreä pohja erottaa kuvakkeen vaaleankin kuitin päältä.
            Box(
                Modifier.size(32.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = removeDescription, tint = Color.White)
            }
        }
    }
}

/** Poiston vahvistus kuvanäkymässä ja pienoiskuvassa, ettei kuva katoa vahingossa ajon aikana. */
@Composable
private fun PhotoDeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_delete_confirm)) },
        confirmButton = {
            Button(colors = AppButtonDefaults.dangerColors(), onClick = Tap(onConfirm)) { Text(stringResource(R.string.photo_delete_button)) }
        },
        dismissButton = {
            TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Kuva koko näytölle mustalla pohjalla: sormin zoomattava ja siirrettävä, kaksoisnapautus vaihtaa suurennoksen. */
@Composable
private fun PhotoViewer(file: File, onDelete: () -> Unit, onClose: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    FullScreenDialog(onDismissRequest = onClose) {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                var zoom by remember { mutableStateOf(PhotoZoom()) }
                when (val load = rememberPhoto(file, maxSide = VIEW_MAX_SIDE)) {
                    null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    PhotoLoad.Missing -> Icon(
                        Icons.Filled.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center).size(48.dp),
                    )
                    is PhotoLoad.Loaded -> Image(
                        load.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { zoom = zoom.doubleTapped() })
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, pinch, _ ->
                                    // Siirtorajat sovitetun kuvan mitoista: Fit jättää tyhjää, jonka verran kuvaa ei saa vetää.
                                    val fitted = fittedSize(load.bitmap.width, load.bitmap.height, size.width, size.height)
                                    zoom = zoom.pinched(pinch, pan.x, pan.y, fitted, size.width, size.height)
                                }
                            }
                            .graphicsLayer(scaleX = zoom.scale, scaleY = zoom.scale, translationX = zoom.x, translationY = zoom.y),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
                ) {
                    IconButton(onClick = Tap(onClose)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.photo_close), tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = Tap { confirmDelete = true }) {
                        Text(stringResource(R.string.photo_delete), color = Color.White)
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        PhotoDeleteConfirmDialog(
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Kuvan lataus taustalla: null = kesken. */
private sealed interface PhotoLoad {
    data object Missing : PhotoLoad
    data class Loaded(val bitmap: ImageBitmap) : PhotoLoad
}

@Composable
private fun rememberPhoto(file: File, maxSide: Int): PhotoLoad? {
    val load by produceState<PhotoLoad?>(null, file, maxSide) {
        value = withContext(Dispatchers.IO) { decodePhoto(file, maxSide) }?.let(PhotoLoad::Loaded) ?: PhotoLoad.Missing
    }
    return load
}

/** Purku pienennettynä ([sampleSize]), ettei pienoiskuva pura täysikokoista kuvaa muistiin; puuttuva tai rikkinäinen on null. */
private fun decodePhoto(file: File, maxSide: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}.getOrNull()
