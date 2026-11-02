package fi.tyomatka.data.repo

import fi.tyomatka.R
import fi.tyomatka.core.plan.normalizeTemplateName
import fi.tyomatka.data.normalizeNote
import fi.tyomatka.data.db.CarDao
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupDao
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.core.poi.PoiCorrectionField
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.PoiCorrectionEntity
import fi.tyomatka.data.db.PoiDao
import fi.tyomatka.data.db.PoiPriceEntity
import fi.tyomatka.data.db.RouteDayDao
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.RouteTemplateDao
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.StopDao
import fi.tyomatka.data.db.TemplateStopEntity
import fi.tyomatka.data.db.WorkSegmentDao
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.photo.FillupPhotoRepository
import fi.tyomatka.data.photo.PhotoStore
import fi.tyomatka.data.report.BackupLine
import fi.tyomatka.data.report.ExportPeriod
import fi.tyomatka.data.report.ExportSummary
import fi.tyomatka.data.report.PHOTO_DATA
import fi.tyomatka.data.report.PHOTO_LINE
import fi.tyomatka.data.report.ReportFormat
import fi.tyomatka.data.report.ReportLanguage
import fi.tyomatka.data.report.ReportLocale
import fi.tyomatka.data.report.ReportModel
import fi.tyomatka.data.report.ReportPhotos
import fi.tyomatka.data.report.backupLine
import fi.tyomatka.data.report.bounds
import fi.tyomatka.data.report.reportModel
import fi.tyomatka.data.report.reportStrings
import fi.tyomatka.data.report.summarizeExport
import fi.tyomatka.data.report.writeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Työpäivän yhteenveto segmenteistä laskettuna. */
data class WorkDayTotals(
    val startEpochSec: Long,
    /** Null = päivä on yhä käynnissä. */
    val endEpochSec: Long?,
    /** Työaika alusta loppuun; käynnissä olevalle null. */
    val durationSec: Long?,
    /** Kilometrit segmenteistä, joissa molemmat lukemat ovat. */
    val totalKm: Long,
    /** Joltain päättyneeltä segmentiltä puuttuu lukema tai lukemat ovat ristiriidassa. */
    val missingReadings: Boolean,
)

/**
 * Työpäivän kesto ja kilometrit segmenteistä. Kesto on ensimmäisen segmentin
 * alusta viimeisen loppuun — autonvaihto ei katkaise työaikaa.
 */
fun workDayTotals(segments: List<WorkSegmentEntity>): WorkDayTotals? {
    if (segments.isEmpty()) return null
    val sorted = segments.sortedBy { it.startEpochSec }
    val ongoing = sorted.any { it.endEpochSec == null }
    val start = sorted.first().startEpochSec
    val end = if (ongoing) null else sorted.mapNotNull { it.endEpochSec }.max()
    var km = 0L
    var missing = false
    sorted.forEach { seg ->
        val s = seg.odometerStartKm
        val e = seg.odometerEndKm
        when {
            s != null && e != null && s >= 0 && e >= s -> km += e - s
            // Avoimelta segmentiltä lopetuslukema puuttuu luonnostaan.
            seg.endEpochSec == null && s != null && e == null -> Unit
            else -> missing = true
        }
    }
    return WorkDayTotals(
        startEpochSec = start,
        endEpochSec = end,
        durationSec = end?.let { it - start },
        totalKm = km,
        missingReadings = missing,
    )
}

// ---- Vienti/tuonti: HTML-raportti, jonka sisään data on upotettu ----

@Serializable
private data class TransferSegment(
    val start: Long,
    val end: Long? = null,
    val kmStart: Long? = null,
    val kmEnd: Long? = null,
    /** Versio 2: rekisterinumero. */
    val plate: String? = null,
    val backupId: String? = null,
)

/** Versio 7: [note] = päivän lisätiedot; null tai puuttuva = ei lisätietoa. */
@Serializable
private data class TransferDay(val date: Long, val segments: List<TransferSegment>, val note: String? = null)

@Serializable
private data class TransferFuel(
    val at: Long,
    val plate: String,
    val km: Long,
    /** Määrä lajin yksikössä (l, kg tai kWh). */
    val liters: Double,
    val cost: Double,
    val price: Double,
    val backupId: String? = null,
    /** Versio 4: laji ([FuelKind]-nimi); aiemmissa puuttuu → diesel. */
    val kind: String? = null,
    /** Versio 4: täysi tankki; aiemmissa puuttuu → täysi. */
    val full: Boolean? = null,
    /** Versio 4: ISO 4217; aiemmissa puuttuu → euro. */
    val currency: String? = null,
    /** Versio 5: kirjauksen kuvat; kuvan data on raportin kuvarivillä ([PHOTO_LINE]). */
    val photos: List<TransferPhoto> = emptyList(),
    /** Versio 7: kirjauksen lisätiedot; null tai puuttuva = ei lisätietoa. */
    val note: String? = null,
)

/** Versio 5: kuvan tunniste ja lisäyshetki. */
@Serializable
private data class TransferPhoto(val id: String, val at: Long)

/** Versio 4: autoprofiili (polttoaine, null = automaattinen; vanhojen versioiden valuutta säilyy sellaisenaan). */
@Serializable
private data class TransferCar(
    val plate: String,
    val fuel: String? = null,
    val currency: String? = null,
)

