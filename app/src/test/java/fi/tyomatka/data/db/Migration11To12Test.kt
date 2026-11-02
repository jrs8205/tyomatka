package fi.tyomatka.data.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Proxy
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ajaa v11 → v12 -migraation oikealla SQLitellä: karttakohteiden välimuisti, korjaukset ja hinnat. */
class Migration11To12Test {
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

    private class Recording : MigrationSql {
        val statements = mutableListOf<String>()
        override fun exec(sql: String, args: List<Any?>) { statements += sql }
        override fun query(sql: String): List<List<Any?>> = emptyList()
    }

    private fun Jdbc.exec(sql: String) = exec(sql, emptyList())

    private fun counts(db: Jdbc, table: String) = (db.query("SELECT COUNT(*) FROM $table").single().single() as Number).toInt()

    @Test
    fun `uudet taulut ottavat rivit vastaan ja sama kohde ja laji korvaa hinnan`() {
        Jdbc().use { db ->
            migrate11To12(db)

            db.exec("INSERT INTO poi_cache (`key`, json, fetchedEpochSec) VALUES ('24.50,60.25,25.00,60.50|fuel', '{}', 1000)")
            db.exec("INSERT INTO poi_corrections (poiId, field, value, epochSec) VALUES ('n1', 'shower', 'yes', 1000)")
            db.exec("INSERT INTO poi_prices (poiId, fuel, price, currency, epochDay) VALUES ('n1', 'DIESEL', 1.789, 'EUR', 20750)")
            db.exec("INSERT OR REPLACE INTO poi_prices (poiId, fuel, price, currency, epochDay) VALUES ('n1', 'DIESEL', 1.799, 'EUR', 20751)")

            assertEquals(1, counts(db, "poi_cache"))
            assertEquals(1, counts(db, "poi_corrections"))
            assertEquals(listOf(listOf<Any?>(1.799)), db.query("SELECT price FROM poi_prices"))
        }
    }

    @Test
    fun `taulut ovat samat, jotka Room generoi entiteeteista`() {
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

        val recording = Recording().also { migrate11To12(it) }
        val created = recording.statements.filter { it.startsWith("CREATE") }
        assertEquals(3, created.size, created.toString())
        created.forEach { sql ->
            assertTrue(generated.any { it.trim() == sql.trim() }, "Roomin lauseista puuttuu: $sql\n${generated.joinToString("\n")}")
        }
    }
}
