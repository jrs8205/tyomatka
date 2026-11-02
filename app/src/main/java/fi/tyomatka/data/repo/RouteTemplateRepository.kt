package fi.tyomatka.data.repo

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.plan.ServiceStops
import fi.tyomatka.core.plan.TemplateStopData
import fi.tyomatka.core.plan.normalizeTemplateName
import fi.tyomatka.data.db.MatrixCacheDao
import fi.tyomatka.data.db.RouteDayDao
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.RouteTemplateDao
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.StopDao
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.db.TemplateListItem
import fi.tyomatka.data.db.TemplateStopEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Pohjan avaus päivälle: korvataan päivän stopit tai lisätään pohjan stopit perään. */
enum class OpenMode { REPLACE, APPEND }

/**
 * Tallennetut reitit (pohjat) ja niiden suhde päivän listaan.
 * Pohja sisältää vain asiakasstopit: huoltostopit ([ServiceStops]) eivät tallennu pohjaan eivätkä poistu
 * hylkäyksessä. Kirjoitukset ovat stoppien yhteisen lukon ([StopsWriteLock], sama kuin [RouteRepository]llä) alla
 * ja useamman rivin kirjoitukset yhdessä transaktiossa ([TransactionRunner]), joten tarkkailijat eivät näe
 * välitiloja eikä rinnakkainen lisäys saa samaa järjestysnumeroa.
 */
