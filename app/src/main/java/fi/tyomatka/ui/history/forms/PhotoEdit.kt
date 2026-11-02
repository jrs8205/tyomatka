package fi.tyomatka.ui.history.forms

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import fi.tyomatka.data.db.FuelPhotoEntity
import java.io.File

/** Lomakkeen kuva: tallennettu tai tällä kerralla lisätty (odottava tiedosto). */
sealed interface PhotoItem {
    data class Stored(val photo: FuelPhotoEntity) : PhotoItem
    data class New(val file: File) : PhotoItem
}

/**
 * Lomakkeen kuvat: tallennettujen poistot ja uudet odottavat kuvat viedään kirjaukseen vasta tallennettaessa, joten
 * sulkeminen ilman tallennusta jättää kirjauksen kuvat ennalleen. Poisto on kumottavissa, kunnes käyttäjä lisää kuvan
 * tai lomake suljetaan; siksi poistetun uuden kuvan tiedosto säilyy siihen asti.
 */
data class PhotoEdit(
    val stored: List<FuelPhotoEntity>,
    val removedIds: Set<Long> = emptySet(),
    /** Tällä kerralla lisätyt tiedostot lisäysjärjestyksessä, myös poistetut: kumoaminen palauttaa kuvan paikalleen. */
    val newFiles: List<File> = emptyList(),
    val removedFiles: Set<File> = emptySet(),
    /** Viimeksi poistettu kuva, jonka Kumoa palauttaa; null, kun ilmoitusta ei näytetä. */
    val undoable: PhotoItem? = null,
    /**
     * Palautuksessa kadonneet uudet kuvat (välimuisti siivottu): kuljettaja ehtii ottaa kuvan uudelleen, jos saa tietää.
     * Ilmoitus näkyy, kunnes käyttäjä kuittaa sen tai lomake suljetaan; määrä säilyy tallenteessa, ettei fonttikoon,
     * teeman tai kielen vaihdon aiheuttama uudelleenluonti hävitä ilmoitusta kuittaamatta.
     */
    val lostCount: Int = 0,
) {
    /** Näytettävät kuvat lisäysjärjestyksessä: tallennetut ensin, sitten uudet. */
    val shown: List<PhotoItem> get() = stored.filter { it.id !in removedIds }.map(PhotoItem::Stored) + added.map(PhotoItem::New)

    /** Uudet kuvat, jotka tallennetaan kirjaukselle. */
    val added: List<File> get() = newFiles.filter { it !in removedFiles }

    /** Tallennuksessa hylättävät tiedostot: poistetut uudet kuvat. */
    val discardOnSave: List<File> get() = newFiles.filter { it in removedFiles }

    /** Tallentamatta suljettaessa hylättävät tiedostot: kaikki uudet kuvat, myös poistetut. */
    val discardOnClose: List<File> get() = newFiles

    val changed: Boolean get() = removedIds.isNotEmpty() || added.isNotEmpty()

    /** Tallennetut kuvat tuoreina: palautuksessa ne latautuvat vasta lomakkeen jälkeen, ja tuonti voi lisätä kuvan. */
    fun withStored(stored: List<FuelPhotoEntity>) = if (stored == this.stored) this else copy(stored = stored)

    /**
     * Lisäys päättää edellisen poiston kumoamisen, ei kadonneiden kuvien ilmoitusta: palautuksessa odottava kameratulos
     * lisää kuvan heti, ennen kuin kuljettaja ehtii lukea ilmoituksen.
     */
    fun add(file: File) = copy(newFiles = newFiles + file, undoable = null)

    /** Käyttäjä kuittasi kadonneiden kuvien ilmoituksen. */
    fun acknowledgeLost() = if (lostCount == 0) this else copy(lostCount = 0)

    fun remove(item: PhotoItem) = when (item) {
        is PhotoItem.Stored -> copy(removedIds = removedIds + item.photo.id, undoable = item)
        is PhotoItem.New -> copy(removedFiles = removedFiles + item.file, undoable = item)
    }

    /** Viimeksi poistettu kuva palaa paikalleen; ilman poistoa ei muutosta. */
    fun undo() = when (val item = undoable) {
        null -> this
        is PhotoItem.Stored -> copy(removedIds = removedIds - item.photo.id, undoable = null)
        is PhotoItem.New -> copy(removedFiles = removedFiles - item.file, undoable = null)
    }
}

/**
 * Kuvamuutokset prosessin yli (Android voi lopettaa prosessin kameran ollessa auki): poistot,
 * uudet odottavat tiedostot, poistetut uudet, kumottava poisto ja kadonneiden määrä. Tallennetut kuvat tulevat tuoreina
 * [stored]-funktiosta ja myöhemmin [PhotoEdit.withStored]-kutsulla. Uusi kuva, jonka tiedosto puuttuu (välimuisti
 * siivottu), jää pois, ja lomakkeella näkyneet kadonneet lisätään [PhotoEdit.lostCount]-kenttään aiemmin tallennetun
 * määrän päälle; käyttäjän itse poistama ei ole kadonnut. Ilman tallennettua määrää (vanhempi nelialkioinen tallenne)
 * aiempia kadonneita ei ole. Kelvoton tallenne (toisen version jäämä) antaa null, jolloin lomake alkaa ilman muutoksia.
 */
fun photoEditSaver(stored: () -> List<FuelPhotoEntity>): Saver<PhotoEdit, Any> = listSaver(
    save = { edit ->
        val undo: List<Any?>? = when (val item = edit.undoable) {
            null -> null
            is PhotoItem.Stored -> with(item.photo) { listOf(UNDO_STORED, id, fillupId, fileName, addedAtEpochSec, backupId) }
            is PhotoItem.New -> listOf(UNDO_NEW, item.file.path)
        }
        listOf(edit.removedIds.toList(), edit.newFiles.map { it.path }, edit.removedFiles.map { it.path }, undo, edit.lostCount)
    },
    restore = { saved ->
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val savedFiles = (saved[1] as List<String>).map(::File)
            @Suppress("UNCHECKED_CAST")
            val savedRemoved = (saved[2] as List<String>).map(::File).toSet()
            val newFiles = savedFiles.filter { it.isFile }
            val removedFiles = savedRemoved.filter { it in newFiles }.toSet()
            val lost = (saved.getOrNull(4) as? Int ?: 0) + savedFiles.count { it !in newFiles && it !in savedRemoved }
            val undo = saved[3] as List<Any?>?
            val undoable = when (undo?.get(0)) {
                UNDO_STORED -> PhotoItem.Stored(
                    FuelPhotoEntity(undo[1] as Long, undo[2] as Long, undo[3] as String, undo[4] as Long, undo[5] as String)
                )
                UNDO_NEW -> File(undo[1] as String).takeIf { it in newFiles }?.let(PhotoItem::New)
                else -> null
            }
            @Suppress("UNCHECKED_CAST")
            PhotoEdit(stored(), (saved[0] as List<Long>).toSet(), newFiles, removedFiles, undoable, lost)
        }.getOrNull()
    },
)

private const val UNDO_STORED = "stored"
private const val UNDO_NEW = "new"
