package fi.tyomatka.ui.history.forms

import androidx.compose.runtime.saveable.SaverScope
import fi.tyomatka.data.db.FuelPhotoEntity
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoEditTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val receipt = FuelPhotoEntity(id = 1, fillupId = 7, fileName = "a.jpg", addedAtEpochSec = 10)
    private val pump = FuelPhotoEntity(id = 2, fillupId = 7, fileName = "b.jpg", addedAtEpochSec = 11)
    private val meter = FuelPhotoEntity(id = 3, fillupId = 7, fileName = "c.jpg", addedAtEpochSec = 12)
    private val screenshot = File("ruutu.jpg")
    private val display = File("naytto.jpg")
    private val sticker = File("tarra.jpg")

    @Test
    fun `lomake näyttää tallennetut ja uudet kuvat lisäysjärjestyksessä`() {
        val edit = PhotoEdit(listOf(receipt, pump)).add(screenshot)

        assertEquals(listOf(PhotoItem.Stored(receipt), PhotoItem.Stored(pump), PhotoItem.New(screenshot)), edit.shown)
        assertTrue(edit.removedIds.isEmpty())
        assertEquals(listOf(screenshot), edit.added)
        assertNull(edit.undoable)
    }

    @Test
    fun `poisto piilottaa tallennetun ja uuden kuvan ja jättää ne kumottaviksi`() {
        val edit = PhotoEdit(listOf(receipt, pump)).add(screenshot)
            .remove(PhotoItem.Stored(pump))
            .remove(PhotoItem.New(screenshot))

        assertEquals(listOf(PhotoItem.Stored(receipt)), edit.shown)
        assertEquals(setOf(2L), edit.removedIds)
        assertTrue(edit.added.isEmpty())
        assertTrue(edit.changed)
        assertEquals(PhotoItem.New(screenshot), edit.undoable)
        assertFalse(PhotoEdit(listOf(receipt)).changed)
    }

    @Test
    fun `kumoa palauttaa tallennetun kuvan paikalleen`() {
        val removed = PhotoEdit(listOf(receipt, pump, meter)).remove(PhotoItem.Stored(pump))
        assertEquals(PhotoItem.Stored(pump), removed.undoable)
        assertEquals(setOf(2L), removed.removedIds)

        val restored = removed.undo()

        assertEquals(listOf(PhotoItem.Stored(receipt), PhotoItem.Stored(pump), PhotoItem.Stored(meter)), restored.shown)
        assertTrue(restored.removedIds.isEmpty())
        assertNull(restored.undoable)
        assertFalse(restored.changed)
    }

    @Test
    fun `kumoa palauttaa uuden kuvan paikalleen`() {
        val removed = PhotoEdit(listOf(receipt)).add(screenshot).add(display).add(sticker).remove(PhotoItem.New(display))
        assertEquals(listOf(PhotoItem.Stored(receipt), PhotoItem.New(screenshot), PhotoItem.New(sticker)), removed.shown)
        assertEquals(listOf(display), removed.discardOnSave)

        val restored = removed.undo()

        assertEquals(
            listOf(PhotoItem.Stored(receipt), PhotoItem.New(screenshot), PhotoItem.New(display), PhotoItem.New(sticker)),
            restored.shown,
        )
        assertEquals(listOf(screenshot, display, sticker), restored.added)
        assertTrue(restored.discardOnSave.isEmpty())
        assertNull(restored.undoable)
    }

    @Test
    fun `kumoa koskee viimeksi poistettua kuvaa`() {
        val edit = PhotoEdit(listOf(receipt, pump)).add(screenshot)
            .remove(PhotoItem.Stored(receipt))
            .remove(PhotoItem.New(screenshot))
            .undo()

        assertEquals(listOf(PhotoItem.Stored(pump), PhotoItem.New(screenshot)), edit.shown)
        assertNull(edit.undoable)
        assertEquals(edit, edit.undo())
    }

    @Test
    fun `kuvan lisäys päättää kumoamisen`() {
        val edit = PhotoEdit(listOf(receipt)).add(screenshot).remove(PhotoItem.New(screenshot)).add(display)

        assertEquals(listOf(PhotoItem.Stored(receipt), PhotoItem.New(display)), edit.shown)
        assertNull(edit.undoable)
        assertEquals(edit, edit.undo())
        assertEquals(listOf(screenshot), edit.discardOnSave)
    }

    @Test
    fun `tallennuksessa hylätään poistetut uudet kuvat ja tallennetaan muut uudet`() {
        val edit = PhotoEdit(listOf(receipt, pump)).add(screenshot).add(display)
            .remove(PhotoItem.Stored(pump))
            .remove(PhotoItem.New(screenshot))

        assertEquals(listOf(display), edit.added)
        assertEquals(listOf(screenshot), edit.discardOnSave)
        assertEquals(setOf(2L), edit.removedIds)
    }

    @Test
    fun `sulkemisessa hylätään kaikki uudet kuvat`() {
        val edit = PhotoEdit(listOf(receipt, pump)).add(screenshot).add(display)
            .remove(PhotoItem.Stored(pump))
            .remove(PhotoItem.New(screenshot))

        assertEquals(listOf(screenshot, display), edit.discardOnClose)
        assertTrue(PhotoEdit(listOf(receipt)).remove(PhotoItem.Stored(receipt)).discardOnClose.isEmpty())
    }

    private fun save(edit: PhotoEdit, stored: List<FuelPhotoEntity> = edit.stored) =
        assertNotNull(with(photoEditSaver { stored }) { SaverScope { true }.save(edit) })

    @Test
    fun `kuvamuutokset palautuvat prosessin yli samana tilana`() {
        val shot = tmp.newFile("ruutu.jpg")
        val gone = tmp.newFile("poistettu.jpg")
        val before = PhotoEdit(listOf(receipt, pump)).add(shot).add(gone)
            .remove(PhotoItem.Stored(pump))
            .remove(PhotoItem.New(gone))

        assertEquals(before, photoEditSaver { listOf(receipt, pump) }.restore(save(before)))
        // Tallennetun kuvan poisto Kumoa-ilmoituksineen palautuu, vaikka rivi tulee tuoreena.
        val undoable = PhotoEdit(listOf(receipt, pump)).remove(PhotoItem.Stored(receipt))
        assertEquals(undoable, photoEditSaver { listOf(receipt, pump) }.restore(save(undoable)))
        assertEquals(PhotoEdit(listOf(receipt)), photoEditSaver { listOf(receipt) }.restore(save(PhotoEdit(listOf(receipt)))))
    }

    @Test
    fun `tallennetut kuvat tulevat palautuksessa tuoreina`() {
        // Kuvarivit latautuvat palautuksessa vasta lomakkeen jälkeen: aluksi niitä ei ole, sitten ne täydentyvät.
        val before = PhotoEdit(listOf(receipt, pump)).remove(PhotoItem.Stored(pump))
        val saved = save(before)

        val restored = assertNotNull(photoEditSaver { emptyList() }.restore(saved))

        assertTrue(restored.shown.isEmpty())
        assertEquals(setOf(2L), restored.removedIds)
        assertEquals(before, restored.withStored(listOf(receipt, pump)))
        // Tuonti toi lomakkeen aikana kuvan: se näkyy, eikä sitä poisteta.
        assertEquals(listOf(PhotoItem.Stored(receipt), PhotoItem.Stored(meter)), restored.withStored(listOf(receipt, pump, meter)).shown)
    }

    @Test
    fun `uusi kuva, jonka tiedosto puuttuu, jää palautuksessa pois`() {
        val shot = tmp.newFile("ruutu.jpg")
        val lost = tmp.newFile("kadonnut.jpg")
        val before = PhotoEdit(listOf(receipt)).add(shot).add(lost).remove(PhotoItem.New(lost))
        val saved = save(before)
        lost.delete()

        val restored = assertNotNull(photoEditSaver { listOf(receipt) }.restore(saved))

        assertEquals(listOf(PhotoItem.Stored(receipt), PhotoItem.New(shot)), restored.shown)
        assertEquals(listOf(shot), restored.newFiles)
        assertTrue(restored.removedFiles.isEmpty())
        assertNull(restored.undoable)
        // Käyttäjän itse poistama kuva ei ole kadonnut.
        assertEquals(0, restored.lostCount)
        assertNull(photoEditSaver { emptyList() }.restore(listOf("rikki")))
    }

    @Test
    fun `palautuksessa kadonneet kuvat lasketaan eikä lisäys päätä ilmoitusta`() {
        val shot = tmp.newFile("ruutu.jpg")
        val lost = tmp.newFile("kadonnut.jpg")
        val lostToo = tmp.newFile("kadonnut2.jpg")
        val before = PhotoEdit(listOf(receipt)).add(shot).add(lost).add(lostToo)
        val saved = save(before)
        lost.delete()
        lostToo.delete()

        val restored = assertNotNull(photoEditSaver { listOf(receipt) }.restore(saved))

        assertEquals(2, restored.lostCount)
        assertEquals(listOf(PhotoItem.Stored(receipt), PhotoItem.New(shot)), restored.shown)
        // Poisto, kumoa ja lisäys eivät päätä ilmoitusta: palautuksessa odottava kameratulos lisää kuvan heti, ennen
        // kuin kuljettaja ehtii lukea ilmoituksen.
        assertEquals(2, restored.remove(PhotoItem.New(shot)).undo().lostCount)
        assertEquals(2, restored.add(display).lostCount)
        assertEquals(listOf(PhotoItem.Stored(receipt), PhotoItem.New(shot), PhotoItem.New(display)), restored.add(display).shown)
        // Kuittaus päättää ilmoituksen eikä koske kuviin.
        val acknowledged = restored.add(display).acknowledgeLost()
        assertEquals(0, acknowledged.lostCount)
        assertEquals(restored.add(display).shown, acknowledged.shown)
        assertEquals(acknowledged, acknowledged.acknowledgeLost())
        assertEquals(0, PhotoEdit(listOf(receipt)).lostCount)
    }

    @Test
    fun `kadonneiden kuvien määrä säilyy aktiviteetin uudelleenluonnin yli ja nollautuu kuittauksesta`() {
        val shot = tmp.newFile("ruutu.jpg")
        val lost = tmp.newFile("kadonnut.jpg")
        val saved = save(PhotoEdit(listOf(receipt)).add(shot).add(lost))
        lost.delete()
        val restored = assertNotNull(photoEditSaver { listOf(receipt) }.restore(saved))
        assertEquals(1, restored.lostCount)

        // Fonttikoon, teeman tai kielen vaihto luo aktiviteetin uudelleen: ilmoitus ei saa kadota ilman kuittausta.
        val recreated = assertNotNull(photoEditSaver { listOf(receipt) }.restore(save(restored)))
        assertEquals(1, recreated.lostCount)
        assertEquals(restored, recreated)
        // Kuittaus nollaa määrän myös tallenteessa.
        assertEquals(0, assertNotNull(photoEditSaver { listOf(receipt) }.restore(save(recreated.acknowledgeLost()))).lostCount)
        // Uudelleenluonnissa kadonneet lisätään aiempiin.
        shot.delete()
        val lostMore = assertNotNull(photoEditSaver { listOf(receipt) }.restore(save(recreated)))
        assertEquals(2, lostMore.lostCount)
        assertEquals(listOf(PhotoItem.Stored(receipt)), lostMore.shown)
    }

    @Test
    fun `vanha nelialkioinen tallenne palautuu`() {
        val shot = tmp.newFile("ruutu.jpg")
        val before = PhotoEdit(listOf(receipt, pump)).add(shot).remove(PhotoItem.Stored(pump))
        val old = listOf(listOf(2L), listOf(shot.path), emptyList<String>(), listOf("stored", 2L, 7L, "b.jpg", 11L, pump.backupId))

        assertEquals(before, photoEditSaver { listOf(receipt, pump) }.restore(old))
        // Ilman tallennettua määrää kadonneet lasketaan vain nyt puuttuvista tiedostoista.
        shot.delete()
        val restored = assertNotNull(photoEditSaver { listOf(receipt, pump) }.restore(old))
        assertEquals(1, restored.lostCount)
        assertEquals(before.copy(newFiles = emptyList(), lostCount = 1), restored)
    }
}