@Singleton
class RouteTemplateRepository internal constructor(
    private val templateDao: RouteTemplateDao,
    private val routeDayDao: RouteDayDao,
    private val stopDao: StopDao,
    private val matrixCacheDao: MatrixCacheDao,
    private val lock: StopsWriteLock,
    private val transactions: TransactionRunner,
    private val nowSec: () -> Long,
) {
    @Inject
    constructor(
        templateDao: RouteTemplateDao,
        routeDayDao: RouteDayDao,
        stopDao: StopDao,
        matrixCacheDao: MatrixCacheDao,
        lock: StopsWriteLock,
        transactions: RoomTransactionRunner,
    ) : this(templateDao, routeDayDao, stopDao, matrixCacheDao, lock, transactions, { System.currentTimeMillis() / 1000 })

    private val writeMutex get() = lock.mutex

    fun observeTemplates(): Flow<List<TemplateListItem>> = templateDao.observeAll()

    /** Päivän rivi (pohjaviite ja Myöhemmin-kuittaus) tarkkailtavana. */
    fun observeDay(dayId: Long): Flow<RouteDayEntity?> = routeDayDao.observeById(dayId)

    fun observeTemplate(templateId: Long): Flow<RouteTemplateEntity?> = templateDao.observeById(templateId)

    fun observeTemplateStops(templateId: Long): Flow<List<TemplateStopEntity>> = templateDao.observeStops(templateId)

    /** Tallentaa päivän asiakasstopit uudeksi pohjaksi ja merkitsee päivän avatuksi siitä. */
    suspend fun saveNew(dayId: Long, name: String): Long = writeMutex.withLock {
        val normalized = requireName(name)
        transactions.transaction {
            val now = nowSec()
            val id = templateDao.insert(RouteTemplateEntity(name = normalized, createdEpochSec = now, updatedEpochSec = now))
            templateDao.insertStops(customerStops(dayId).toTemplateStops(id))
            routeDayDao.setTemplate(dayId, id)
            id
        }
    }

    /** Korvaa pohjan stopit päivän asiakasstopeilla. */
    suspend fun overwrite(templateId: Long, dayId: Long): Unit = writeMutex.withLock {
        transactions.transaction {
            templateDao.deleteStops(templateId)
            templateDao.insertStops(customerStops(dayId).toTemplateStops(templateId))
            templateDao.touch(templateId, nowSec())
            routeDayDao.setTemplate(dayId, templateId)
        }
    }

    /**
     * Avaa pohjan päivälle. [OpenMode.REPLACE] vaihtaa pohjaviitteen, [OpenMode.APPEND] ei koske siihen eikä lisää
     * osoitetta, joka on jo päivän hoitamattomalla listalla (sama sääntö kuin [RouteRepository.addStop]).
     * Palauttaa ohitettujen stoppien määrän.
     */
    suspend fun open(templateId: Long, dayId: Long, mode: OpenMode): Int = writeMutex.withLock {
        transactions.transaction {
            val templateStops = templateDao.stopsFor(templateId)
            val (toAdd, firstIndex) = when (mode) {
                OpenMode.REPLACE -> {
                    stopDao.deleteForDay(dayId)
                    matrixCacheDao.clear(dayId)
                    templateStops to 0
                }
                OpenMode.APPEND -> {
                    val existing = stopDao.forDay(dayId)
                    val open = existing.filter { it.status != StopStatus.DONE && it.status != StopStatus.SKIPPED }
                    val fresh = templateStops.filterNot { stop -> open.any { sameAddress(it, stop) } }
                    fresh to (existing.maxOfOrNull { it.orderIndex }?.plus(1) ?: 0)
                }
            }
            toAdd.forEachIndexed { i, stop -> stopDao.insert(stop.toStop(dayId, firstIndex + i)) }
            if (mode == OpenMode.REPLACE) routeDayDao.setTemplate(dayId, templateId)
            templateDao.setLastOpened(templateId, nowSec())
            templateStops.size - toAdd.size
        }
    }

    suspend fun rename(templateId: Long, name: String): Unit = writeMutex.withLock {
        templateDao.rename(templateId, requireName(name), nowSec())
    }

    /** Poistaa pohjan; siitä avatut päivät eivät enää viittaa siihen. */
    suspend fun delete(templateId: Long): Unit = writeMutex.withLock {
        transactions.transaction {
            routeDayDao.clearTemplate(templateId)
            templateDao.delete(templateId)
        }
    }

    /**
     * Palauttaa päivän asiakasstopit pohjan mukaisiksi. Huoltostopit säilyvät ja siirtyvät asiakasstoppien perään
     * keskinäisessä järjestyksessään. Nollaa Myöhemmin-kuittauksen.
     */
    suspend fun discardChanges(dayId: Long): Unit = writeMutex.withLock {
        val templateId = routeDayDao.byId(dayId)?.templateId ?: return@withLock
        transactions.transaction {
            val templateStops = templateDao.stopsFor(templateId)
            val existing = stopDao.forDay(dayId)
            existing.filterNot { ServiceStops.isServiceStop(it.placeId) }.forEach { stopDao.delete(it.id) }
            templateStops.forEachIndexed { i, stop -> stopDao.insert(stop.toStop(dayId, i)) }
            existing.filter { ServiceStops.isServiceStop(it.placeId) }.forEachIndexed { i, stop ->
                stopDao.setOrder(stop.id, templateStops.size + i)
            }
            matrixCacheDao.clear(dayId)
            routeDayDao.setTemplate(dayId, templateId)
        }
    }

    /** Myöhemmin: muutosrivi pysyy piilossa, kunnes päivän asiakasstopit muuttuvat taas. */
    suspend fun acknowledge(dayId: Long, ackKey: String): Unit = writeMutex.withLock {
        routeDayDao.setTemplateAck(dayId, ackKey)
    }

    private fun requireName(name: String): String =
        normalizeTemplateName(name).also { require(it.isNotEmpty()) { "Reitin nimi puuttuu." } }

    private fun sameAddress(stop: StopEntity, template: TemplateStopEntity): Boolean = isSameAddress(
        stop.placeId, stop.address, GeoPoint(stop.lat, stop.lng),
        template.placeId, template.address, GeoPoint(template.lat, template.lng),
    )

    private suspend fun customerStops(dayId: Long): List<StopEntity> =
        stopDao.forDay(dayId).filterNot { ServiceStops.isServiceStop(it.placeId) }

    private fun List<StopEntity>.toTemplateStops(templateId: Long): List<TemplateStopEntity> =
        mapIndexed { i, stop ->
            TemplateStopEntity(
                templateId = templateId, orderIndex = i, label = stop.label, address = stop.address, placeId = stop.placeId,
                lat = stop.lat, lng = stop.lng, deadlineMinutesOfDay = stop.deadlineMinutesOfDay, serviceSec = stop.serviceSec,
            )
        }

    private fun TemplateStopEntity.toStop(dayId: Long, orderIndex: Int): StopEntity = StopEntity(
        routeDayId = dayId, label = label, address = address, placeId = placeId, lat = lat, lng = lng,
        orderIndex = orderIndex, deadlineMinutesOfDay = deadlineMinutesOfDay, serviceSec = serviceSec, status = StopStatus.PENDING,
    )
}

/** Vertailukentät päivän stopista. */
fun StopEntity.toTemplateData(): TemplateStopData = TemplateStopData(label, address, lat, lng, deadlineMinutesOfDay, serviceSec)

/** Vertailukentät pohjan stopista. */
fun TemplateStopEntity.toTemplateData(): TemplateStopData = TemplateStopData(label, address, lat, lng, deadlineMinutesOfDay, serviceSec)
