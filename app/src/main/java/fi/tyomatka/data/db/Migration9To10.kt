package fi.tyomatka.data.db

/** v9 → v10: Lisätiedot-teksti päivälle ja tankkaukselle. */
internal fun migrate9To10(db: MigrationSql) {
    db.exec("ALTER TABLE `route_days` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''", emptyList())
    db.exec("ALTER TABLE `fuel_fillups` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''", emptyList())
}
