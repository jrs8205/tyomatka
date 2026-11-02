package fi.tyomatka.data.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Proxy
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** v12 → v13: kuljettajien ilmoitusten lähetysjono. */
class Migration12To13Test {
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

    @Test
    fun `jono luodaan eikä muihin tauluihin kosketa`() {
        Jdbc().use { db ->
            migrate11To12(db)
            db.exec("INSERT INTO poi_corrections (poiId, field, value, epochSec) VALUES ('n1', 'shower', 'yes', 1000)", emptyList())

            migrate12To13(db)

            db.exec("INSERT INTO pending_reports (rid, poiId, field, value, createdEpochSec) VALUES ('r1', 'n1', 'closed', 'yes', 1000)", emptyList())
            assertEquals(listOf(listOf<Any?>("r1", "n1", "closed", "yes")), db.query("SELECT rid, poiId, field, value FROM pending_reports"))
            assertEquals(listOf(listOf<Any?>("n1", "shower", "yes")), db.query("SELECT poiId, field, value FROM poi_corrections"))
        }
        val recording = Recording().also { migrate12To13(it) }
        assertTrue(recording.statements.all { it.startsWith("CREATE TABLE IF NOT EXISTS `pending_reports`") }, recording.statements.toString())
    }

    @Test
    fun `taulu on sama, jonka Room generoi entiteetista`() {
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

        val created = Recording().also { migrate12To13(it) }.statements
        assertEquals(1, created.size, created.toString())
        assertTrue(generated.any { it.trim() == created.single().trim() }, "Roomin lauseista puuttuu: ${created.single()}\n${generated.joinToString("\n")}")
    }
}
