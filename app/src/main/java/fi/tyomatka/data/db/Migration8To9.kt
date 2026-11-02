package fi.tyomatka.data.db

/**
 * Room v8 → v9: tankkausten kuvat ([FuelPhotoEntity]). Taulun ja indeksin SQL on sama, jonka Room generoi
 * entiteetistä (Room vertaa skeemaa avatessaan); olemassa olevat taulut eivät muutu.
 */
internal fun migrate8To9(db: MigrationSql) {
    db.exec(
        "CREATE TABLE IF NOT EXISTS `fuel_photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`fillupId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `addedAtEpochSec` INTEGER NOT NULL, " +
            "`backupId` TEXT NOT NULL, FOREIGN KEY(`fillupId`) REFERENCES `fuel_fillups`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        emptyList(),
    )
    db.exec("CREATE INDEX IF NOT EXISTS `index_fuel_photos_fillupId` ON `fuel_photos` (`fillupId`)", emptyList())
}