/** Versio 6: viedyn jakson ensimmäinen ja viimeinen päivä (LocalDate.toEpochDay). */
@Serializable
private data class TransferPeriod(val from: Long, val to: Long)

/** Versio 8: tallennetun reitin stoppi ajojärjestyksessä ([deadline] minuutteja keskiyöstä, [service] sekunteja). */
@Serializable
private data class TransferTemplateStop(
    val label: String,
    val address: String,
    val placeId: String? = null,
    val lat: Double,
    val lng: Double,
    val deadline: Int? = null,
    val service: Long? = null,
)

/** Versio 8: tallennettu reitti (pohja) stoppeineen; ajat epoch-sekunteina. */
@Serializable
private data class TransferTemplate(val name: String, val created: Long, val updated: Long, val stops: List<TransferTemplateStop>)

/** Versio 8: kuljettajan korjaus karttakohteen tietoon ([field] = `PoiCorrectionField.key`, [at] epoch-sekunteina). */
@Serializable
private data class TransferPoiCorrection(val poi: String, val field: String, val value: String, val at: Long)

/** Versio 8: kuljettajan kirjaama hinta karttakohteella ([fuel] = `FuelKind`-nimi, [day] LocalDate.toEpochDay). */
@Serializable
private data class TransferPoiPrice(val poi: String, val fuel: String, val price: Double, val currency: String, val day: Long)

@Serializable
private data class TransferFile(
    val version: Int = 8,
    val exported: Long,
    val days: List<TransferDay>,
    /** Versio 2; versio 1:n tiedostoissa puuttuu → tyhjä. */
    val fuel: List<TransferFuel> = emptyList(),
    /** Versio 4; aiemmissa puuttuu → tyhjä. */
    val cars: List<TransferCar> = emptyList(),
    /** Versio 6: jakson vienti; null = koko varmuuskopio. */
    val period: TransferPeriod? = null,
    /** Versio 6: raportin kieli ("fi", "sv" tai "en"). */
    val language: String? = null,
    /** Versio 8: tallennetut reitit; vain koko varmuuskopiossa, jakson raportissa tyhjä. */
    val templates: List<TransferTemplate> = emptyList(),
    /** Versio 8: karttakohteiden korjaukset ja hinnat; vain koko varmuuskopiossa. */
    val poiCorrections: List<TransferPoiCorrection> = emptyList(),
    val poiPrices: List<TransferPoiPrice> = emptyList(),
)

/**
 * Tuonnin tulos. [fuelKinds]: varmuuskopion kirjausten (tuodut ja ohitetut) lajit ilmoituksen sanaa varten. [period]:
 * jakson varmuuskopion ensimmäinen ja viimeinen päivä (LocalDate.toEpochDay); koko varmuuskopiolla null.
 */
data class WorkImportResult(
    val added: Int,
    val skipped: Int,
    val fuelAdded: Int,
    val fuelSkipped: Int,
    val photosAdded: Int = 0,
    val fuelKinds: Set<FuelKind> = emptySet(),
    val period: Pair<Long, Long>? = null,
    /** Versio 8: tuodut ja samannimisinä korvatut tallennetut reitit. */
    val templates: Int = 0,
    /** Versio 8: kirjoitetut karttakohteiden korjaukset ja hinnat (uudempi voittaa). */
    val poiEdits: Int = 0,
)

/**
 * Tuonnin virheteksti virheellisen kirjauksen sanalla (sähkö = lataus, kaasu = täyttö, muut = tankkaus, kuten
 * käyttöliittymän FillWord; vanha varmuuskopio ilman lajia on dieseltankkaus). Kun lajia ei voi lukea, muoto on
 * neutraali.
 */
internal fun invalidFuelRes(kindName: String?): Int {
    val kind = if (kindName == null) FuelKind.DIESEL else FuelKind.entries.firstOrNull { it.name == kindName }
    return when (kind) {
        null -> R.string.import_bad_any_fuel
        FuelKind.ELECTRIC -> R.string.import_bad_charge
        FuelKind.GAS -> R.string.import_bad_refill
        else -> R.string.import_bad_fillup
    }
}

/**
 * Varmuuskopion hylkäys: [messageRes] kertoo syyn käyttäjän kielellä, ja poikkeuksen oma viesti jää diagnostiikkaan.
 */
class BackupRejectedException(@androidx.annotation.StringRes val messageRes: Int, detail: String) : IllegalArgumentException(detail)

private inline fun requireBackup(condition: Boolean, @androidx.annotation.StringRes messageRes: Int, detail: () -> String) {
    if (!condition) throw BackupRejectedException(messageRes, detail())
}

internal fun invalidFuelMessage(kindName: String?): String {
    val kind = if (kindName == null) FuelKind.DIESEL else FuelKind.entries.firstOrNull { it.name == kindName }
    val word = when (kind) {
        null -> "tankkaus-, lataus- tai täyttökirjaus"
        FuelKind.ELECTRIC -> "lataus"
        FuelKind.GAS -> "täyttö"
        else -> "tankkaus"
    }
    return "Varmuuskopiossa on virheellinen $word. Tietoja ei tuotu."
}

