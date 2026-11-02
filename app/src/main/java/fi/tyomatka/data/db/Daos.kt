package fi.tyomatka.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDayDao {
    /** IGNORE: uniikki dateEpochDay — kilpaileva luonti palauttaa -1,
     *  jolloin kutsuja hakee olemassa olevan rivin. */
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insert(day: RouteDayEntity): Long

    /** Koko rivin kirjoitus vain tuonnille; muut kirjoittajat päivittävät kentän kerrallaan, etteivät ne yliaja toisiaan. */
    @Update
    suspend fun update(day: RouteDayEntity)

    /** Ajon aloitus: aika vain, kun sitä ei ole (jatkettu päivä pitää ensimmäisen). */
    @Query("UPDATE route_days SET startedAtEpochSec = :epochSec WHERE id = :id AND startedAtEpochSec IS NULL")
    suspend fun setStartedIfNull(id: Long, epochSec: Long)

    @Query("UPDATE route_days SET finishedAtEpochSec = :epochSec WHERE id = :id")
    suspend fun setFinished(id: Long, epochSec: Long)

    @Query("UPDATE route_days SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String)

    @Query("UPDATE route_days SET note = :note WHERE dateEpochDay = :epochDay")
    suspend fun setNoteByDate(epochDay: Long, note: String)

    /** Lisätiedon täydennys vain tyhjään kenttään kirjoitushetkellä (tuonti); muut sarakkeet koskematta. */
    @Query("UPDATE route_days SET note = :note WHERE id = :id AND note = ''")
    suspend fun fillNoteIfEmpty(id: Long, note: String): Int

    @Query("SELECT * FROM route_days WHERE dateEpochDay = :epochDay LIMIT 1")
    suspend fun byDate(epochDay: Long): RouteDayEntity?

    @Query("SELECT * FROM route_days WHERE id = :id")
    suspend fun byId(id: Long): RouteDayEntity?

    /** Päivän rivi tarkkailtavana: pohjaviite ja kuittaus muuttuvat muutosrivin mukana. */
    @Query("SELECT * FROM route_days WHERE id = :id")
    fun observeById(id: Long): Flow<RouteDayEntity?>

    @Query("SELECT * FROM route_days ORDER BY dateEpochDay DESC")
    fun observeAll(): Flow<List<RouteDayEntity>>

    @Query("SELECT * FROM route_days")
    suspend fun all(): List<RouteDayEntity>

    @Query("DELETE FROM route_days WHERE id = :id")
    suspend fun delete(id: Long)

    /** Avattu pohja; nollaa samalla Myöhemmin-kuittauksen. */
    @Query("UPDATE route_days SET templateId = :templateId, templateAck = NULL WHERE id = :id")
    suspend fun setTemplate(id: Long, templateId: Long?)

    @Query("UPDATE route_days SET templateAck = :ack WHERE id = :id")
    suspend fun setTemplateAck(id: Long, ack: String?)

    /** Pohjan poisto: päivät eivät saa viitata kadonneeseen pohjaan. */
    @Query("UPDATE route_days SET templateId = NULL, templateAck = NULL WHERE templateId = :templateId")
    suspend fun clearTemplate(templateId: Long)
}

@Dao
interface RouteTemplateDao {
    @Insert
    suspend fun insert(template: RouteTemplateEntity): Long

    @Insert
    suspend fun insertStops(stops: List<TemplateStopEntity>)

    @Query(
        "SELECT t.*, (SELECT COUNT(*) FROM template_stops s WHERE s.templateId = t.id) AS stopCount " +
            "FROM route_templates t ORDER BY t.name COLLATE NOCASE"
    )
    fun observeAll(): Flow<List<TemplateListItem>>

    @Query("SELECT * FROM route_templates ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<RouteTemplateEntity>

    @Query("SELECT * FROM route_templates WHERE id = :id")
    suspend fun byId(id: Long): RouteTemplateEntity?

    @Query("SELECT * FROM route_templates WHERE id = :id")
    fun observeById(id: Long): Flow<RouteTemplateEntity?>

    @Query("SELECT * FROM route_templates WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): RouteTemplateEntity?

    @Query("SELECT * FROM template_stops WHERE templateId = :templateId ORDER BY orderIndex")
    suspend fun stopsFor(templateId: Long): List<TemplateStopEntity>

    @Query("SELECT * FROM template_stops WHERE templateId = :templateId ORDER BY orderIndex")
    fun observeStops(templateId: Long): Flow<List<TemplateStopEntity>>

    @Query("DELETE FROM template_stops WHERE templateId = :templateId")
    suspend fun deleteStops(templateId: Long)

    @Query("UPDATE route_templates SET name = :name, updatedEpochSec = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE route_templates SET updatedEpochSec = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE route_templates SET lastOpenedEpochSec = :now WHERE id = :id")
    suspend fun setLastOpened(id: Long, now: Long)

    @Query("DELETE FROM route_templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StopDao {
    @Insert
    suspend fun insert(stop: StopEntity): Long

    @Update
    suspend fun update(stop: StopEntity)

    @Query("SELECT * FROM stops WHERE id = :id")
    suspend fun byId(id: Long): StopEntity?

    @Query("SELECT * FROM stops WHERE routeDayId = :dayId ORDER BY orderIndex")
    fun observeForDay(dayId: Long): Flow<List<StopEntity>>

    @Query("SELECT * FROM stops WHERE routeDayId = :dayId ORDER BY orderIndex")
    suspend fun forDay(dayId: Long): List<StopEntity>

    @Query("DELETE FROM stops WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE stops SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun setOrder(id: Long, orderIndex: Int)

    @Query("UPDATE stops SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: StopStatus)

    // Kenttäkohtaiset päivitykset: koko rivin luku-muokkaa-kirjoita voisi
    // kirjoittaa samanaikaisen tilasiirtymän tai aikarajamuutoksen yli.

    @Query("UPDATE stops SET label = :label WHERE id = :id")
    suspend fun setLabel(id: Long, label: String)

    @Query(
        "UPDATE stops SET label = :label, address = :address, placeId = :placeId, " +
            "lat = :lat, lng = :lng WHERE id = :id"
    )
    suspend fun updatePlace(id: Long, label: String, address: String, placeId: String?, lat: Double, lng: Double)

    @Query("UPDATE stops SET deadlineMinutesOfDay = :minutesOfDay WHERE id = :id")
    suspend fun setDeadline(id: Long, minutesOfDay: Int?)

    @Query("UPDATE stops SET serviceSec = :serviceSec WHERE id = :id")
    suspend fun setServiceSec(id: Long, serviceSec: Long?)

    @Query("UPDATE stops SET status = :status, arrivedAtEpochSec = :arrivedAtEpochSec WHERE id = :id")
    suspend fun markArrived(id: Long, status: StopStatus, arrivedAtEpochSec: Long)

    @Query("UPDATE stops SET status = :status, departedAtEpochSec = :departedAtEpochSec WHERE id = :id")
    suspend fun markDone(id: Long, status: StopStatus, departedAtEpochSec: Long)

    @Query("SELECT COUNT(*) FROM stops WHERE routeDayId = :dayId")
    suspend fun countForDay(dayId: Long): Int

    @Query("DELETE FROM stops WHERE routeDayId = :dayId")
    suspend fun deleteForDay(dayId: Long)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertLeg(record: LegRecordEntity)

    @Insert
    suspend fun insertService(record: ServiceRecordEntity)

    @Query("SELECT * FROM leg_records WHERE routeDayId = :dayId")
    suspend fun legsForDay(dayId: Long): List<LegRecordEntity>

    @Query("SELECT * FROM service_records WHERE routeDayId = :dayId")
    suspend fun servicesForDay(dayId: Long): List<ServiceRecordEntity>

    @Query("SELECT AVG(durationSec) FROM service_records")
    suspend fun avgServiceSec(): Double?

    @Query("DELETE FROM leg_records WHERE routeDayId = :dayId")
    suspend fun deleteLegsForDay(dayId: Long)

    @Query("DELETE FROM service_records WHERE routeDayId = :dayId")
    suspend fun deleteServicesForDay(dayId: Long)
}

@Dao
interface LegStatDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: LegStatEntity)

    @Query("SELECT * FROM leg_stats WHERE fromKey = :fromKey AND toKey = :toKey")
    suspend fun get(fromKey: String, toKey: String): LegStatEntity?

    @Query("SELECT * FROM leg_stats")
    suspend fun all(): List<LegStatEntity>
}

@Dao
interface SavedAddressDao {
    @Insert
    suspend fun insert(address: SavedAddressEntity): Long

    @Update
    suspend fun update(address: SavedAddressEntity)

    @Query("SELECT * FROM saved_addresses WHERE placeId = :placeId LIMIT 1")
    suspend fun byPlaceId(placeId: String): SavedAddressEntity?

    @Query("SELECT * FROM saved_addresses WHERE address = :address LIMIT 1")
    suspend fun byAddress(address: String): SavedAddressEntity?

    @Query(
        "SELECT * FROM saved_addresses WHERE searchKey LIKE '%' || :key || '%' " +
            "ORDER BY useCount DESC, lastUsedEpochSec DESC LIMIT :limit"
    )
    suspend fun search(key: String, limit: Int = 8): List<SavedAddressEntity>

    @Query("SELECT * FROM saved_addresses ORDER BY lastUsedEpochSec DESC LIMIT :limit")
    suspend fun recent(limit: Int = 8): List<SavedAddressEntity>
}

@Dao
interface WorkSegmentDao {
    @Insert
    suspend fun insert(segment: WorkSegmentEntity): Long

    @Update
    suspend fun update(segment: WorkSegmentEntity)

    @Query("SELECT * FROM work_segments WHERE id = :id")
    suspend fun byId(id: Long): WorkSegmentEntity?

    @Query("SELECT * FROM work_segments WHERE routeDayId = :dayId ORDER BY startEpochSec")
    fun observeForDay(dayId: Long): Flow<List<WorkSegmentEntity>>

    @Query("SELECT * FROM work_segments WHERE routeDayId = :dayId ORDER BY startEpochSec")
    suspend fun forDay(dayId: Long): List<WorkSegmentEntity>

    @Query(
        "SELECT * FROM work_segments WHERE routeDayId = :dayId AND endEpochSec IS NULL " +
            "ORDER BY startEpochSec DESC LIMIT 1"
    )
    suspend fun openForDay(dayId: Long): WorkSegmentEntity?

    @Query("SELECT * FROM work_segments ORDER BY startEpochSec")
    fun observeAll(): Flow<List<WorkSegmentEntity>>

    @Query("SELECT * FROM work_segments ORDER BY startEpochSec")
    suspend fun all(): List<WorkSegmentEntity>
}

@Dao
interface MatrixCacheDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(cache: MatrixCacheEntity)

    @Query("SELECT * FROM matrix_cache WHERE routeDayId = :dayId")
    suspend fun get(dayId: Long): MatrixCacheEntity?

    @Query("DELETE FROM matrix_cache WHERE routeDayId = :dayId")
    suspend fun clear(dayId: Long)
}

@Dao
interface FuelFillupDao {
    @Insert
    suspend fun insert(fillup: FuelFillupEntity): Long

    @Update
    suspend fun update(fillup: FuelFillupEntity)

    /** Lisätiedon täydennys vain tyhjään kenttään kirjoitushetkellä (tuonti); muut sarakkeet koskematta. */
    @Query("UPDATE fuel_fillups SET note = :note WHERE id = :id AND note = ''")
    suspend fun fillNoteIfEmpty(id: Long, note: String): Int

    @Query("DELETE FROM fuel_fillups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM fuel_fillups WHERE id = :id")
    suspend fun byId(id: Long): FuelFillupEntity?

    @Query("SELECT * FROM fuel_fillups ORDER BY epochSec DESC, odometerKm DESC")
    fun observeAll(): Flow<List<FuelFillupEntity>>

    @Query("SELECT * FROM fuel_fillups ORDER BY epochSec, odometerKm")
    suspend fun all(): List<FuelFillupEntity>
}

@Dao
interface FuelPhotoDao {
    @Insert
    suspend fun insert(photo: FuelPhotoEntity): Long

    @Query("DELETE FROM fuel_photos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM fuel_photos WHERE fillupId = :fillupId ORDER BY addedAtEpochSec, id")
    suspend fun forFillup(fillupId: Long): List<FuelPhotoEntity>

    @Query("SELECT * FROM fuel_photos ORDER BY addedAtEpochSec, id")
    fun observeAll(): Flow<List<FuelPhotoEntity>>

    @Query("SELECT * FROM fuel_photos ORDER BY addedAtEpochSec, id")
    suspend fun all(): List<FuelPhotoEntity>

    @Query("SELECT * FROM fuel_photos WHERE backupId = :backupId LIMIT 1")
    suspend fun byBackupId(backupId: String): FuelPhotoEntity?
}

/** Välimuistirivin avain ja hakuhetki ilman runkoa. */
data class PoiCacheKey(val key: String, val fetchedEpochSec: Long)

@Dao
interface PoiDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putCache(entry: PoiCacheEntity)

    @Query("SELECT * FROM poi_cache WHERE `key` = :key")
    suspend fun cache(key: String): PoiCacheEntity?

    /**
     * Luokan kohdeikkunoiden avaimet (`minLon,minLat,maxLon,maxLat|luokka`), jotka on haettu hetken [since] jälkeen. Ilman
     * runkoa: alue rajataan avaimesta, ja vain valittujen rivien rungot luetaan ([cache]).
     */
    @Query("SELECT `key`, fetchedEpochSec FROM poi_cache WHERE `key` LIKE '%|' || :apiName AND fetchedEpochSec >= :since")
    suspend fun cacheKeys(apiName: String, since: Long): List<PoiCacheKey>

    /** Ilmoitusikkunoiden avaimet (`reports|minLon,minLat,maxLon,maxLat`), jotka on haettu hetken [since] jälkeen. */
    @Query("SELECT `key`, fetchedEpochSec FROM poi_cache WHERE `key` LIKE 'reports|%' AND fetchedEpochSec >= :since")
    suspend fun reportCacheKeys(since: Long): List<PoiCacheKey>

    @Query("DELETE FROM poi_cache WHERE `key` LIKE 'reports|%'")
    suspend fun deleteReportCache()

    @Query("DELETE FROM poi_cache WHERE fetchedEpochSec < :olderThan")
    suspend fun pruneCache(olderThan: Long): Int

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertCorrection(correction: PoiCorrectionEntity)

    @Query("DELETE FROM poi_corrections WHERE poiId = :poiId AND field = :field")
    suspend fun deleteCorrection(poiId: String, field: String)

    @Query("SELECT * FROM poi_corrections WHERE poiId = :poiId")
    fun observeCorrections(poiId: String): Flow<List<PoiCorrectionEntity>>

    @Query("SELECT * FROM poi_corrections")
    suspend fun allCorrections(): List<PoiCorrectionEntity>

    @Query("SELECT * FROM poi_corrections WHERE poiId = :poiId AND field = :field")
    suspend fun correction(poiId: String, field: String): PoiCorrectionEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertPrice(price: PoiPriceEntity)

    @Query("SELECT * FROM poi_prices")
    suspend fun allPrices(): List<PoiPriceEntity>

    @Query("SELECT * FROM poi_prices WHERE poiId = :poiId AND fuel = :fuel")
    suspend fun price(poiId: String, fuel: FuelKind): PoiPriceEntity?
}

@Dao
interface ReportDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(report: PendingReportEntity)

    /** Lähettämättömät, vanhin ensin: ilmoitukset lähtevät tekojärjestyksessä. */
    @Query(PENDING_SQL)
    suspend fun pending(): List<PendingReportEntity>

    /** Palvelimen kuittaamat omat ilmoitukset (säilyvät viikon). */
    @Query(SENT_SQL)
    suspend fun sent(): List<PendingReportEntity>

    /** Kaikki omat ilmoitukset; kortti ja merkit näyttävät ne heti ilman ikkunahakua. */
    @Query(ALL_SQL)
    fun observeAll(): Flow<List<PendingReportEntity>>

    @Query("SELECT * FROM pending_reports WHERE rid = :rid")
    suspend fun row(rid: String): PendingReportEntity?

    @Query("DELETE FROM pending_reports")
    suspend fun deleteAll()

    @Query("UPDATE pending_reports SET sentEpochSec = :sentEpochSec WHERE rid = :rid")
    suspend fun markSent(rid: String, sentEpochSec: Long)

    @Query("DELETE FROM pending_reports WHERE sentEpochSec IS NOT NULL AND sentEpochSec < :before")
    suspend fun pruneSent(before: Long): Int

    @Query("DELETE FROM pending_reports WHERE rid = :rid")
    suspend fun delete(rid: String)

    companion object {
        // Saman sekunnin ilmoitukset lisäysjärjestyksessä: tunnus on satunnainen, ja viimeisimmän valinnan pitää ratkaista.
        const val PENDING_SQL = "SELECT * FROM pending_reports WHERE sentEpochSec IS NULL ORDER BY createdEpochSec, rowid"
        const val SENT_SQL = "SELECT * FROM pending_reports WHERE sentEpochSec IS NOT NULL ORDER BY createdEpochSec, rowid"
        const val ALL_SQL = "SELECT * FROM pending_reports ORDER BY createdEpochSec, rowid"
    }
}

@Dao
interface CarDao {
    /** Lisäysjärjestyksessä: ensimmäinen profiili on oletusauto ennen kirjauksia (esittelyn auto). */
    @Query("SELECT * FROM cars ORDER BY rowid")
    fun observeAll(): Flow<List<CarEntity>>

    @Query("SELECT * FROM cars ORDER BY rowid")
    suspend fun all(): List<CarEntity>

    /**
     * Poistaa auton vain, jos sillä ei ole työjaksoja eikä kirjauksia, ja palauttaa poistettujen rivien määrän. Yksi
     * lause: samaan aikaan valmistuva varmuuskopion tuonti ei jää ilman käytetyn auton profiilia.
     */
    @Query(DELETE_IF_UNUSED)
    suspend fun deleteIfUnused(plate: String): Int

    /** Täyttää auton puuttuvat tiedot (varmuuskopion palautus); olemassa olevat arvot säilyvät. Yksi lause: atominen. */
    @Query("UPDATE cars SET fuel = COALESCE(fuel, :fuel), currency = COALESCE(currency, :currency) WHERE plate = :plate")
    suspend fun fillMissing(plate: String, fuel: FuelKind?, currency: String?)

    @Query("SELECT * FROM cars WHERE plate = :plate")
    suspend fun get(plate: String): CarEntity?

    @Upsert
    suspend fun upsert(car: CarEntity)

    /** Lisää vain puuttuvan auton yhdellä lauseella: olemassa oleva rivi voittaa, vaikka se tallennettaisiin samaan aikaan. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(car: CarEntity): Long

    /** Vaihtaa vain polttoaineen yhdellä lauseella: samaan aikaan valmistuva varmuuskopion tuonti ei jää alle. */
    @Query("UPDATE cars SET fuel = :fuel WHERE plate = :plate")
    suspend fun updateFuel(plate: String, fuel: FuelKind?)

    companion object {
        /** [deleteIfUnused]-lause; sama vakio testataan oikealla SQLitellä. */
        const val DELETE_IF_UNUSED = "DELETE FROM cars WHERE plate = :plate " +
            "AND NOT EXISTS (SELECT 1 FROM work_segments WHERE licensePlate = :plate) " +
            "AND NOT EXISTS (SELECT 1 FROM fuel_fillups WHERE licensePlate = :plate)"
    }
}
