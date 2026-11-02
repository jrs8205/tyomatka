package fi.tyomatka.data.repo

import fi.tyomatka.data.db.MatrixCacheDao
import fi.tyomatka.data.db.MatrixCacheEntity
import fi.tyomatka.data.db.RouteDayDao
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.RouteTemplateDao
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.StopDao
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.TemplateStopEntity
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Proxy

/** Muistinvaraiset DAO:t tallennettujen reittien repositorylle; käyttämättömät StopDao-metodit kaatuvat heti. */
internal class RouteTemplateTestStore(var nowSec: Long = 1_000) {
    val days = mutableListOf<RouteDayEntity>()
    val stops = mutableListOf<StopEntity>()
    val templates = mutableListOf<RouteTemplateEntity>()
    val templateStops = mutableListOf<TemplateStopEntity>()
    val matrixCache = mutableListOf<MatrixCacheEntity>()
    var matrixCleared = 0

    /** Stoppien yhteinen kirjoituslukko (sama kuin RouteRepositoryllä). */
    val lock = StopsWriteLock()

    /** Transaktioiden määrä ja stoppikirjoitukset, jotka tehtiin transaktion ulkopuolella. */
    var transactions = 0
    var writesOutsideTransaction = 0
    private var inTransaction = false
    private val transactionRunner = object : TransactionRunner {
        override suspend fun <T> transaction(block: suspend () -> T): T {
            transactions++
            inTransaction = true
            try {
                return block()
            } finally {
                inTransaction = false
            }
        }
    }

    val dayDao = object : RouteDayDao {
        override suspend fun insert(day: RouteDayEntity): Long {
            if (days.any { it.dateEpochDay == day.dateEpochDay }) return -1
            val id = (days.maxOfOrNull { it.id } ?: 0) + 1
            days.add(day.copy(id = id))
            return id
        }
        override suspend fun update(day: RouteDayEntity) { days[days.indexOfFirst { it.id == day.id }] = day }
        override suspend fun setStartedIfNull(id: Long, epochSec: Long) {
            val at = days.indexOfFirst { it.id == id && it.startedAtEpochSec == null }
            if (at >= 0) days[at] = days[at].copy(startedAtEpochSec = epochSec)
        }
        override suspend fun setFinished(id: Long, epochSec: Long) {
            val at = days.indexOfFirst { it.id == id }
            if (at >= 0) days[at] = days[at].copy(finishedAtEpochSec = epochSec)
        }
        override suspend fun setNote(id: Long, note: String) {
            val at = days.indexOfFirst { it.id == id }
            if (at >= 0) days[at] = days[at].copy(note = note)
        }
        override suspend fun setNoteByDate(epochDay: Long, note: String) {
            val at = days.indexOfFirst { it.dateEpochDay == epochDay }
            if (at >= 0) days[at] = days[at].copy(note = note)
        }
        override suspend fun fillNoteIfEmpty(id: Long, note: String): Int = 0
        override suspend fun byDate(epochDay: Long) = days.firstOrNull { it.dateEpochDay == epochDay }
        override suspend fun byId(id: Long) = days.firstOrNull { it.id == id }
        override fun observeById(id: Long) = flowOf(days.firstOrNull { it.id == id })
        override fun observeAll() = flowOf(days.toList())
        override suspend fun all() = days.toList()
        override suspend fun delete(id: Long) { days.removeAll { it.id == id } }
        override suspend fun setTemplate(id: Long, templateId: Long?) {
            val at = days.indexOfFirst { it.id == id }
            if (at >= 0) days[at] = days[at].copy(templateId = templateId, templateAck = null)
        }
        override suspend fun setTemplateAck(id: Long, ack: String?) {
            val at = days.indexOfFirst { it.id == id }
            if (at >= 0) days[at] = days[at].copy(templateAck = ack)
        }
        override suspend fun clearTemplate(templateId: Long) {
            days.replaceAll { if (it.templateId == templateId) it.copy(templateId = null, templateAck = null) else it }
        }
    }

    val stopDao: StopDao = Proxy.newProxyInstance(StopDao::class.java.classLoader, arrayOf(StopDao::class.java)) { _, method, args ->
        // Suspend-funktion viimeinen argumentti on jatko (Continuation); tulos palautetaan suoraan.
        if (method.name in WRITES && !inTransaction) writesOutsideTransaction++
        when (method.name) {
            "insert" -> {
                val stop = args[0] as StopEntity
                val id = (stops.maxOfOrNull { it.id } ?: 0) + 1
                stops.add(stop.copy(id = id))
                id
            }
            "forDay" -> stops.filter { it.routeDayId == args[0] as Long }.sortedBy { it.orderIndex }
            "byId" -> stops.firstOrNull { it.id == args[0] as Long }
            "delete" -> { stops.removeAll { it.id == args[0] as Long }; Unit }
            "deleteForDay" -> { stops.removeAll { it.routeDayId == args[0] as Long }; Unit }
            "setOrder" -> {
                val at = stops.indexOfFirst { it.id == args[0] as Long }
                if (at >= 0) stops[at] = stops[at].copy(orderIndex = args[1] as Int)
                Unit
            }
            else -> error("Unexpected StopDao call: ${method.name}")
        }
    } as StopDao

    val matrixCacheDao = object : MatrixCacheDao {
        override suspend fun put(cache: MatrixCacheEntity) { matrixCache.add(cache) }
        override suspend fun get(dayId: Long) = matrixCache.firstOrNull { it.routeDayId == dayId }
        override suspend fun clear(dayId: Long) { matrixCache.removeAll { it.routeDayId == dayId }; matrixCleared++ }
    }

    val templateDao: RouteTemplateDao = InMemoryRouteTemplateDao(templates, templateStops)

    val repository = RouteTemplateRepository(templateDao, dayDao, stopDao, matrixCacheDao, lock, transactionRunner) { nowSec }

    private companion object {
        val WRITES = setOf("insert", "delete", "deleteForDay", "setOrder")
    }
}
