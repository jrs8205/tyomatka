package fi.tyomatka.data.db

/** v10 → v11: tallennetut reitit (pohjat) ja päivän pohjaviite. */
internal fun migrate10To11(db: MigrationSql) {
    db.exec(
        "CREATE TABLE IF NOT EXISTS `route_templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
            "`createdEpochSec` INTEGER NOT NULL, `updatedEpochSec` INTEGER NOT NULL, `lastOpenedEpochSec` INTEGER)",
        emptyList(),
    )
    db.exec("CREATE INDEX IF NOT EXISTS `index_route_templates_name` ON `route_templates` (`name`)", emptyList())
    db.exec(
        "CREATE TABLE IF NOT EXISTS `template_stops` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `templateId` INTEGER NOT NULL, " +
            "`orderIndex` INTEGER NOT NULL, `label` TEXT NOT NULL, `address` TEXT NOT NULL, `placeId` TEXT, `lat` REAL NOT NULL, " +
            "`lng` REAL NOT NULL, `deadlineMinutesOfDay` INTEGER, `serviceSec` INTEGER, " +
            "FOREIGN KEY(`templateId`) REFERENCES `route_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        emptyList(),
    )
    db.exec("CREATE INDEX IF NOT EXISTS `index_template_stops_templateId` ON `template_stops` (`templateId`)", emptyList())
    db.exec("ALTER TABLE `route_days` ADD COLUMN `templateId` INTEGER", emptyList())
    db.exec("ALTER TABLE `route_days` ADD COLUMN `templateAck` TEXT", emptyList())
}
