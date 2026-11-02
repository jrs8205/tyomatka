package fi.tyomatka.data.db

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Ajaa v7 → v8 -migraation oikealla SQLitellä v7-tauluja vastaan. */
class Migration7To8Test {
    /** JDBC-sovitin: sama migraatiokoodi, jonka Room ajaa SupportSQLiteDatabasella. */
    private class Jdbc(val connection: Connection) : MigrationSql, AutoCloseable {
        override fun exec(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { s ->
                args.forEachIndexed { i, arg -> s.setObject(i + 1, arg) }
                s.executeUpdate()
            }
        }

        override fun query(sql: String): List<List<Any?>> = connection.createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getObject(it) }) }
            }
        }

        override fun close() = connection.close()
    }

    private fun v7(): Jdbc = Jdbc(DriverManager.getConnection("jdbc:sqlite::memory:")).apply {
        exec(
            "CREATE TABLE fuel_fillups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, epochSec INTEGER NOT NULL, " +
                "licensePlate TEXT NOT NULL, odometerKm INTEGER NOT NULL, liters REAL NOT NULL, totalCost REAL NOT NULL, " +
                "pricePerLiter REAL NOT NULL, backupId TEXT NOT NULL DEFAULT '')"
        )
        exec(
            "CREATE TABLE leg_stats (fromKey TEXT NOT NULL, toKey TEXT NOT NULL, ewmaSec INTEGER NOT NULL, " +
                "samples INTEGER NOT NULL, updatedEpochSec INTEGER NOT NULL, PRIMARY KEY(fromKey, toKey))"
        )
        exec("CREATE TABLE saved_addresses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, placeId TEXT, lat REAL NOT NULL, lng REAL NOT NULL)")
        exec("CREATE TABLE stops (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, placeId TEXT, lat REAL NOT NULL, lng REAL NOT NULL)")
    }

    private fun Jdbc.exec(sql: String) = exec(sql, emptyList())

    private fun Jdbc.migrate(currency: String = "SEK") = migrate7To8(this, currency)

    @Test
    fun `tankkaukset ovat dieseliä, täysiä ja annetussa valuutassa`() {
        v7().use { db ->
            db.exec(
                "INSERT INTO fuel_fillups (epochSec, licensePlate, odometerKm, liters, totalCost, pricePerLiter, backupId) " +
                    "VALUES (1, 'ABC-123', 100, 50.0, 90.0, 1.8, 'b1')"
            )
            db.migrate("SEK")
            assertEquals(listOf(listOf<Any?>("DIESEL", 1, "SEK")), db.query("SELECT kind, fullTank, currency FROM fuel_fillups"))
            // Uuden rivin sarakeoletukset vastaavat entiteetin @ColumnInfo-arvoja.
            db.exec(
                "INSERT INTO fuel_fillups (epochSec, licensePlate, odometerKm, liters, totalCost, pricePerLiter) " +
                    "VALUES (2, 'ABC-123', 200, 40.0, 0, 0)"
            )
            assertEquals(listOf<Any?>("DIESEL", 1, "EUR"), db.query("SELECT kind, fullTank, currency FROM fuel_fillups WHERE epochSec = 2").single())
        }
    }

    @Test
    fun `autotaulu luodaan tyhjänä Roomin odottamalla rakenteella`() {
        v7().use { db ->
            db.exec("INSERT INTO fuel_fillups (epochSec, licensePlate, odometerKm, liters, totalCost, pricePerLiter) VALUES (1, 'ABC-123', 100, 50.0, 90.0, 1.8)")

            db.migrate()

            // Vanhojen tankkausten diesel on oletus, ei käyttäjän antama tieto: autoja ei täytetä.
            assertEquals(0, db.query("SELECT count(*) FROM cars").single().single())
            assertEquals(
                listOf(listOf<Any?>("plate", "TEXT", 1, 1), listOf<Any?>("fuel", "TEXT", 0, 0), listOf<Any?>("currency", "TEXT", 0, 0)),
                db.query("SELECT name, type, \"notnull\", pk FROM pragma_table_info('cars') ORDER BY cid"),
            )
        }
    }

    @Test
    fun `opittujen aikojen paikkatunnisteet muuttuvat koordinaattiavaimiksi`() {
        v7().use { db ->
            db.exec("INSERT INTO saved_addresses (placeId, lat, lng) VALUES ('A', 65.01438, 25.46953)")
            db.exec("INSERT INTO stops (placeId, lat, lng) VALUES ('B', 65.06012, 25.46781)")
            db.exec("INSERT INTO leg_stats VALUES ('p:A', 'p:B', 600, 3, 1)")
            db.exec("INSERT INTO leg_stats VALUES ('p:A', 'p:TUNTEMATON', 300, 1, 1)")
            db.exec("INSERT INTO leg_stats VALUES ('c:65.0144,25.4695', 'c:1.0000,1.0000', 120, 2, 1)")

            db.migrate()

            assertEquals(
                setOf("c:65.0144,25.4695>c:65.0601,25.4678", "c:65.0144,25.4695>c:1.0000,1.0000"),
                db.query("SELECT fromKey || '>' || toKey FROM leg_stats").map { it.single() }.toSet(),
            )
        }
    }

    @Test
    fun `puolivälin koordinaatti saa saman avaimen kuin ajonaikainen haku`() {
        // SQLiten printf antaisi 65.0144, Kotlinin String.format 65.0145: migroitu aika ei löytyisi.
        v7().use { db ->
            db.exec("INSERT INTO saved_addresses (placeId, lat, lng) VALUES ('A', 65.01445, 25.46953)")
            db.exec("INSERT INTO leg_stats VALUES ('p:A', 'c:1.0000,1.0000', 600, 3, 1)")

            db.migrate()

            val migrated = db.query("SELECT fromKey FROM leg_stats").single().single()
            assertEquals(legKey(65.01445, 25.46953), migrated)
            assertEquals("c:65.0145,25.4695", migrated)
        }
    }

    @Test
    fun `törmäävä paikkatunnisteavain poistuu ja olemassa oleva koordinaattirivi säilyy`() {
        v7().use { db ->
            db.exec("INSERT INTO saved_addresses (placeId, lat, lng) VALUES ('A', 65.01438, 25.46953)")
            db.exec("INSERT INTO leg_stats VALUES ('p:A', 'c:1.0000,1.0000', 999, 9, 1)")
            db.exec("INSERT INTO leg_stats VALUES ('c:65.0144,25.4695', 'c:1.0000,1.0000', 120, 2, 1)")

            db.migrate()

            assertEquals(listOf(listOf<Any?>("c:65.0144,25.4695", 120)), db.query("SELECT fromKey, ewmaSec FROM leg_stats"))
        }
    }

    @Test
    fun `vanha koordinaattirivi väistyy uudemman paikkatunnisterivin tieltä`() {
        v7().use { db ->
            db.exec("INSERT INTO saved_addresses (placeId, lat, lng) VALUES ('A', 65.01438, 25.46953)")
            db.exec("INSERT INTO leg_stats VALUES ('p:A', 'c:1.0000,1.0000', 999, 40, 5000)")
            db.exec("INSERT INTO leg_stats VALUES ('c:65.0144,25.4695', 'c:1.0000,1.0000', 120, 2, 1000)")

            db.migrate()

            assertEquals(listOf(listOf<Any?>("c:65.0144,25.4695", 999)), db.query("SELECT fromKey, ewmaSec FROM leg_stats"))
        }
    }

    @Test
    fun `pelkän stopin piste kelpaa avaimeksi`() {
        v7().use { db ->
            db.exec("INSERT INTO stops (placeId, lat, lng) VALUES ('S', 65.01438, 25.46953)")
            db.exec("INSERT INTO leg_stats VALUES ('p:S', 'c:1.0000,1.0000', 600, 3, 1)")

            db.migrate()

            assertEquals(listOf(listOf<Any?>("c:65.0144,25.4695", "c:1.0000,1.0000")), db.query("SELECT fromKey, toKey FROM leg_stats"))
        }
    }

    @Test
    fun `osoitemuistin piste voittaa saman tunnisteen stopin pisteen`() {
        v7().use { db ->
            db.exec("INSERT INTO stops (placeId, lat, lng) VALUES ('A', 65.1, 25.1)")
            db.exec("INSERT INTO saved_addresses (placeId, lat, lng) VALUES ('A', 65.01438, 25.46953)")
            db.exec("INSERT INTO leg_stats VALUES ('c:1.0000,1.0000', 'p:A', 600, 3, 1)")

            db.migrate()

            assertEquals(listOf(listOf<Any?>("c:1.0000,1.0000", "c:65.0144,25.4695")), db.query("SELECT fromKey, toKey FROM leg_stats"))
        }
    }

    @Test
    fun `kaksi tunnistetta samaan pisteeseen ja uusin opittu aika säilyy`() {
        // Eri hakupalvelut antoivat samalle osoitteelle tunnisteet A ja B.
        v7().use { db ->
            db.exec("INSERT INTO saved_addresses (placeId, lat, lng) VALUES ('A', 65.01438, 25.46953)")
            db.exec("INSERT INTO saved_addresses (placeId, lat, lng) VALUES ('B', 65.01441, 25.46951)")
            db.exec("INSERT INTO leg_stats VALUES ('p:A', 'c:1.0000,1.0000', 100, 1, 1000)")
            db.exec("INSERT INTO leg_stats VALUES ('p:B', 'c:1.0000,1.0000', 300, 5, 3000)")

            db.migrate()

            assertEquals(listOf(listOf<Any?>("c:65.0144,25.4695", 300)), db.query("SELECT fromKey, ewmaSec FROM leg_stats"))
        }
    }

    @Test
    fun `virheellinen valuuttakoodi hylätään ennen tietokantaa`() {
        v7().use { db ->
            assertFailsWith<IllegalArgumentException> { db.migrate("EUR'; DROP TABLE stops; --") }
            assertEquals(0, db.query("SELECT count(*) FROM stops").single().single())
        }
    }
}
