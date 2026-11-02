package fi.tyomatka.ui.history

import fi.tyomatka.R
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.photo.PhotoApplyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FillupSaveTest {
    private val none: FillupSave? = null
    private val failed = FillupSave.Failed(R.string.fuel_save_failed)

    @Test
    fun `lomake on kiireinen tallennuksen ajan ja kunnes valmis vaihe on kuitattu`() {
        // Done-vaiheen kehyksessä lomake on vielä auki: Tallenna ei saa aktivoitua, ettei kirjauksesta tule tuplaa.
        assertTrue(FillupSave.Saving.busy)
        assertTrue(FillupSave.Done.busy)
        assertFalse(none.busy)
        assertFalse(failed.busy)
    }

    @Test
    fun `kuittaus nollaa valmiin ja epäonnistuneen vaiheen muttei kesken olevaa tallennusta`() {
        assertNull(FillupSave.Done.consumed())
        assertNull(failed.consumed())
        assertNull(none.consumed())
        assertEquals(FillupSave.Saving, FillupSave.Saving.consumed())
    }

    @Test
    fun `kirjauksen tallennusvirhe kerrotaan kirjauksen sanalla`() {
        assertEquals(FillupSave.Failed(R.string.fuel_save_failed), fillupSaveFailed(FuelKind.DIESEL))
        assertEquals(FillupSave.Failed(R.string.fuel_save_failed), fillupSaveFailed(FuelKind.ADBLUE))
        assertEquals(FillupSave.Failed(R.string.fuel_save_failed_charge), fillupSaveFailed(FuelKind.ELECTRIC))
        assertEquals(FillupSave.Failed(R.string.fuel_save_failed_gas), fillupSaveFailed(FuelKind.GAS))
    }

    @Test
    fun `kuvista ei ilmoiteta, kun kaikki uudet tallentuivat`() {
        assertNull(photoSaveNotice(PhotoApplyResult(saved = 2, failed = 0), removals = 1))
        assertNull(photoSaveNotice(PhotoApplyResult(saved = 0, failed = 0), removals = 0))
    }

    @Test
    fun `tallentamatta jäänyt kuva kerrotaan ja aiempien säilyminen mainitaan vain kun poistoja oli`() {
        assertEquals(PhotoSaveNotice(R.plurals.photo_save_failed, 1), photoSaveNotice(PhotoApplyResult(saved = 0, failed = 1), removals = 0))
        assertEquals(PhotoSaveNotice(R.plurals.photo_save_failed_kept, 2), photoSaveNotice(PhotoApplyResult(saved = 1, failed = 2), removals = 1))
    }
}
