package fi.tyomatka.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RouteDayEntity::class,
        StopEntity::class,
        LegRecordEntity::class,
        ServiceRecordEntity::class,
        MatrixCacheEntity::class,
        SavedAddressEntity::class,
        LegStatEntity::class,
        WorkSegmentEntity::class,
        FuelFillupEntity::class,
        CarEntity::class,
        FuelPhotoEntity::class,
        RouteTemplateEntity::class,
        TemplateStopEntity::class,
        PoiCacheEntity::class,
        PoiCorrectionEntity::class,
        PoiPriceEntity::class,
        PendingReportEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDayDao(): RouteDayDao
    abstract fun stopDao(): StopDao
    abstract fun historyDao(): HistoryDao
    abstract fun matrixCacheDao(): MatrixCacheDao
    abstract fun savedAddressDao(): SavedAddressDao
    abstract fun legStatDao(): LegStatDao
    abstract fun workSegmentDao(): WorkSegmentDao
    abstract fun fuelFillupDao(): FuelFillupDao
    abstract fun carDao(): CarDao
    abstract fun fuelPhotoDao(): FuelPhotoDao
    abstract fun routeTemplateDao(): RouteTemplateDao
    abstract fun poiDao(): PoiDao
    abstract fun reportDao(): ReportDao
}
