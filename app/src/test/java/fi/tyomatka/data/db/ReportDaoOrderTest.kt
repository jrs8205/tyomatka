package fi.tyomatka.data.db

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ajaa jonon kyselyt oikealla SQLitellä: sama SQL kuin Roomin kyselyissä. */
class ReportDaoOrderTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use {
            it.executeUpdate(
                "CREATE TABLE pending_reports (rid TEXT NOT NULL, poiId TEXT NOT NULL, field TEXT NOT NULL, value TEXT NOT NULL, " +
                    "createdEpochSec INTEGER NOT NULL, sentEpochSec INTEGER, PRIMARY KEY(rid))",
            )
        }
    }

    private fun Connection.insert(rid: String, created: Long, sent: Long? = null) = prepareStatement(
        "INSERT INTO pending_reports VALUES (?, 'n1', 'adblue', 'no', ?, ?)",
    ).use { s ->
        s.setString(1, rid)
        s.setLong(2, created)
        if (sent == null) s.setNull(3, java.sql.Types.INTEGER) else s.setLong(3, sent)
        s.executeUpdate()
    }

    private fun Connection.rids(sql: String): List<String> = createStatement().use { s ->
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add(r.getString("rid")) } }
    }

    @Test
    fun `saman sekunnin ilmoitukset pysyvät tekojärjestyksessä satunnaisesta tunnuksesta riippumatta`() {
        db().use { db ->
            db.insert("zzz", 100)
            db.insert("aaa", 100)
            db.insert("mmm", 99)

            assertEquals(listOf("mmm", "zzz", "aaa"), db.rids(ReportDao.PENDING_SQL))
            assertEquals(listOf("mmm", "zzz", "aaa"), db.rids(ReportDao.ALL_SQL))
        }
    }

    @Test
    fun `kuitatut pysyvät samassa järjestyksessä`() {
        db().use { db ->
            db.insert("zzz", 100, sent = 105)
            db.insert("aaa", 100, sent = 101)
            db.insert("lähettämätön", 90)

            assertEquals(listOf("zzz", "aaa"), db.rids(ReportDao.SENT_SQL))
            assertEquals(listOf("lähettämätön", "zzz", "aaa"), db.rids(ReportDao.ALL_SQL))
            assertEquals(listOf("lähettämätön"), db.rids(ReportDao.PENDING_SQL))
        }
    }
}
