package fi.tyomatka.data.db

/** v12 → v13: kuljettajien ilmoitusten lähetysjono. */
internal fun migrate12To13(db: MigrationSql) {
    db.exec(
        "CREATE TABLE IF NOT EXISTS `pending_reports` (`rid` TEXT NOT NULL, `poiId` TEXT NOT NULL, `field` TEXT NOT NULL, " +
            "`value` TEXT NOT NULL, `createdEpochSec` INTEGER NOT NULL, `sentEpochSec` INTEGER, PRIMARY KEY(`rid`))",
        emptyList(),
    )
}