/**
 * Työajan ja kilometrien kirjaus: työpäivä koostuu autosegmenteistä.
 * Täysin erillinen ajosessiosta — työt alkavat yleensä ennen ensimmäistä
 * navigointia ja päättyvät viimeisen jälkeen.
 *
 * Vienti tuottaa HTML-raportin, joka aukeaa selaimessa luettavana sivuna ja
 * jonka sisään sama data on upotettu koneluettavana JSONina — yksi tiedosto
 * toimii sekä raporttina että varmuuskopiona, jonka voi tuoda takaisin.
 */
@Singleton
class WorkLogRepository @Inject constructor(
    private val workSegmentDao: WorkSegmentDao,
    private val routeDayDao: RouteDayDao,
    private val stopDao: StopDao,
    private val fuelFillupDao: FuelFillupDao,
    private val carDao: CarDao,
    private val templateDao: RouteTemplateDao,
    private val poiDao: PoiDao,
    /** Pohjien kirjoitukset samassa lukossa ja transaktiossa kuin RouteTemplateRepositoryssa. */
    private val stopsLock: StopsWriteLock,
    private val transactions: TransactionRunner,
    private val photoRepository: FillupPhotoRepository,
    private val photoStore: PhotoStore,
    private val reportPhotos: ReportPhotos,
) {
    /** Suojaa tarkista-ja-kirjoita-kulut tuplanapautuksilta. */
    private val writeMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun observeForDay(dayId: Long): Flow<List<WorkSegmentEntity>> =
        workSegmentDao.observeForDay(dayId)

    fun observeAll(): Flow<List<WorkSegmentEntity>> = workSegmentDao.observeAll()

    fun observeDays(): Flow<List<RouteDayEntity>> = routeDayDao.observeAll()

    /** Aloita työt tai jatka päätettyä päivää: avaa uuden segmentin. */
    suspend fun startSegment(
        dayId: Long,
        odometerKm: Long?,
        licensePlate: String?,
        nowEpochSec: Long,
    ): Unit = writeMutex.withLock {
        if (workSegmentDao.openForDay(dayId) != null) return@withLock
        workSegmentDao.insert(
            WorkSegmentEntity(
                routeDayId = dayId,
                startEpochSec = nowEpochSec,
                odometerStartKm = odometerKm,
                licensePlate = licensePlate?.let(::normalizePlate),
            )
        )
    }

    /** Autonvaihto: sulje avoin segmentti (rekkari säilyy) ja avaa uusi uudella autolla. */
    suspend fun switchCar(
        dayId: Long,
        endOdometerKm: Long?,
        startOdometerKm: Long?,
        newLicensePlate: String?,
        nowEpochSec: Long,
    ): Unit = writeMutex.withLock {
        val open = workSegmentDao.openForDay(dayId) ?: return@withLock
        workSegmentDao.update(open.copy(endEpochSec = nowEpochSec, odometerEndKm = endOdometerKm))
        workSegmentDao.insert(
            WorkSegmentEntity(
                routeDayId = dayId,
                startEpochSec = nowEpochSec,
                odometerStartKm = startOdometerKm,
                licensePlate = newLicensePlate?.let(::normalizePlate),
            )
        )
    }

    /** Lopeta työt: sulje avoin segmentti. */
    /** Päivän lisätiedot normalisoituna ([normalizeNote]); tuntematon päivä ei tee mitään eikä luo riviä. */
    suspend fun setDayNote(dayId: Long, note: String): Unit = writeMutex.withLock {
        routeDayDao.setNote(dayId, normalizeNote(note))
    }

    /** Sama päivämäärällä: korjauslomake tuntee päivän päivämäärän, ei id:tä. */
    suspend fun setDayNoteByDate(epochDay: Long, note: String): Unit = writeMutex.withLock {
        routeDayDao.setNoteByDate(epochDay, normalizeNote(note))
    }

    suspend fun endSegment(dayId: Long, odometerKm: Long?, nowEpochSec: Long): Unit =
        writeMutex.withLock {
            val open = workSegmentDao.openForDay(dayId) ?: return@withLock
            workSegmentDao.update(open.copy(endEpochSec = nowEpochSec, odometerEndKm = odometerKm))
        }

    /**
     * Korjaa segmentin mittarilukemat ja kellonajat. Null-lopetus säilyttää
     * nykyisen tilan (avoin pysyy avoimena, päätetyn lopetus ei katoa);
     * annettu lopetus päättää myös avoimen segmentin — unohtunut
     * "Lopeta työt" kirjataan näin jälkikäteen menneelle päivälle.
     */
    suspend fun updateSegment(
        segmentId: Long,
        startKm: Long?,
        endKm: Long?,
        startEpochSec: Long,
        endEpochSec: Long?,
        licensePlate: String?,
    ): Unit = writeMutex.withLock {
        workSegmentDao.byId(segmentId)?.let { seg ->
            workSegmentDao.update(
                seg.copy(
                    odometerStartKm = startKm,
                    odometerEndKm = endKm,
                    startEpochSec = startEpochSec,
                    endEpochSec = endEpochSec ?: seg.endEpochSec,
                    licensePlate = licensePlate?.let(::normalizePlate),
                )
            )
        }
    }

    // ---- Unohtuneen välin takautuva kirjaus ----

    /** Viimeisin kirjattu mittarilukema kaikista segmenteistä (esitäyttöön ja välintunnistukseen). */
    suspend fun lastReading(): LastReading? {
        val dateById = routeDayDao.all().associate { it.id to it.dateEpochDay }
        val seg = latestWorkSegment(workSegmentDao.all()) ?: return null
        val date = dateById[seg.routeDayId] ?: return null
        val km = seg.odometerEndKm ?: seg.odometerStartKm ?: return null
        return LastReading(date, km, isEnd = seg.odometerEndKm != null, licensePlate = seg.licensePlate)
    }

    /**
     * Tunnista kirjaamaton väli ennen uuden lukeman tallennusta: lukema on
     * noussut edellisestä ja väliin jää päiviä, joilla oli stoppilista mutta
     * ei työkirjausta. Kutsu ENNEN uuden segmentin kirjoitusta — muuten uusi
     * segmentti olisi itse "viimeisin lukema".
     */
    suspend fun gapSuggestion(newReadingKm: Long, todayEpochDay: Long, licensePlate: String?): GapSuggestion? {
        val last = lastReading() ?: return null
        // Tyhjä väli (edellinen lukema eiliseltä tai tältä päivältä) tuottaa
        // tyhjän listan, jolloin ehdotusta ei synny.
        val missing = ((last.epochDay + 1) until todayEpochDay).filter { date ->
            val day = routeDayDao.byDate(date) ?: return@filter false
            stopDao.countForDay(day.id) > 0 && workSegmentDao.forDay(day.id).isEmpty()
        }
        return findUnrecordedGap(newReadingKm, last, missing, newPlate = licensePlate)
    }

    /**
     * Kirjaa unohtuneet päivät takautuvasti: lukemat ketjutetaan edellisestä
     * kirjatusta lukemasta alkaen. Kellonajat otetaan päivän ajosessiosta
     * (startedAt/finishedAt); jos päivää ei ajettu sovelluksella, käytetään
     * keskipäivää — silloin segmentin kesto on 0 ja UI merkitsee sen
     * takautuvaksi. Nollakilometriset päivät ohitetaan.
     */
    suspend fun recordGap(perDayKm: Map<Long, Long>, previousKm: Long, licensePlate: String?): Int = writeMutex.withLock {
        var running = previousKm
        var added = 0
        perDayKm.toSortedMap().forEach { (date, km) ->
            if (km <= 0) return@forEach
            // IGNORE-insert + uudelleenhaku: sama kuvio kuin HTML-tuonnissa.
            val day = routeDayDao.byDate(date) ?: run {
                routeDayDao.insert(RouteDayEntity(dateEpochDay = date))
                requireNotNull(routeDayDao.byDate(date))
            }
            val noon = LocalDate.ofEpochDay(date).atTime(12, 0)
                .atZone(ZoneId.systemDefault()).toEpochSecond()
            workSegmentDao.insert(
                WorkSegmentEntity(
                    routeDayId = day.id,
                    startEpochSec = day.startedAtEpochSec ?: noon,
                    endEpochSec = day.finishedAtEpochSec ?: day.startedAtEpochSec ?: noon,
                    odometerStartKm = running,
                    odometerEndKm = running + km,
                    licensePlate = licensePlate?.let(::normalizePlate),
                )
            )
            running += km
            added++
        }
        added
    }

    /** HTML-raportti merkkijonona (testit ja pienet viennit); laite kirjoittaa virtaan ([exportHtml]). */
    suspend fun exportHtml(
        nowEpochSec: Long,
        period: ExportPeriod = ExportPeriod.Full,
        locale: ReportLocale = DEFAULT_REPORT_LOCALE,
    ): String = ByteArrayOutputStream().also { exportHtml(nowEpochSec, it, period, locale) }.toString(Charsets.UTF_8.name())

    /**
     * HTML-raportti valitulta jaksolta sovelluksen kielellä, varmuuskopion data upotettuna. Jakson vienti sisältää vain
     * jakson päivät ja kirjaukset kuvineen; koko varmuuskopio ([ExportPeriod.Full]) valitaan erikseen.
     * Kuvat kirjoitetaan virtaan tiedostosta kuva kerrallaan omille riveilleen, joten suurikaan raportti ei täytä muistia,
     * ja tuonti löytää ne riveittäin.
     */
    suspend fun exportHtml(
        nowEpochSec: Long,
        out: OutputStream,
        period: ExportPeriod = ExportPeriod.Full,
        locale: ReportLocale = DEFAULT_REPORT_LOCALE,
    ): Unit = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val model = loadReportModel(period, zone)
        val transfer = TransferFile(
            exported = nowEpochSec,
            days = model.days.filter { it.segments.isNotEmpty() }.sortedBy { it.date }.map { day ->
                TransferDay(
                    date = day.date,
                    note = day.note.ifBlank { null },
                    segments = day.segments.sortedBy { it.startEpochSec }.map {
                        TransferSegment(
                            start = it.startEpochSec,
                            end = it.endEpochSec,
                            kmStart = it.odometerStartKm,
                            kmEnd = it.odometerEndKm,
                            plate = it.licensePlate,
                            backupId = it.backupId,
                        )
                    },
                )
            },
            // Kirjaukset aikajärjestyksessä (malli on uusin ensin).
            fuel = model.entries.reversed().map { entry ->
                val it = entry.fillup
                TransferFuel(
                    at = it.epochSec,
                    plate = it.licensePlate,
                    km = it.odometerKm,
                    liters = it.liters,
                    cost = it.totalCost,
                    price = it.pricePerLiter,
                    backupId = it.backupId,
                    kind = it.kind.name,
                    full = it.fullTank,
                    currency = it.currency,
                    photos = entry.photos.map { TransferPhoto(it.photo.backupId, it.photo.addedAtEpochSec) },
                    note = it.note.ifBlank { null },
                )
            },
            cars = model.carProfiles.map { TransferCar(it.plate, it.fuel?.name, it.currency) },
            period = model.period.bounds()?.let { (from, to) -> TransferPeriod(from.toEpochDay(), to.toEpochDay()) },
            language = locale.language.tag,
            templates = if (period is ExportPeriod.Full) exportTemplates() else emptyList(),
            poiCorrections = if (period is ExportPeriod.Full) poiDao.allCorrections().map { TransferPoiCorrection(it.poiId, it.field, it.value, it.epochSec) } else emptyList(),
            poiPrices = if (period is ExportPeriod.Full) poiDao.allPrices().map { TransferPoiPrice(it.poiId, it.fuel.name, it.price, it.currency, it.epochDay) } else emptyList(),
        )
        writeReport(
            out, model, reportStrings(locale.language), ReportFormat(locale, zone), reportPhotos,
            json.encodeToString(transfer), nowEpochSec,
        )
    }

    /**
     * Tallennetut reitit vain koko varmuuskopioon: pohjissa on asiakasosoitteita, joita jakson raportti ei muuten
     * sisällä.
     */
    private suspend fun exportTemplates(): List<TransferTemplate> = templateDao.all().map { template ->
        TransferTemplate(
            name = template.name,
            created = template.createdEpochSec,
            updated = template.updatedEpochSec,
            stops = templateDao.stopsFor(template.id).map {
                TransferTemplateStop(it.label, it.address, it.placeId, it.lat, it.lng, it.deadlineMinutesOfDay, it.serviceSec)
            },
        )
    }

    /** Viennin paneelin yhteenveto: jakson määrät ja arvioitu tiedostokoko kuvatiedostojen koosta. */
    suspend fun exportSummary(period: ExportPeriod): ExportSummary = withContext(Dispatchers.IO) {
        summarizeExport(
            loadReportModel(period, ZoneId.systemDefault()),
            templates = if (period is ExportPeriod.Full) templateDao.all().size else 0,
        ) { photoStore.file(it.fileName).length() }
    }

    /** Raportin malli jaksolta. Vain kuvat, joiden tiedosto on tallessa: JSON ja kuvarivit pysyvät samoina. */
    private suspend fun loadReportModel(period: ExportPeriod, zone: ZoneId): ReportModel {
        val photosByFillup = photoRepository.all().filter { photoStore.file(it.fileName).isFile }.groupBy { it.fillupId }
        return reportModel(period, workSegmentDao.all(), routeDayDao.all(), fuelFillupDao.all(), photosByFillup, carDao.all(), zone)
    }

    /**
     * Tuo HTML-varmuuskopion kirjaukset. V3 tunnistaa muokatutkin rivit
     * pysyvillä tunnisteilla; v1/v2 käyttää sisällöstä saatavaa tunnistusta.
     * V4 kantaa tankkauksen lajin, täyden tankin ja valuutan sekä autoprofiilit;
     * vanhempien tankkaukset ovat täysiä dieseltankkauksia euroissa
     * ([FuelDedupe] vertaa niitä ilman valuuttaa, lajia ja täyttä).
     * Olemassa olevat lukemat säilyvät ja puuttuva rekkari voidaan täydentää;
     * varmuuskopio tuo vain puhelimesta puuttuvat autot. V5 kantaa kirjausten kuvat:
     * ne liittyvät myös puhelimessa jo olevaan kirjaukseen, ja jo oleva kuva ohitetaan.
     */
    suspend fun importFromHtml(content: String): WorkImportResult = importFromHtml(content.byteInputStream())

    /**
     * Varmuuskopion tuonti virrasta: kuvarivit ([PHOTO_LINE]) puretaan heti odottaviksi tiedostoiksi, ja muu teksti jää
     * muistiin JSONin lukua varten. Kuvat liitetään kirjauksiin tunnisteella; käyttämättömät odottavat poistetaan.
     */
    suspend fun importFromHtml(input: InputStream): WorkImportResult {
        val backup = withContext(Dispatchers.IO) { readBackup(input) }
        try {
            return importBackup(backup.text, backup.photos)
        } finally {
            backup.photos.values.forEach(photoStore::discard)
        }
    }

    private class Backup(val text: String, val photos: Map<String, File>)

    private fun readBackup(input: InputStream): Backup {
        val text = StringBuilder()
        val photos = LinkedHashMap<String, File>()
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    when (backupLine(line)) {
                        BackupLine.TEXT -> text.append(line).append('\n')
                        // Gallerian pienoiskuvat eivät kuulu varmuuskopioon eivätkä jää muistiin.
                        BackupLine.THUMBNAIL -> Unit
                        BackupLine.PHOTO -> readPhoto(line)?.let { (id, file) ->
                            photos.put(id, file)?.let(photoStore::discard)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            photos.values.forEach(photoStore::discard)
            throw e
        }
        return Backup(text.toString(), photos)
    }

    /** Kuvarivi odottavaksi tiedostoksi; virheellinen tai liian suuri ohitetaan. */
    private fun readPhoto(line: String): Pair<String, File>? {
        val idEnd = line.indexOf('"', PHOTO_LINE.length)
        // Haku eikä attribuutin jäsennys: ennen 17.10. tehdyissä vienneissä rivi on muotoa src="src="data:…,
        // ja niidenkin kuvat pitää saada takaisin.
        val dataStart = line.indexOf(PHOTO_DATA).takeIf { it >= 0 }?.plus(PHOTO_DATA.length) ?: return null
        val dataEnd = line.lastIndexOf("\">")
        if (idEnd < 0 || dataEnd < dataStart) return null
        val id = line.substring(PHOTO_LINE.length, idEnd)
        if (!PHOTO_ID.matches(id) || (dataEnd - dataStart) / 4L * 3 > MAX_PHOTO_BYTES) return null
        val bytes = runCatching { Base64.getDecoder().decode(line.substring(dataStart, dataEnd)) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return id to photoStore.newPending().apply { writeBytes(bytes) }
    }

    private suspend fun importBackup(content: String, photos: Map<String, File>): WorkImportResult = writeMutex.withLock {
        val match = DATA_PATTERN.find(content)
            ?: throw BackupRejectedException(R.string.import_not_backup, "Tiedostosta ei löytynyt Stopin työmatkakirjauksia.")
        val transfer = runCatching { json.decodeFromString<TransferFile>(match.groupValues[1].trim()) }
            .getOrElse { throw BackupRejectedException(R.string.import_unreadable, "Tiedoston kirjaukset eivät kelpaa: ${it.message}") }

        requireBackup(transfer.version in 1..8, R.string.import_version) { "Varmuuskopion versiota ei tueta." }
        transfer.days.forEach { LocalDate.ofEpochDay(it.date) }
        transfer.period?.let {
            requireBackup(LocalDate.ofEpochDay(it.from) <= LocalDate.ofEpochDay(it.to), R.string.import_bad_period) { "Varmuuskopion jakso ei kelpaa." }
        }
        transfer.fuel.forEach { f ->
            requireBackup(normalizePlate(f.plate) != null && f.km >= 0 && f.liters.isFinite() && f.liters > 0 &&
                f.cost.isFinite() && f.cost >= 0 && f.price.isFinite() && f.price >= 0 &&
                (f.kind == null || FuelKind.entries.any { it.name == f.kind }) &&
                (f.currency == null || isCurrencyCode(f.currency)), invalidFuelRes(f.kind)) {
                invalidFuelMessage(f.kind)
            }
            Instant.ofEpochSecond(f.at).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        transfer.cars.forEach { c ->
            requireBackup(normalizePlate(c.plate) != null &&
                (c.fuel == null || FuelKind.mainFuels.any { it.name == c.fuel }) &&
                (c.currency == null || isCurrencyCode(c.currency)), R.string.import_bad_car) {
                "Varmuuskopiossa on virheellinen auto. Tietoja ei tuotu."
            }
        }
        transfer.templates.forEach { t ->
            requireBackup(normalizeTemplateName(t.name).isNotEmpty() && t.stops.all { s ->
                s.lat.isFinite() && s.lng.isFinite() && s.lat in -90.0..90.0 && s.lng in -180.0..180.0 &&
                    (s.deadline == null || s.deadline in 0 until 24 * 60) && (s.service == null || s.service >= 0)
            }, R.string.import_bad_template) {
                "Varmuuskopiossa on virheellinen tallennettu reitti. Tietoja ei tuotu."
            }
        }
        transfer.poiCorrections.forEach { c ->
            requireBackup(POI_ID.matches(c.poi) && PoiCorrectionField.of(c.field) != null && c.value.isNotBlank() && c.value.length <= 200 && c.at >= 0, R.string.import_bad_place) {
                INVALID_POI_EDIT
            }
        }
        transfer.poiPrices.forEach { p ->
            requireBackup(POI_ID.matches(p.poi) && FuelKind.entries.any { it.name == p.fuel } && p.price.isFinite() && p.price > 0 &&
                isCurrencyCode(p.currency) && p.day in 0..200_000, R.string.import_bad_place) {
                INVALID_POI_EDIT
            }
        }
        // Autot, joilla on puhelimessa kirjauksia ennen tätä tuontia (autoprofiilien täydennys alla).
        val platesWithRecords = (workSegmentDao.all().mapNotNull { it.licensePlate } + fuelFillupDao.all().map { it.licensePlate }).toSet()
        var added = 0
        var skipped = 0
        transfer.days.forEach { day ->
            // IGNORE-insert palauttaa -1, jos kilpaileva polku ehti luoda
            // saman päivän — silloin haetaan olemassa oleva rivi.
            val dayId = routeDayDao.byDate(day.date)?.id
                ?: routeDayDao.insert(RouteDayEntity(dateEpochDay = day.date))
                    .takeIf { it != -1L }
                ?: requireNotNull(routeDayDao.byDate(day.date)).id
            // Päivän lisätiedot: tyhjä täydennetään varmuuskopiosta, olemassa olevaa ei ylikirjoiteta (määrittely 20.10.).
            val dayNote = normalizeNote(day.note)
            if (dayNote.isNotEmpty()) {
                routeDayDao.fillNoteIfEmpty(dayId, dayNote)
            }
            // Päivittyvä tilannekuva: myös saman tiedoston sisäiset tuplat
            // ohitetaan, ei vain kannassa jo olevat.
            val existing = workSegmentDao.forDay(dayId).toMutableList()
            day.segments.forEach { seg ->
                val plate = seg.plate?.let(::normalizePlate)
                // Sisältöavain: sama alkuhetki tai samat lukemat.
                fun sameContent(e: WorkSegmentEntity): Boolean =
                    e.startEpochSec == seg.start ||
                        ((seg.kmStart != null || seg.kmEnd != null) &&
                            e.odometerStartKm == seg.kmStart && e.odometerEndKm == seg.kmEnd)
                val duplicate = if (!seg.backupId.isNullOrBlank()) {
                    // v3: pysyvä tunniste TAI sama sisältö samalla autolla — v2-
                    // palautuksen jälkeen tuotu v3 ei saa tuplata, vaikka tunnisteet
                    // ovat eri. Samanaikaiset eri autojen segmentit säilyvät.
                    existing.firstOrNull { it.backupId == seg.backupId }
                        ?: existing.firstOrNull { e ->
                            (e.licensePlate == null || plate == null || e.licensePlate == plate) && sameContent(e)
                        }
                } else {
                    // v1/v2: rekkaria ei verrata — jälkikäteen korjattu rekkari ei
                    // saa tuplata päivän kilometrejä.
                    existing.firstOrNull(::sameContent)
                }
                if (duplicate != null) {
                    skipped++
                    if (duplicate.licensePlate == null && plate != null) {
                        val updated = duplicate.copy(licensePlate = plate)
                        workSegmentDao.update(updated)
                        existing[existing.indexOf(duplicate)] = updated
                    }
                } else {
                    val entity = WorkSegmentEntity(
                        routeDayId = dayId,
                        startEpochSec = seg.start,
                        endEpochSec = seg.end,
                        odometerStartKm = seg.kmStart,
                        odometerEndKm = seg.kmEnd,
                        licensePlate = plate,
                        backupId = seg.backupId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                    )
                    val id = workSegmentDao.insert(entity)
                    existing.add(entity.copy(id = id))
                    added++
                }
            }
        }

        var fuelAdded = 0
        var fuelSkipped = 0
        var photosAdded = 0
        val fuelKinds = mutableSetOf<FuelKind>()
        val knownFuel = fuelFillupDao.all().toMutableList()
        val dedupe = FuelDedupe(knownFuel)
        // v1–v3: täysi dieseltankkaus euroissa.
        transfer.fuel.forEach { f ->
            val entity = FuelFillupEntity(
                epochSec = f.at,
                licensePlate = requireNotNull(normalizePlate(f.plate)),
                odometerKm = f.km,
                liters = f.liters,
                totalCost = f.cost,
                pricePerLiter = f.price,
                backupId = f.backupId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                kind = f.kind?.let(FuelKind::valueOf) ?: FuelKind.DIESEL,
                fullTank = f.full ?: true,
                currency = f.currency ?: APP_CURRENCY,
                note = normalizeNote(f.note),
            )
            fuelKinds += entity.kind
            // Vanha rivi tunnistetaan riveittäin: v4 kirjoittaa valuutan aina, mutta v1/v2-viennit eivät
            // kirjoittaneet edes versiota (oletusarvo jäi pois), joten tiedoston versio ei kerro sitä.
            val fillupId = if (!dedupe.isNew(entity, hasBackupId = !f.backupId.isNullOrBlank(), legacy = f.currency == null)) {
                fuelSkipped++
                // Kuvat liittyvät puhelimessa jo olevaan kirjaukseen: tunniste, muuten sama sisältö.
                val known = knownFuel.firstOrNull { it.backupId == entity.backupId } ?: knownFuel.firstOrNull { it.sameContent(entity) }
                // Lisätiedot: tyhjä täydennetään varmuuskopiosta, olemassa olevaa ei ylikirjoiteta. Ehdollinen päivitys
                // koskee vain note-saraketta, joten tuonnin aikana tehty muokkaus säilyy.
                if (known != null && known.note.isBlank() && entity.note.isNotEmpty()) {
                    if (fuelFillupDao.fillNoteIfEmpty(known.id, entity.note) > 0) {
                        knownFuel[knownFuel.indexOf(known)] = known.copy(note = entity.note)
                    }
                }
                known?.id
            } else {
                fuelAdded++
                fuelFillupDao.insert(entity).also { knownFuel.add(entity.copy(id = it)) }
            }
            if (fillupId != null) {
                f.photos.forEach { photo ->
                    val file = photos[photo.id] ?: return@forEach
                    if (photoRepository.restore(fillupId, file, photo.at, photo.id)) photosAdded++
                }
            }
        }
        // Käytetyn auton puhelimen rivi voittaa kokonaan: myös "Automaattinen" (null) on käyttäjän valinta, joten
        // varmuuskopio tuo kaikki puuttuvat profiilit, myös kokonaan automaattiset (lisäys on atominen, INSERT OR IGNORE).
        // Auto, jolla ei ennen tuontia ollut puhelimessa työjaksoja eikä kirjauksia (esittelyssä tai Autot-näkymässä
        // juuri annettu), saa puuttuvat tiedot varmuuskopiosta: uuden puhelimen esittely ei saa hävittää palautettavan
        // auton valuuttaa.
        transfer.cars.forEach { c ->
            val car = CarEntity(requireNotNull(normalizePlate(c.plate)), c.fuel?.let(FuelKind::valueOf), c.currency)
            if (carDao.insertIfMissing(car) == -1L && car.plate !in platesWithRecords) {
                carDao.fillMissing(car.plate, car.fuel, car.currency)
            }
        }
        WorkImportResult(
            added = added, skipped = skipped, fuelAdded = fuelAdded, fuelSkipped = fuelSkipped, photosAdded = photosAdded,
            fuelKinds = fuelKinds,
            period = transfer.period?.let { it.from to it.to },
            templates = importTemplates(transfer.templates),
            poiEdits = importPoiEdits(transfer.poiCorrections, transfer.poiPrices),
        )
    }

    /**
     * Karttakohteiden korjaukset ja vanhat hinnat: tiedoston rivi kirjoitetaan vain, kun laitteella ei ole uudempaa.
     * Hinnat eivät enää näy missään, joten palautettu määrä kertoo vain korjaukset.
     */
    private suspend fun importPoiEdits(corrections: List<TransferPoiCorrection>, prices: List<TransferPoiPrice>): Int {
        var written = 0
        corrections.forEach { c ->
            val existing = poiDao.correction(c.poi, c.field)
            if (existing == null || existing.epochSec < c.at) {
                poiDao.upsertCorrection(PoiCorrectionEntity(c.poi, c.field, c.value, c.at))
                written++
            }
        }
        prices.forEach { p ->
            val fuel = FuelKind.valueOf(p.fuel)
            val existing = poiDao.price(p.poi, fuel)
            if (existing == null || existing.epochDay < p.day) {
                poiDao.upsertPrice(PoiPriceEntity(p.poi, fuel, p.price, p.currency, p.day))
            }
        }
        return written
    }

    /**
     * Tallennetut reitit nimen perusteella: samanniminen (normalisoitu nimi) pohja saa varmuuskopion stopit ja
     * sen muutosajan, muut lisätään varmuuskopion ajoilla. Palauttaa tuotujen ja korvattujen määrän. Kirjoitukset
     * ovat pohjien yhteisen lukon alla ja yhdessä transaktiossa, jottei Suunnittelu-välilehden Korvaa lomitu tuonnin
     * kanssa eikä keskeytys jätä pohjaa tyhjäksi. Lukkojärjestys: oma kirjoituslukko ennen pohjalukkoa;
     * pohjarepositoriot eivät ota omaa lukkoa, joten umpikujaa ei synny.
     */
    private suspend fun importTemplates(templates: List<TransferTemplate>): Int {
        if (templates.isEmpty()) return 0
        return stopsLock.mutex.withLock { transactions.transaction { writeTemplates(templates) } }
    }

    private suspend fun writeTemplates(templates: List<TransferTemplate>): Int {
        val existing = templateDao.all().associateBy { normalizeTemplateName(it.name) }.toMutableMap()
        templates.forEach { t ->
            val name = normalizeTemplateName(t.name)
            val id = existing[name]?.id?.also { id ->
                templateDao.deleteStops(id)
                templateDao.touch(id, t.updated)
            } ?: templateDao.insert(RouteTemplateEntity(name = name, createdEpochSec = t.created, updatedEpochSec = t.updated))
                .also { id -> existing[name] = RouteTemplateEntity(id = id, name = name, createdEpochSec = t.created, updatedEpochSec = t.updated) }
            templateDao.insertStops(
                t.stops.mapIndexed { i, s ->
                    TemplateStopEntity(
                        templateId = id, orderIndex = i, label = s.label, address = s.address, placeId = s.placeId,
                        lat = s.lat, lng = s.lng, deadlineMinutesOfDay = s.deadline, serviceSec = s.service,
                    )
                },
            )
        }
        return templates.size
    }

    private fun FuelFillupEntity.sameContent(other: FuelFillupEntity): Boolean =
        copy(id = 0, backupId = "", currency = "", note = "") == other.copy(id = 0, backupId = "", currency = "", note = "")

    private companion object {
        const val INVALID_POI_EDIT = "Varmuuskopiossa on virheellinen kohdekorjaus. Tietoja ei tuotu."
        val POI_ID = Regex("[nwr][0-9]+")

        /** Raportti suomeksi suomalaisin numeroin, kun kieltä ei anneta (testit). */
        val DEFAULT_REPORT_LOCALE = ReportLocale(ReportLanguage.FI, finnishNumerals = true)
        val PHOTO_ID = Regex("[0-9a-fA-F-]{36}")
        /** Tuotavan kuvan enimmäiskoko: tallennettu kuva on tyypillisesti alle 1 Mt. */
        const val MAX_PHOTO_BYTES = 20L * 1024 * 1024
        val DATA_PATTERN = Regex(
            "<script[^>]*id=\"(?:tyomatka|stoppi)-data\"[^>]*>(.*?)</script>",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
