package fi.tyomatka.data.photo

import fi.tyomatka.data.db.FuelPhotoDao
import fi.tyomatka.data.db.FuelPhotoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FillupPhotoRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Muistinvarainen taulu; viiteavaimen poisto ([cascade]) kuten SQLitessä. [ops] kertoo kirjoitusten järjestyksen. */
    private class Rows : FuelPhotoDao {
        val rows = mutableListOf<FuelPhotoEntity>()
        val ops = mutableListOf<String>()
        override suspend fun insert(photo: FuelPhotoEntity): Long {
            val id = (rows.maxOfOrNull { it.id } ?: 0) + 1
            rows.add(photo.copy(id = id))
            ops += "insert"
            return id
        }
        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
            ops += "delete"
        }
        override suspend fun forFillup(fillupId: Long) = rows.filter { it.fillupId == fillupId }.sortedWith(compareBy({ it.addedAtEpochSec }, { it.id }))
        override fun observeAll(): Flow<List<FuelPhotoEntity>> = flowOf(rows.toList())
        override suspend fun all() = rows.toList()
        override suspend fun byBackupId(backupId: String) = rows.firstOrNull { it.backupId == backupId }
        fun cascade(fillupId: Long) { rows.removeAll { it.fillupId == fillupId } }
    }

    private val dao = Rows()
    private lateinit var store: PhotoStore
    private lateinit var repository: FillupPhotoRepository

    private fun setUp() {
        store = PhotoStore(tmp.newFolder("files"), tmp.newFolder("cache"))
        repository = FillupPhotoRepository(dao, store)
    }

    private fun pending(text: String) = store.newPending().apply { writeText(text) }

    @Test
    fun `tallennus lisää odottavat ja poistaa poistetut kuvat tiedostoineen`() = runBlocking {
        setUp()
        repository.apply(fillupId = 7, removedIds = emptySet(), added = listOf(pending("kuitti"), pending("mittari")), nowEpochSec = 100)
        val (receipt, pump) = dao.forFillup(7)
        assertEquals("kuitti", store.file(receipt.fileName).readText())
        assertEquals("mittari", store.file(pump.fileName).readText())

        // Muokkaus: kuitti jää, mittarin kuva poistetaan, uusi kuvakaappaus lisätään.
        dao.ops.clear()
        val result = repository.apply(fillupId = 7, removedIds = setOf(pump.id), added = listOf(pending("ruutu")), nowEpochSec = 200)

        assertEquals(PhotoApplyResult(saved = 1, failed = 0), result)
        assertEquals(listOf("kuitti", "ruutu"), dao.forFillup(7).map { store.file(it.fileName).readText() })
        assertFalse(store.file(pump.fileName).exists())
        // Uudet kuvat ensin, poistot vasta niiden jälkeen.
        assertEquals(listOf("insert", "delete"), dao.ops)
        // Muiden kirjausten kuvat eivät muutu.
        repository.apply(fillupId = 8, removedIds = emptySet(), added = listOf(pending("toinen")), nowEpochSec = 300)
        assertEquals(2, dao.forFillup(7).size)
    }

    @Test
    fun `puuttuva odottava kuva ei kaada tallennusta eikä poista merkittyä vanhaa kuvaa`() = runBlocking {
        setUp()
        repository.apply(fillupId = 7, removedIds = emptySet(), added = listOf(pending("vanha")), nowEpochSec = 100)
        val old = dao.forFillup(7).single()
        // Android siivosi välimuistin: odottava tiedosto katosi ennen tallennusta. Toinen tiedosto ei ole odottava.
        val lost = pending("kadonnut").apply { delete() }
        val outside = tmp.newFile("muualla.jpg")

        val result = repository.apply(fillupId = 7, removedIds = setOf(old.id), added = listOf(lost, pending("uusi"), outside), nowEpochSec = 200)

        assertEquals(PhotoApplyResult(saved = 1, failed = 2), result)
        assertEquals(listOf("vanha", "uusi"), dao.forFillup(7).map { store.file(it.fileName).readText() })
        assertTrue(store.file(old.fileName).exists())
    }

    @Test
    fun `tallennus poistaa vain lomakkeella poistetut kuvat`() = runBlocking {
        setUp()
        // Lomake tunsi kuvan A; B tuli varmuuskopion tuonnissa lomakkeen ollessa auki, eikä lomake tiennyt siitä.
        repository.apply(fillupId = 7, removedIds = emptySet(), added = listOf(pending("A")), nowEpochSec = 100)
        val a = dao.forFillup(7).single()
        repository.restore(7, pending("B"), addedAtEpochSec = 150, backupId = "b")
        val b = dao.forFillup(7).single { it.id != a.id }

        // Käyttäjä poisti A:n ja lisäsi C:n.
        repository.apply(fillupId = 7, removedIds = setOf(a.id), added = listOf(pending("C")), nowEpochSec = 200)

        assertEquals(listOf("B", "C"), dao.forFillup(7).map { store.file(it.fileName).readText() })
        assertTrue(store.file(b.fileName).exists())
        assertFalse(store.file(a.fileName).exists())
    }

    @Test
    fun `toisen kirjauksen kuva ei poistu, vaikka sen tunniste olisi poistettavissa`() = runBlocking {
        setUp()
        repository.apply(fillupId = 8, removedIds = emptySet(), added = listOf(pending("toinen")), nowEpochSec = 100)
        val other = dao.forFillup(8).single()

        repository.apply(fillupId = 7, removedIds = setOf(other.id), added = listOf(pending("oma")), nowEpochSec = 200)

        assertEquals(listOf(other.id), dao.forFillup(8).map { it.id })
        assertTrue(store.file(other.fileName).exists())
        assertEquals(listOf("oma"), dao.forFillup(7).map { store.file(it.fileName).readText() })
    }

    @Test
    fun `kirjauksen poisto poistaa kuvien tiedostot`() = runBlocking {
        setUp()
        repository.apply(fillupId = 7, removedIds = emptySet(), added = listOf(pending("kuitti")), nowEpochSec = 100)
        repository.apply(fillupId = 8, removedIds = emptySet(), added = listOf(pending("toinen")), nowEpochSec = 100)
        val file = store.file(dao.forFillup(7).single().fileName)
        val other = store.file(dao.forFillup(8).single().fileName)

        repository.deleteWithFillup(7) { dao.cascade(7) }

        assertFalse(file.exists())
        assertTrue(other.exists())
        assertTrue(dao.forFillup(7).isEmpty())
    }

    @Test
    fun `siivous poistaa orvot tiedostot ja rivit, joiden tiedosto puuttuu`() = runBlocking {
        setUp()
        repository.apply(fillupId = 7, removedIds = emptySet(), added = listOf(pending("a"), pending("b")), nowEpochSec = 100)
        val (kept, lost) = dao.forFillup(7)
        store.delete(lost.fileName)
        val orphan = store.commit(pending("orpo"))

        repository.sweep()

        assertEquals(listOf(kept.id), dao.all().map { it.id })
        assertTrue(store.file(kept.fileName).exists())
        assertFalse(store.file(orphan).exists())
    }
}
