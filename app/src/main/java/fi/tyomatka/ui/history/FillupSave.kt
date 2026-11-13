package fi.tyomatka.ui.history

import fi.tyomatka.R
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.photo.PhotoApplyResult
import fi.tyomatka.ui.history.forms.fillWord

/**
 * Kirjauksen tallennuksen vaihe lomakkeelle: kesken (Tallenna pois käytöstä), epäonnistui (lomake jää auki
 * syötteineen ja kertoo virheen) tai valmis (lomake sulkeutuu). Lomake sulkeutuu vasta tallennuksen jälkeen, ettei
 * epäonnistunut tallennus hävitä syötettyjä tietoja ja kuvia.
 */
sealed interface FillupSave {
    data object Saving : FillupSave
    data class Failed(val textRes: Int) : FillupSave
    data object Done : FillupSave
}

/**
 * Lomake on kiireinen: tallennus on kesken tai valmis mutta lomake ei ole vielä sulkeutunut. Tallenna, sulkeminen ja
 * Poista ovat silloin pois käytöstä, ja ViewModel hylkää uuden tallennuksen. Done-vaiheen kehyksessä lomake piirtyy
 * vielä kerran ennen sulkeutumista, ja pelkkä Saving-tarkistus päästi tuplanapautuksen toiseksi kirjaukseksi.
 * Uusi tallennus sallitaan vain, kun lomake ei ole kiireinen.
 */
val FillupSave?.busy: Boolean
    get() = this == FillupSave.Saving || this == FillupSave.Done

/**
 * Kuittaus (lomake sulkeutui tai otti vaiheen vastaan) nollaa valmiin ja epäonnistuneen vaiheen. Kesken olevaa
 * tallennusta ei kuitata, ettei kuittaus nollaa toisen tallennuksen vaihetta ja päästä lomaketta vapaaksi kesken kaiken.
 */
fun FillupSave?.consumed(): FillupSave? = if (this == FillupSave.Saving) this else null

/** Kirjauksen tallennusvirhe kirjauksen sanalla: tankkausta, latausta tai täyttöä ei voitu tallentaa. */
fun fillupSaveFailed(kind: FuelKind): FillupSave.Failed =
    FillupSave.Failed(fillWord(kind).pick(R.string.fuel_save_failed, R.string.fuel_save_failed_charge, R.string.fuel_save_failed_gas))

/** Kuvien ilmoitus Työmatka-sivulle: monikkotekstin tunniste ja tallentamatta jääneiden määrä. */
data class PhotoSaveNotice(val pluralRes: Int, val count: Int)

/**
 * Kirjaus tallentui, mutta kuva jäi tallentamatta: ilmoitus kertoo määrän ja, jos lomakkeella merkittiin kuvia
 * poistettaviksi, että ne säilyivät ([FillupPhotoRepository.apply] ei poista mitään, kun uusi kuva jäi tallentamatta).
 * Null, kun kaikki uudet kuvat tallentuivat.
 */
fun photoSaveNotice(result: PhotoApplyResult, removals: Int): PhotoSaveNotice? = when {
    result.failed == 0 -> null
    removals > 0 -> PhotoSaveNotice(R.plurals.photo_save_failed_kept, result.failed)
    else -> PhotoSaveNotice(R.plurals.photo_save_failed, result.failed)
}
