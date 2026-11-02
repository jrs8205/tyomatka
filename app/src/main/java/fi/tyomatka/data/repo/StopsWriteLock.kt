package fi.tyomatka.data.repo

import androidx.room.withTransaction
import fi.tyomatka.data.db.AppDatabase
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Päivän stoppien ja pohjien yhteinen kirjoituslukko: [RouteRepository] ja [RouteTemplateRepository] lukevat ja
 * kirjoittavat samaa `stops`-taulua (järjestysnumerot, tuplasääntö), ja [RouteTemplateRepository] sekä
 * [WorkLogRepository]n tuonti samoja pohjatauluja, joten niiden luku–muokkaa–kirjoita-vaiheet eivät saa lomittua.
 */
@Singleton
class StopsWriteLock @Inject constructor() {
    val mutex = Mutex()
}

/** Useamman rivin kirjoitus yhtenä kokonaisuutena; tarkkailijat eivät näe välitiloja. Testit ajavat lohkon suoraan. */
interface TransactionRunner {
    suspend fun <T> transaction(block: suspend () -> T): T
}

class RoomTransactionRunner @Inject constructor(private val db: AppDatabase) : TransactionRunner {
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction(block)
}
