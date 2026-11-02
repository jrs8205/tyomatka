package fi.tyomatka.data.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Proxy
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ajaa v9 → v10 -migraation oikealla SQLitellä v9:n päivä- ja tankkaustauluja vastaan. */
class Migration9To10Test {
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

    private fun Jdbc.exec(sql: String) = exec(sql, emptyList())

    private fun v9(): Jdbc = Jdbc().apply {
        exec(
            "CREATE TABLE IF NOT EXISTS `route_days` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, " +
                "`startedAtEpochSec` INTEGER, `finishedAtEpochSec` INTEGER, `predictedFinishEpochSec` INTEGER)",
        )
        exec(
            "CREATE TABLE IF NOT EXISTS `fuel_fillups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochSec` INTEGER NOT NULL, " +
                "`licensePlate` TEXT NOT NULL, `odometerKm` INTEGER NOT NULL, `liters` REAL NOT NULL, `totalCost` REAL NOT NULL, " +
                "`pricePerLiter` REAL NOT NULL, `backupId` TEXT NOT NULL DEFAULT '', `kind` TEXT NOT NULL DEFAULT 'DIESEL', " +
                "`fullTank` INTEGER NOT NULL DEFAULT 1, `currency` TEXT NOT NULL DEFAULT 'EUR')",
        )
        exec("INSERT INTO route_days (id, dateEpochDay) VALUES (1, 20700)")
        exec(
            "INSERT INTO fuel_fillups (id, epochSec, licensePlate, odometerKm, liters, totalCost, pricePerLiter, backupId, kind, fullTank, currency) " +
                "VALUES (1, 100, 'ABC-123', 1000, 40.0, 72.0, 1.8, 'b1', 'DIESEL', 1, 'EUR')",
        )
    }

    @Test
    fun `vanhat rivit saavat tyhjan tekstin ja uusi rivi onnistuu ilman tekstia`() {
        v9().use { db ->
            migrate9To10(db)

            assertEquals(listOf(listOf<Any?>("")), db.query("SELECT note FROM route_days WHERE id = 1"))
            assertEquals(listOf(listOf<Any?>("")), db.query("SELECT note FROM fuel_fillups WHERE id = 1"))
            db.exec("INSERT INTO route_days (dateEpochDay) VALUES (20701)")
            db.exec("UPDATE fuel_fillups SET note = 'Latauspiste rikki' WHERE id = 1")
            assertEquals(listOf(listOf<Any?>("")), db.query("SELECT note FROM route_days WHERE dateEpochDay = 20701"))
            assertEquals(listOf(listOf<Any?>("Latauspiste rikki")), db.query("SELECT note FROM fuel_fillups WHERE id = 1"))
        }
    }

    @Test
    fun `sarake on sama, jonka Room generoi entiteeteista`() {
        // Room vertaa skeemaa avatessaan: poikkeava sarake kaataisi sovelluksen käynnistyksessä päivityksen jälkeen.
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

        val column = "`note` TEXT NOT NULL DEFAULT ''"
        assertTrue(generated.any { "`route_days`" in it && column in it }, generated.toString())
        assertTrue(generated.any { "`fuel_fillups`" in it && column in it }, generated.toString())
        v9().use { db ->
            migrate9To10(db)
            val columns = db.query("PRAGMA table_info(route_days)").map { it[1] to it[4] } +
                db.query("PRAGMA table_info(fuel_fillups)").map { it[1] to it[4] }
            assertEquals(listOf("note" to "''", "note" to "''"), columns.filter { it.first == "note" })
        }
    }
}
