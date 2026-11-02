package fi.tyomatka.data.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Proxy
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Ajaa v8 → v9 -migraation oikealla SQLitellä v8:n tankkaustaulua vastaan. */
class Migration8To9Test {
    private class Jdbc : MigrationSql, AutoCloseable {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")

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

    private fun v8(): Jdbc = Jdbc().apply {
        exec(
            "CREATE TABLE IF NOT EXISTS `fuel_fillups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochSec` INTEGER NOT NULL, " +
                "`licensePlate` TEXT NOT NULL, `odometerKm` INTEGER NOT NULL, `liters` REAL NOT NULL, `totalCost` REAL NOT NULL, " +
                "`pricePerLiter` REAL NOT NULL, `backupId` TEXT NOT NULL DEFAULT '', `kind` TEXT NOT NULL DEFAULT 'DIESEL', " +
                "`fullTank` INTEGER NOT NULL DEFAULT 1, `currency` TEXT NOT NULL DEFAULT 'EUR')",
            emptyList(),
        )
        exec(
            "INSERT INTO fuel_fillups (id, epochSec, licensePlate, odometerKm, liters, totalCost, pricePerLiter, backupId, kind, fullTank, currency) " +
                "VALUES (1, 100, 'ABC-123', 1000, 40.0, 72.0, 1.8, 'b1', 'DIESEL', 1, 'EUR'), (2, 200, 'EVX-1', 500, 30.0, 0, 0, 'b2', 'ELECTRIC', 1, 'EUR')",
            emptyList(),
        )
    }

    private fun Jdbc.exec(sql: String) = exec(sql, emptyList())

    @Test
    fun `kuvataulu syntyy, ja kirjauksen poisto poistaa sen kuvat`() {
        v8().use { db ->
            migrate8To9(db)
            db.exec("PRAGMA foreign_keys = ON")
            db.exec("INSERT INTO fuel_photos (fillupId, fileName, addedAtEpochSec, backupId) VALUES (1, 'a.jpg', 10, 'p1'), (1, 'b.jpg', 11, 'p2'), (2, 'c.jpg', 12, 'p3')")

            db.exec("DELETE FROM fuel_fillups WHERE id = 1")

            assertEquals(listOf(listOf<Any?>("c.jpg")), db.query("SELECT fileName FROM fuel_photos"))
            // Kirjaukset säilyvät ennallaan.
            assertEquals(listOf(listOf<Any?>(2, "EVX-1")), db.query("SELECT id, licensePlate FROM fuel_fillups"))
        }
    }

    @Test
    fun `migraation SQL on sama, jonka Room generoi entiteetistä`() {
        // Room vertaa skeemaa avatessaan: poikkeava taulu kaataisi sovelluksen käynnistyksessä päivityksen jälkeen.
        val generated = mutableListOf<String>()
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SQLiteStatement::class.java)) { _, method, _ ->
            if (method.name == "step") false else null
        }
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SQLiteConnection::class.java)) { _, method, args ->
            if (method.name == "prepare") statement.also { generated += args[0] as String } else null
        } as SQLiteConnection
        val delegate = AppDatabase_Impl::class.java.getDeclaredMethod("createOpenDelegate")
            .apply { isAccessible = true }
            .invoke(AppDatabase_Impl()) as RoomOpenDelegate
        delegate.createAllTables(connection)

        val migrated = mutableListOf<String>()
        migrate8To9(object : MigrationSql {
            override fun exec(sql: String, args: List<Any?>) { migrated += sql }
            override fun query(sql: String): List<List<Any?>> = emptyList()
        })

        assertEquals(generated.filter { "`fuel_photos`" in it }, migrated)
    }

    @Test
    fun `kuva vaatii olemassa olevan kirjauksen ja indeksin`() {
        v8().use { db ->
            migrate8To9(db)
            db.exec("PRAGMA foreign_keys = ON")
            assertFails { db.exec("INSERT INTO fuel_photos (fillupId, fileName, addedAtEpochSec, backupId) VALUES (99, 'x.jpg', 1, 'p')") }
            assertEquals(
                listOf(listOf<Any?>("index_fuel_photos_fillupId")),
                db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'fuel_photos'"),
            )
        }
    }
}
