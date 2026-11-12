package fi.tyomatka.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fi.tyomatka.BuildConfig
import fi.tyomatka.core.api.BusinessAddressSearch
import fi.tyomatka.core.api.BusinessClient
import fi.tyomatka.core.api.BusinessPlaceResolver
import fi.tyomatka.core.api.Defaults
import fi.tyomatka.core.api.FallbackPlaceSearch
import fi.tyomatka.core.api.FallbackReverseGeocoder
import fi.tyomatka.core.api.Finland
import fi.tyomatka.core.api.OsrmClient
import fi.tyomatka.core.api.PeliasClient
import fi.tyomatka.core.api.ReverseGeocoder
import fi.tyomatka.core.api.PhotonClient
import fi.tyomatka.core.api.PlaceSearch
import fi.tyomatka.core.api.RateLimiter
import fi.tyomatka.core.api.RouteOverviewSource
import fi.tyomatka.core.api.UserAgentInterceptor
import fi.tyomatka.core.eta.EtaEngine
import fi.tyomatka.core.learn.HistoryLearner
import fi.tyomatka.data.db.AppDatabase
import fi.tyomatka.data.db.RoomMigrationSql
import fi.tyomatka.data.db.migrate7To8
import fi.tyomatka.data.db.migrate8To9
import fi.tyomatka.data.db.migrate9To10
import fi.tyomatka.data.db.migrate10To11
import fi.tyomatka.data.db.migrate11To12
import fi.tyomatka.data.db.migrate12To13
import fi.tyomatka.data.photo.PhotoStore
import fi.tyomatka.data.photo.PhotoThumbnailer
import fi.tyomatka.data.repo.ServerUrls
import fi.tyomatka.data.repo.SharedSyncPrefs
import fi.tyomatka.data.repo.SyncSettings
import fi.tyomatka.data.report.ReportPhotos
import fi.tyomatka.data.repo.APP_CURRENCY
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "learned_stats")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // Säilyttävä migraatio, ettei osoitemuisti ja historia pyyhkiydy päivityksessä.
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `leg_stats` (" +
                    "`fromKey` TEXT NOT NULL, `toKey` TEXT NOT NULL, " +
                    "`ewmaSec` INTEGER NOT NULL, `samples` INTEGER NOT NULL, " +
                    "`updatedEpochSec` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`fromKey`, `toKey`))"
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `work_segments` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`routeDayId` INTEGER NOT NULL, " +
                    "`startEpochSec` INTEGER NOT NULL, " +
                    "`endEpochSec` INTEGER, " +
                    "`odometerStartKm` INTEGER, " +
                    "`odometerEndKm` INTEGER)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_segments_routeDayId` " +
                    "ON `work_segments` (`routeDayId`)"
            )
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Jos samalle päivälle on ehtinyt syntyä kaksi riviä, siirrä lapset
            // vanhimmalle ja poista tuplat ennen uniikki-indeksin luontia —
            // muuten indeksin luonti kaatuisi ja data jäisi jumiin.
            for (table in listOf("stops", "leg_records", "service_records", "work_segments")) {
                db.execSQL(
                    "UPDATE `$table` SET routeDayId = (" +
                        "SELECT MIN(id) FROM route_days WHERE dateEpochDay = " +
                        "(SELECT dateEpochDay FROM route_days WHERE id = `$table`.routeDayId))"
                )
            }
            // Välimuisti on kertakäyttöistä — tuplapäivän rivi vain pois.
            db.execSQL(
                "DELETE FROM matrix_cache WHERE routeDayId NOT IN " +
                    "(SELECT MIN(id) FROM route_days GROUP BY dateEpochDay)"
            )
            db.execSQL(
                "DELETE FROM route_days WHERE id NOT IN " +
                    "(SELECT MIN(id) FROM route_days GROUP BY dateEpochDay)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_route_days_dateEpochDay` " +
                    "ON `route_days` (`dateEpochDay`)"
            )
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `work_segments` ADD COLUMN `licensePlate` TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `fuel_fillups` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`epochSec` INTEGER NOT NULL, " +
                    "`licensePlate` TEXT NOT NULL, " +
                    "`odometerKm` INTEGER NOT NULL, " +
                    "`liters` REAL NOT NULL, " +
                    "`totalCost` REAL NOT NULL, " +
                    "`pricePerLiter` REAL NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_fuel_fillups_licensePlate` " +
                    "ON `fuel_fillups` (`licensePlate`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_fuel_fillups_epochSec` " +
                    "ON `fuel_fillups` (`epochSec`)"
            )
        }
    }

    internal val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `fuel_fillups` ADD COLUMN `backupId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `fuel_fillups` SET `backupId` = lower(hex(randomblob(16)))")
            db.execSQL("ALTER TABLE `work_segments` ADD COLUMN `backupId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `work_segments` SET `backupId` = lower(hex(randomblob(16)))")
        }
    }

    /** v7 → v8 ([migrate7To8]); olemassa olevat tankkaukset saavat valuutan. */
    internal fun migration7To8(appCurrency: String) = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) = migrate7To8(RoomMigrationSql(db), appCurrency)
    }

    /** v8 → v9 ([migrate8To9]): tankkausten kuvat. */
    internal val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) = migrate8To9(RoomMigrationSql(db))
    }

    /** v9 → v10 ([migrate9To10]): lisätiedot päivälle ja tankkaukselle. */
    internal val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) = migrate9To10(RoomMigrationSql(db))
    }

    internal val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) = migrate10To11(RoomMigrationSql(db))
    }

    /** v11 → v12 ([migrate11To12]): karttakohteiden välimuisti, korjaukset ja hinnat. */
    internal val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) = migrate11To12(RoomMigrationSql(db))
    }

    internal val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) = migrate12To13(RoomMigrationSql(db))
    }

    // EI fallbackToDestructiveMigrationia: puuttuva migraatio saa mieluummin
    // kaatua heti kehityksessä kuin pyyhkiä osoitemuistin ja oppimisdatan.
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "tyomatka.db")
            .addMigrations(
                MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                migration7To8(APP_CURRENCY),
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
            )
            .build()

    @Provides
    fun routeDayDao(db: AppDatabase) = db.routeDayDao()

    @Provides
    fun stopDao(db: AppDatabase) = db.stopDao()

    @Provides
    fun routeTemplateDao(db: AppDatabase) = db.routeTemplateDao()

    @Provides
    fun poiDao(db: AppDatabase) = db.poiDao()

    @Provides
    fun reportDao(db: AppDatabase) = db.reportDao()

    /** Useamman rivin kirjoitukset Room-transaktiossa; testit antavat oman toteutuksen. */
    @Provides
    fun transactionRunner(db: AppDatabase): fi.tyomatka.data.repo.TransactionRunner = fi.tyomatka.data.repo.RoomTransactionRunner(db)

    @Provides
    fun historyDao(db: AppDatabase) = db.historyDao()

    @Provides
    fun matrixCacheDao(db: AppDatabase) = db.matrixCacheDao()

    @Provides
    fun savedAddressDao(db: AppDatabase) = db.savedAddressDao()

    @Provides
    fun legStatDao(db: AppDatabase) = db.legStatDao()

    @Provides
    fun workSegmentDao(db: AppDatabase) = db.workSegmentDao()

    @Provides
    fun fuelFillupDao(db: AppDatabase) = db.fuelFillupDao()

    @Provides
    fun carDao(db: AppDatabase) = db.carDao()

    @Provides
    fun fuelPhotoDao(db: AppDatabase) = db.fuelPhotoDao()

    @Provides
    @Singleton
    fun photoStore(@ApplicationContext context: Context): PhotoStore = PhotoStore(context.filesDir, context.cacheDir)

    @Provides
    @Singleton
    fun reportPhotos(store: PhotoStore): ReportPhotos = PhotoThumbnailer(store)

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore

    @Provides
    @Singleton
    fun syncSettings(@ApplicationContext context: Context): SyncSettings = SyncSettings(SharedSyncPrefs(context))

    /** Luetaan kerran käynnistyksessä: muutos asetuksista tulee voimaan seuraavalla käynnistyksellä. */
    @Provides
    @Singleton
    fun serverUrls(settings: SyncSettings): ServerUrls = settings.servers(defaultServers())

    @Provides
    @Singleton
    fun okHttp(interceptors: Set<@JvmSuppressWildcards okhttp3.Interceptor>): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor(BuildConfig.VERSION_NAME))
            // Debug-buildin diagnostiikkaloki kirjaa pyynnöt; julkaisuversiossa joukko on tyhjä.
            .apply { interceptors.forEach { addInterceptor(it) } }
            .build()

    // Yhteinen kuristin: vapaaehtoisten palvelimet saavat enintään yhden pyynnön sekunnissa.
    @Provides
    @Singleton
    fun rateLimiter(): RateLimiter = RateLimiter()

    @Provides
    @Singleton
    fun routeOverviewSource(okHttp: OkHttpClient, limiter: RateLimiter, servers: ServerUrls): RouteOverviewSource =
        OsrmClient(okHttp, servers.osrm, limiter)

    /** Suomen virallinen osoitehaku (Digitransit) sovelluksen palvelimen kautta. Virhe siirtää haun Photonille. */
    private fun finlandSearch(okHttp: OkHttpClient, servers: ServerUrls): PeliasClient =
        PeliasClient(okHttp, servers.geocode, source = "© Digitransit")

    /**
     * Käänteishaku koordinaateista lähimpään osoitteeseen: Digitransit ja sen varalla Suomeen rajattu Photon. Sama
     * palvelu yrityksen stopille, kun talonumero ei täsmää, ja karttakohteesta tehdylle stopille.
     */
    @Provides
    @Singleton
    fun reverseGeocoder(okHttp: OkHttpClient, servers: ServerUrls): ReverseGeocoder =
        FallbackReverseGeocoder(listOf(finlandSearch(okHttp, servers), finlandPhoton(okHttp, servers)))

    @Provides
    @Singleton
    fun placeSearch(okHttp: OkHttpClient, reverse: ReverseGeocoder, servers: ServerUrls): PlaceSearch {
        // Vain Suomen (Ahvenanmaa mukaan lukien) osoitteet: Digitransit ja sen varalla Suomeen rajattu Photon.
        val addresses = FallbackPlaceSearch(listOf(finlandSearch(okHttp, servers), finlandPhoton(okHttp, servers)))
        // Yritykset (Overture Places) omasta Workerista osoitehaun rinnalla; valitun yrityksen
        // osoite osoitehausta tai käänteishaulla ([reverseGeocoder]). Sama palvelin kuin karttakohteilla.
        val businesses = BusinessClient(okHttp, servers.poi)
        val resolver = BusinessPlaceResolver({ line, bias -> addresses.geocodeText(line, bias) }, reverse)
        return BusinessAddressSearch(addresses, businesses, resolver)
    }

    /** Photon (OSM-data) rajattuna Suomeen: haku kirjoitettaessa on sallittu, oma kevyt kuristus. */
    private fun finlandPhoton(okHttp: OkHttpClient, servers: ServerUrls): PhotonClient =
        PhotonClient(okHttp, servers.photon, RateLimiter(minIntervalMs = 300), bbox = Defaults.FINLAND_BBOX, countries = Finland.COUNTRIES)

    @Provides
    @Singleton
    fun etaEngine(): EtaEngine = EtaEngine()

    @Provides
    @Singleton
    fun learner(): HistoryLearner = HistoryLearner()
}
