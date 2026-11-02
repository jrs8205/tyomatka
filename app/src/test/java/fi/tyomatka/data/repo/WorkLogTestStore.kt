package fi.tyomatka.data.repo

import fi.tyomatka.data.db.*
import fi.tyomatka.data.photo.FillupPhotoRepository
import fi.tyomatka.data.photo.PhotoStore
import fi.tyomatka.data.report.ReportPhotos
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.reflect.Proxy
import java.nio.file.Files

/** In-memory DAO implementations exercise real export/import without Android or user data. */
internal class WorkLogTestStore {
    val days = mutableListOf<RouteDayEntity>()
    val segments = mutableListOf<WorkSegmentEntity>()
    val fillups = mutableListOf<FuelFillupEntity>()
    val cars = mutableListOf<CarEntity>()
    val photos = mutableListOf<FuelPhotoEntity>()
    val templates = mutableListOf<RouteTemplateEntity>()
    val templateStops = mutableListOf<TemplateStopEntity>()

    /** Kerran: ajetaan heti tuonnin tankkaustilannekuvan jälkeen (kilpaileva muokkaus tuonnin aikana). */
    var onFuelSnapshot: (() -> Unit)? = null

    /** Stoppien ja pohjien yhteinen kirjoituslukko (sama kuin RouteTemplateRepositoryllä) ja transaktioiden määrä. */
    val lock = StopsWriteLock()
    var transactions = 0
    private val transactionRunner = object : TransactionRunner {
        override suspend fun <T> transaction(block: suspend () -> T): T {
            transactions++
            return block()
        }
    }

