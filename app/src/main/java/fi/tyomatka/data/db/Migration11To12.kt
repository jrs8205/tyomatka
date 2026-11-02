package fi.tyomatka.data.db

/** v11 → v12: karttakohteiden välimuisti, kuljettajan korjaukset ja hinnat. */
internal fun migrate11To12(db: MigrationSql) {
    db.exec(
        "CREATE TABLE IF NOT EXISTS `poi_cache` (`key` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedEpochSec` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        emptyList(),
    )
    db.exec(
        "CREATE TABLE IF NOT EXISTS `poi_corrections` (`poiId` TEXT NOT NULL, `field` TEXT NOT NULL, `value` TEXT NOT NULL, " +
            "`epochSec` INTEGER NOT NULL, PRIMARY KEY(`poiId`, `field`))",
        emptyList(),
    )
    db.exec(
        "CREATE TABLE IF NOT EXISTS `poi_prices` (`poiId` TEXT NOT NULL, `fuel` TEXT NOT NULL, `price` REAL NOT NULL, " +
            "`currency` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, PRIMARY KEY(`poiId`, `fuel`))",
        emptyList(),
    )
}
