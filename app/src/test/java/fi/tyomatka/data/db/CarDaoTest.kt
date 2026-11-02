package fi.tyomatka.data.db

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ajaa auton poistolauseen oikealla SQLitellä: sama SQL kuin Roomin kyselyssä ([CarDao.DELETE_IF_UNUSED]). */
class CarDaoTest {
    /** Taulut Roomin rakenteella poistolauseen tarvitsemin sarakkein, ja kaksi autoa. */
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        exec("CREATE TABLE cars (plate TEXT NOT NULL, fuel TEXT, currency TEXT, PRIMARY KEY(plate))")
        exec(
            "CREATE TABLE work_segments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, routeDayId INTEGER NOT NULL, " +
                "startEpochSec INTEGER NOT NULL, licensePlate TEXT)"
        )
        exec("CREATE TABLE fuel_fillups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, epochSec INTEGER NOT NULL, licensePlate TEXT NOT NULL)")
        exec("INSERT INTO cars VALUES ('TST-1', 'DIESEL', 'NOK'), ('ABC-123', NULL, NULL)")
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.executeUpdate(sql) }

    /** Roomin `:plate` on SQLiten nimetty parametri: yksi sidonta kattaa kaikki esiintymät. */
    private fun Connection.deleteIfUnused(plate: String): Int = prepareStatement(CarDao.DELETE_IF_UNUSED).use { s ->
        s.setString(1, plate)
        s.executeUpdate()
    }

    private fun Connection.plates(): List<String> = createStatement().use { s ->
        s.executeQuery("SELECT plate FROM cars ORDER BY plate").use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }

    @Test
    fun `auto ilman työjaksoja ja kirjauksia poistuu`() {
        db().use { db ->
            // Toisen auton työjakso ja kirjaus eivät estä.
            db.exec("INSERT INTO work_segments (routeDayId, startEpochSec, licensePlate) VALUES (1, 1, 'ABC-123')")
            db.exec("INSERT INTO fuel_fillups (epochSec, licensePlate) VALUES (1, 'ABC-123')")

            assertEquals(1, db.deleteIfUnused("TST-1"))

            assertEquals(listOf("ABC-123"), db.plates())
            // Toinen yritys ei löydä enää mitään.
            assertEquals(0, db.deleteIfUnused("TST-1"))
        }
    }

    @Test
    fun `työjakso estää poiston`() {
        db().use { db ->
            db.exec("INSERT INTO work_segments (routeDayId, startEpochSec, licensePlate) VALUES (1, 1, 'TST-1')")

            assertEquals(0, db.deleteIfUnused("TST-1"))

            assertEquals(listOf("ABC-123", "TST-1"), db.plates())
        }
    }

    @Test
    fun `tankkaus estää poiston`() {
        db().use { db ->
            db.exec("INSERT INTO fuel_fillups (epochSec, licensePlate) VALUES (1, 'TST-1')")

            assertEquals(0, db.deleteIfUnused("TST-1"))

            assertEquals(listOf("ABC-123", "TST-1"), db.plates())
        }
    }
}