    private val dayDao = object : RouteDayDao {
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
        override suspend fun fillNoteIfEmpty(id: Long, note: String): Int {
            val at = days.indexOfFirst { it.id == id && it.note.isBlank() }
            if (at < 0) return 0
            days[at] = days[at].copy(note = note)
            return 1
        }
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
    private val segmentDao = object : WorkSegmentDao {
        override suspend fun insert(segment: WorkSegmentEntity): Long {
            val id = (segments.maxOfOrNull { it.id } ?: 0) + 1
            segments.add(segment.copy(id = id))
            return id
        }
        override suspend fun update(segment: WorkSegmentEntity) { segments[segments.indexOfFirst { it.id == segment.id }] = segment }
        override suspend fun byId(id: Long) = segments.firstOrNull { it.id == id }
        override fun observeForDay(dayId: Long) = flowOf(segments.filter { it.routeDayId == dayId })
        override suspend fun forDay(dayId: Long) = segments.filter { it.routeDayId == dayId }
        override suspend fun openForDay(dayId: Long) = segments.lastOrNull { it.routeDayId == dayId && it.endEpochSec == null }
        override fun observeAll() = flowOf(segments.toList())
        override suspend fun all() = segments.toList()
    }
    val fuelDao = object : FuelFillupDao {
        override suspend fun insert(fillup: FuelFillupEntity): Long {
            val id = (fillups.maxOfOrNull { it.id } ?: 0) + 1
            fillups.add(fillup.copy(id = id))
            return id
        }
        override suspend fun update(fillup: FuelFillupEntity) { fillups[fillups.indexOfFirst { it.id == fillup.id }] = fillup }
        override suspend fun fillNoteIfEmpty(id: Long, note: String): Int {
            val at = fillups.indexOfFirst { it.id == id && it.note.isBlank() }
            if (at < 0) return 0
            fillups[at] = fillups[at].copy(note = note)
            return 1
        }
        override suspend fun delete(id: Long) { fillups.removeAll { it.id == id } }
        override suspend fun byId(id: Long) = fillups.firstOrNull { it.id == id }
        override fun observeAll() = flowOf(fillups.sortedWith(fillupOrder.reversed()))
        override suspend fun all(): List<FuelFillupEntity> {
            val snapshot = fillups.sortedWith(fillupOrder)
            onFuelSnapshot?.let { hook -> onFuelSnapshot = null; hook() }
            return snapshot
        }
    }
    private val carDao = object : CarDao {
        override fun observeAll() = flowOf(cars.toList())
        override suspend fun all() = cars.toList()
        override suspend fun get(plate: String) = cars.firstOrNull { it.plate == plate }
        override suspend fun upsert(car: CarEntity) {
            // Room päivittää olemassa olevan rivin paikallaan, joten lisäysjärjestys säilyy.
            val at = cars.indexOfFirst { it.plate == car.plate }
            if (at >= 0) cars[at] = car else cars.add(car)
        }
        override suspend fun insertIfMissing(car: CarEntity): Long {
            if (cars.any { it.plate == car.plate }) return -1
            cars.add(car)
            return cars.size.toLong()
        }
        override suspend fun updateFuel(plate: String, fuel: FuelKind?) {
            val at = cars.indexOfFirst { it.plate == plate }
            if (at >= 0) cars[at] = cars[at].copy(fuel = fuel)
        }
        override suspend fun deleteIfUnused(plate: String): Int {
            val used = segments.any { it.licensePlate == plate } || fillups.any { it.licensePlate == plate }
            return if (!used && cars.removeAll { it.plate == plate }) 1 else 0
        }
        override suspend fun fillMissing(plate: String, fuel: FuelKind?, currency: String?) {
            val at = cars.indexOfFirst { it.plate == plate }
            if (at >= 0) cars[at] = cars[at].let { it.copy(fuel = it.fuel ?: fuel, currency = it.currency ?: currency) }
        }
    }
    private val templateDao: RouteTemplateDao = InMemoryRouteTemplateDao(templates, templateStops)

    /** Karttakohteiden korjaukset ja hinnat (versio 8). */
    val poiDao = FakePoiDao()
    private val stopDao = Proxy.newProxyInstance(StopDao::class.java.classLoader, arrayOf(StopDao::class.java)) { _, method, _ ->
        if (method.name == "countForDay") 1 else error("Unexpected StopDao call: ${method.name}")
    } as StopDao
    private val photoOrder = compareBy<FuelPhotoEntity>({ it.addedAtEpochSec }, { it.id })
    private val photoDao = object : FuelPhotoDao {
        override suspend fun insert(photo: FuelPhotoEntity): Long {
            val id = (photos.maxOfOrNull { it.id } ?: 0) + 1
            photos.add(photo.copy(id = id))
            return id
        }
        override suspend fun delete(id: Long) { photos.removeAll { it.id == id } }
        override suspend fun forFillup(fillupId: Long) = photos.filter { it.fillupId == fillupId }.sortedWith(photoOrder)
        override fun observeAll() = flowOf(photos.sortedWith(photoOrder))
        override suspend fun all() = photos.sortedWith(photoOrder)
        override suspend fun byBackupId(backupId: String) = photos.firstOrNull { it.backupId == backupId }
    }

    /** Kuvatiedostot väliaikaisessa kansiossa, joka poistetaan testiajon lopussa. */
    val photoStore = PhotoStore(File(tempRoot, "files-${System.nanoTime()}"), File(tempRoot, "cache-${System.nanoTime()}"))
    val photoRepository = FillupPhotoRepository(photoDao, photoStore)

    /** Tallennettu kuva kirjaukselle: tiedosto kuvakansioon ja rivi. */
    fun addPhoto(fillupId: Long, bytes: ByteArray, at: Long = 1): FuelPhotoEntity {
        val name = photoStore.commit(photoStore.newPending().apply { writeBytes(bytes) })
        val photo = FuelPhotoEntity(id = (photos.maxOfOrNull { it.id } ?: 0) + 1, fillupId = fillupId, fileName = name, addedAtEpochSec = at)
        photos.add(photo)
        return photo
    }

    /** Raportin kuvat JVM:ssä: pienoiskuvaa ei tehdä (galleriaan paikkamerkki), täysikokoinen luetaan tiedostosta. */
    val reportPhotos = object : ReportPhotos {
        override fun thumbnail(photo: FuelPhotoEntity): ByteArray? = null
        override fun open(photo: FuelPhotoEntity): InputStream? = try {
            photoStore.file(photo.fileName).inputStream()
        } catch (e: FileNotFoundException) {
            null
        }
    }

    val repository =
        WorkLogRepository(
            segmentDao, dayDao, stopDao, fuelDao, carDao, templateDao, poiDao, lock, transactionRunner, photoRepository,
            photoStore, reportPhotos,
        )

    private companion object {
        val tempRoot: File = Files.createTempDirectory("tyomatka-test").toFile().also { root ->
            Runtime.getRuntime().addShutdownHook(Thread { root.deleteRecursively() })
        }
    }
}
