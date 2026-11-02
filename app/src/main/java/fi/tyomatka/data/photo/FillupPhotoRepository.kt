package fi.tyomatka.data.photo

import fi.tyomatka.data.db.FuelPhotoDao
import fi.tyomatka.data.db.FuelPhotoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Lomakkeen kuvien tallennuksen tulos: [failed] uutta kuvaa jäi tallentamatta, jolloin poistoja ei tehty. */
data class PhotoApplyResult(val saved: Int, val failed: Int)

/**
 * Tankkauksen, latauksen ja täytön kuvat (kuitti tai mittarin näyttö todisteeksi). Rivit ja
 * tiedostot muuttuvat yhden lukon alla, jottei siivous näe juuri siirrettyä tiedostoa ilman riviä.
 */
@Singleton
class FillupPhotoRepository @Inject constructor(
    private val dao: FuelPhotoDao,
    private val store: PhotoStore,
) {
    private val mutex = Mutex()

    /** Kaikki kuvat lisäysjärjestyksessä. */
    fun observeAll(): Flow<List<FuelPhotoEntity>> = dao.observeAll()

    /** Kaikki kuvat lisäysjärjestyksessä (vienti). */
    suspend fun all(): List<FuelPhotoEntity> = dao.all()

    /**
     * Varmuuskopion kuva kirjaukselle: odottava tiedosto kuvakansioon, ellei sama kuva (tunniste) ole jo puhelimessa.
     * Palauttaa true, kun kuva lisättiin.
     */
    suspend fun restore(fillupId: Long, pending: File, addedAtEpochSec: Long, backupId: String): Boolean = mutex.withLock {
        if (dao.byBackupId(backupId) != null) return@withLock false
        dao.insert(FuelPhotoEntity(fillupId = fillupId, fileName = store.commit(pending), addedAtEpochSec = addedAtEpochSec, backupId = backupId))
        true
    }

    /**
     * Lomakkeen tallennus: odottavat kuvat [added] siirtyvät kuvakansioon omille riveilleen, ja kirjauksen omat kuvat,
     * jotka lomakkeella poistettiin ([removedIds]), poistuvat (rivi ensin, sitten tiedosto). Poistot ovat nimenomaisia,
     * koska lomake ei tiedä sen aikana tuoduista kuvista, eivätkä ne saa kadota.
     *
     * Puuttuva tai siirtymätön odottava kuva (Android siivosi välimuistin) ei heitä: se ohitetaan ja tulos kertoo sen.
     * Poistot tehdään vasta, kun kaikki uudet tallentuivat: käyttäjä voi olla vaihtamassa epäselvää kuittikuvaa
     * uuteen, eikä vanha saa kadota, jos uusi ei tallentunut.
     */
    suspend fun apply(fillupId: Long, removedIds: Set<Long>, added: List<File>, nowEpochSec: Long): PhotoApplyResult = mutex.withLock {
        var failed = 0
        added.forEach { pending ->
            val fileName = runCatching { store.commit(pending) }.getOrNull()
            if (fileName == null) failed++ else dao.insert(FuelPhotoEntity(fillupId = fillupId, fileName = fileName, addedAtEpochSec = nowEpochSec))
        }
        if (failed == 0) {
            dao.forFillup(fillupId).filter { it.id in removedIds }.forEach { photo ->
                dao.delete(photo.id)
                store.delete(photo.fileName)
            }
        }
        PhotoApplyResult(saved = added.size - failed, failed = failed)
    }

    /** Kirjauksen poisto: viiteavain poistaa rivit kirjauksen mukana, tiedostot poistetaan sen jälkeen. */
    suspend fun deleteWithFillup(fillupId: Long, deleteFillup: suspend () -> Unit): Unit = mutex.withLock {
        val photos = dao.forFillup(fillupId)
        deleteFillup()
        photos.forEach { store.delete(it.fileName) }
    }

    /** Käynnistyksen siivous: kuvakansion tiedostot ilman riviä ja rivit ilman tiedostoa pois. */
    suspend fun sweep(): Unit = mutex.withLock {
        val rows = dao.all()
        val missing = store.sweepPhotos(rows.map { it.fileName }.toSet())
        rows.filter { it.fileName in missing }.forEach { dao.delete(it.id) }
    }
}
