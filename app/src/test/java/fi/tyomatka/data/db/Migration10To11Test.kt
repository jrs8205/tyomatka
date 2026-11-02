package fi.tyomatka.data.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Proxy
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ajaa v10 → v11 -migraation oikealla SQLitellä: pohjataulut ja päivän pohjaviite. */
class Migration10To11Test {
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

    /** Kerää migraation lauseet vertailua varten ajamatta niitä. */
    private class Recording : MigrationSql {
        val statements = mutableListOf<String>()
        override fun exec(sql: String, args: List<Any?>) { statements += sql }
        override fun query(sql: String): List<List<Any?>> = emptyList()
    }

    private fun Jdbc.exec(sql: String) = exec(sql, emptyList())

    private fun v10(): Jdbc = Jdbc().apply {
        exec("PRAGMA foreign_keys = ON")
        exec(
            "CREATE TABLE IF NOT EXISTS `route_days` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, " +
                "`startedAtEpochSec` INTEGER, `finishedAtEpochSec` INTEGER, `predictedFinishEpochSec` INTEGER, `note` TEXT NOT NULL DEFAULT '')",
        )
        exec("INSERT INTO route_days (id, dateEpochDay) VALUES (1, 20700)")
    }

    @Test
    fun `vanha paiva saa tyhjan pohjaviitteen ja pohjan poisto vie stopit kaskadina`() {
        v10().use { db ->
            migrate10To11(db)

            assertEquals(listOf(listOf<Any?>(null, null)), db.query("SELECT templateId, templateAck FROM route_days WHERE id = 1"))
            db.exec("INSERT INTO route_templates (name, createdEpochSec, updatedEpochSec) VALUES ('Vantaa-kierros', 100, 100)")
            val templateId = (db.query("SELECT id FROM route_templates").single().single() as Number).toLong()
            db.exec(
                "INSERT INTO template_stops (templateId, orderIndex, label, address, lat, lng) VALUES " +
                    "($templateId, 0, 'Örum', 'Läntinen teollisuuskatu 2, Espoo', 60.2, 24.7), " +
                    "($templateId, 1, 'Varasto', 'Tikkurilantie 10, Vantaa', 60.3, 25.0)",
            )
            db.exec("UPDATE route_days SET templateId = $templateId, templateAck = 'abc' WHERE id = 1")
            assertEquals(listOf(listOf<Any?>(2)), db.query("SELECT COUNT(*) FROM template_stops").map { it.map { v -> (v as Number).toInt() } })
            assertEquals(listOf(listOf<Any?>(templateId.toInt(), "abc")), db.query("SELECT templateId, templateAck FROM route_days WHERE id = 1").map { listOf((it[0] as Number).toInt(), it[1]) })

            db.exec("DELETE FROM route_templates WHERE id = $templateId")
            assertEquals(listOf(listOf<Any?>(0)), db.query("SELECT COUNT(*) FROM template_stops").map { it.map { v -> (v as Number).toInt() } })
        }
    }

    @Test
    fun `taulut ja sarakkeet ovat samat, jotka Room generoi entiteeteista`() {
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

        val recording = Recording().also { migrate10To11(it) }
        val created = recording.statements.filter { it.startsWith("CREATE") }
        assertEquals(4, created.size, created.toString())
        created.forEach { sql ->
            assertTrue(generated.any { it.trim() == sql.trim() }, "Roomin lauseista puuttuu: $sql\n${generated.joinToString("\n")}")
        }
        val routeDays = generated.single { "CREATE TABLE IF NOT EXISTS `route_days`" in it }
        assertTrue("`templateId` INTEGER" in routeDays && "`templateAck` TEXT" in routeDays, routeDays)

        v10().use { db ->
            migrate10To11(db)
            val columns = db.query("PRAGMA table_info(route_days)").map { it[1] to it[2] }
            assertTrue(("templateId" to "INTEGER") in columns && ("templateAck" to "TEXT") in columns, columns.toString())
        }
    }
}
