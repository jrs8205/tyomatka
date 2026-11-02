package fi.tyomatka.data.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import fi.tyomatka.data.repo.isCurrencyCode

/** Migraation tarvitsema osa tietokannasta: Room ajaa saman koodin [RoomMigrationSql]illa, JVM-testi JDBC:llä. */
internal interface MigrationSql {
    fun exec(sql: String, args: List<Any?>)
    fun query(sql: String): List<List<Any?>>
}

/** Roomin tietokanta [MigrationSql]-muodossa. */
internal class RoomMigrationSql(private val db: SupportSQLiteDatabase) : MigrationSql {
    override fun exec(sql: String, args: List<Any?>) =
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())

    override fun query(sql: String): List<List<Any?>> = db.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    (0 until c.columnCount).map { i ->
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Room v7 → v8: tankkauksen laji, täysi tankki ja valuutta, autoprofiilit; opittujen ajoaikojen avaimet
 * koordinaateiksi. Sarakkeen oletus on vakio (Room vertaa sitä entiteetin `@ColumnInfo`-arvoon), valuutta
 * asetetaan olemassa oleville riveille päivityksellä. Vanhat tankkaukset ovat dieseliä; autoja ei täytetä, koska
 * oletus ei ole käyttäjän tieto. Paikkatunnisteavaimet `p:<placeId>` muutetaan [legKey]-avaimiksi osoitemuistin
 * tai stoppien koordinaateilla: osoitehaku käyttää nyt useaa palvelua, joten sama osoite saa eri tunnisteen
 * palvelusta riippuen. Törmäyksessä uusin opittu aika säilyy; muuntumattomat `p:`-rivit poistetaan.
 */
internal fun migrate7To8(db: MigrationSql, appCurrency: String) {
    require(isCurrencyCode(appCurrency)) { "Valuuttakoodi ei kelpaa: $appCurrency" }
    db.exec("ALTER TABLE `fuel_fillups` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'DIESEL'", emptyList())
    db.exec("ALTER TABLE `fuel_fillups` ADD COLUMN `fullTank` INTEGER NOT NULL DEFAULT 1", emptyList())
    db.exec("ALTER TABLE `fuel_fillups` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'EUR'", emptyList())
    db.exec("UPDATE `fuel_fillups` SET `currency` = ?", listOf(appCurrency))
    db.exec(
        "CREATE TABLE IF NOT EXISTS `cars` (`plate` TEXT NOT NULL, `fuel` TEXT, `currency` TEXT, PRIMARY KEY(`plate`))",
        emptyList(),
    )
    // Osoitemuistin piste voittaa stopin pisteen: myöhempi kirjoitus korvaa.
    val keys = HashMap<String, String>()
    for (table in listOf("stops", "saved_addresses")) {
        for ((placeId, lat, lng) in db.query("SELECT placeId, lat, lng FROM $table WHERE placeId IS NOT NULL")) {
            keys["p:$placeId"] = legKey((lat as Number).toDouble(), (lng as Number).toDouble())
        }
    }
    // Uusin ensin: kun kaksi tunnistetta osuu samaan pisteeseen, uusin opittu aika jää voimaan.
    val legs = db.query(
        "SELECT fromKey, toKey, updatedEpochSec FROM leg_stats WHERE fromKey LIKE 'p:%' OR toKey LIKE 'p:%' " +
            "ORDER BY updatedEpochSec DESC"
    )
    for ((from, to, updated) in legs) {
        val newFrom = (from as String).let { if (it.startsWith("p:")) keys[it] else it } ?: continue
        val newTo = (to as String).let { if (it.startsWith("p:")) keys[it] else it } ?: continue
        // Törmäys koordinaattiriviin: vanhempi koordinaattirivi väistyy, muuten (myös tasatilanteessa ja silmukassa
        // jo muunnetulle uudemmalle riville) p:-rivi jää päivittämättä ja poistuu alla.
        db.exec("DELETE FROM leg_stats WHERE fromKey = ? AND toKey = ? AND updatedEpochSec < ?", listOf(newFrom, newTo, updated))
        db.exec("UPDATE OR IGNORE leg_stats SET fromKey = ?, toKey = ? WHERE fromKey = ? AND toKey = ?", listOf(newFrom, newTo, from, to))
    }
    db.exec("DELETE FROM leg_stats WHERE fromKey LIKE 'p:%' OR toKey LIKE 'p:%'", emptyList())
}
