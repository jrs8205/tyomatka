package fi.tyomatka.data.repo

import fi.tyomatka.data.db.FuelFillupDao
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.photo.FillupPhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Tankkausten kirjaus. Laskenta on puhtaissa funktioissa (FuelStats.kt). */
@Singleton
class FuelRepository @Inject constructor(
    private val fuelFillupDao: FuelFillupDao,
    private val photoRepository: FillupPhotoRepository,
) {
    private val writeMutex = Mutex()

    /** Uusin ensin. */
    fun observeAll(): Flow<List<FuelFillupEntity>> = fuelFillupDao.observeAll()

    suspend fun add(fillup: FuelFillupEntity): Long = writeMutex.withLock {
        fuelFillupDao.insert(fillup.copy(id = 0))
    }

    suspend fun update(fillup: FuelFillupEntity): Unit = writeMutex.withLock {
        fuelFillupDao.update(fillup)
    }

    /** Kirjauksen kuvat poistuvat sen mukana. */
    suspend fun delete(id: Long): Unit = photoRepository.deleteWithFillup(id) {
        writeMutex.withLock { fuelFillupDao.delete(id) }
    }
}
