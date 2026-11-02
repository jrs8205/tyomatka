package fi.tyomatka.data.repo

import fi.tyomatka.data.db.CarDao
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.ui.history.forms.CarForm
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarRepositoryTest {
    /** Muistinvarainen autotaulu lisäysjärjestyksessä; [used]-autoilla on työjaksoja tai kirjauksia. */
    internal open class Cars : CarDao {
        val rows = mutableListOf<CarEntity>()
        val used = mutableSetOf<String>()
        override fun observeAll() = flowOf(rows.toList())
        override suspend fun all() = rows.toList()
        override suspend fun get(plate: String) = rows.firstOrNull { it.plate == plate }
        override suspend fun upsert(car: CarEntity) {
            val at = rows.indexOfFirst { it.plate == car.plate }
            if (at >= 0) rows[at] = car else rows.add(car)
        }
        override suspend fun insertIfMissing(car: CarEntity): Long {
            if (rows.any { it.plate == car.plate }) return -1
            rows.add(car)
            return rows.size.toLong()
        }
        override suspend fun updateFuel(plate: String, fuel: FuelKind?) {
            val at = rows.indexOfFirst { it.plate == plate }
            if (at >= 0) rows[at] = rows[at].copy(fuel = fuel)
        }
        override suspend fun deleteIfUnused(plate: String): Int =
            if (plate !in used && rows.removeAll { it.plate == plate }) 1 else 0
        override suspend fun fillMissing(plate: String, fuel: FuelKind?, currency: String?) {
            val at = rows.indexOfFirst { it.plate == plate }
            if (at >= 0) rows[at] = rows[at].let { it.copy(fuel = it.fuel ?: fuel, currency = it.currency ?: currency) }
        }
    }

    @Test
    fun `esittelyn auto lisätään tai sen polttoaine täydennetään valuuttaa koskematta`() = runTest {
        val dao = Cars()
        val repository = CarRepository(dao)

        repository.introduce("UUS-1", FuelKind.ELECTRIC)
        assertEquals(CarEntity("UUS-1", FuelKind.ELECTRIC, null), dao.get("UUS-1"))

        // Olemassa oleva auto: valuutta säilyy aina, polttoaine säilyy, kun sitä ei valittu.
        dao.upsert(CarEntity("ABC-123", FuelKind.PETROL, "NOK"))
        repository.introduce("ABC-123", null)
        assertEquals(CarEntity("ABC-123", FuelKind.PETROL, "NOK"), dao.get("ABC-123"))
        repository.introduce("ABC-123", FuelKind.GAS)
        assertEquals(CarEntity("ABC-123", FuelKind.GAS, "NOK"), dao.get("ABC-123"))
        // AdBlue ei ole auton polttoaine.
        repository.introduce("ABC-123", FuelKind.ADBLUE)
        assertEquals(FuelKind.GAS, dao.get("ABC-123")?.fuel)
    }

    @Test
    fun `auton lomakkeen tallennus säilyttää valuutan, jonka tuonti toi lomakkeen ollessa auki`() = runTest {
        val dao = Cars()
        dao.upsert(CarEntity("ABC-123", null, null))
        val repository = CarRepository(dao)
        val form = CarForm.initial(CarEntity("ABC-123", null, null)).withFuel(FuelKind.DIESEL)

        dao.fillMissing("ABC-123", null, "SEK")
        repository.setFuel(form.savedPlate, form.fuel)

        assertEquals(CarEntity("ABC-123", FuelKind.DIESEL, "SEK"), dao.get("ABC-123"))
    }

    @Test
    fun `polttoaineen tallennus lisää uuden auton ja säilyttää olemassa olevan muut tiedot`() = runTest {
        val dao = Cars()
        val repository = CarRepository(dao)

        repository.setFuel("UUS-1", FuelKind.ELECTRIC)
        assertEquals(CarEntity("UUS-1", FuelKind.ELECTRIC, null), dao.get("UUS-1"))
        // Lisää auto -lomakkeen oletus: polttoaine automaattinen.
        repository.setFuel("UUS-2", null)
        assertEquals(CarEntity("UUS-2", null, null), dao.get("UUS-2"))

        dao.upsert(CarEntity("ABC-123", FuelKind.PETROL, "SEK"))
        repository.setFuel("ABC-123", FuelKind.DIESEL)
        assertEquals(CarEntity("ABC-123", FuelKind.DIESEL, "SEK"), dao.get("ABC-123"))
        // Automaattinen polttoaine tallentuu tyhjänä, ja AdBlue ei ole auton polttoaine.
        repository.setFuel("ABC-123", null)
        assertEquals(CarEntity("ABC-123", null, "SEK"), dao.get("ABC-123"))
        repository.setFuel("ABC-123", FuelKind.ADBLUE)
        assertEquals(CarEntity("ABC-123", null, "SEK"), dao.get("ABC-123"))
    }

    @Test
    fun `polttoaineen tallennus ei kirjoita vanhaa riviä juuri valmistuneen tuonnin päälle`() = runTest {
        // Tuonti täyttää valuutan juuri ennen kirjoitusta: vain yhden sarakkeen päivitys säilyttää sen.
        val dao = object : Cars() {
            override suspend fun upsert(car: CarEntity) {
                fillMissing(car.plate, null, "SEK")
                super.upsert(car)
            }
            override suspend fun updateFuel(plate: String, fuel: FuelKind?) {
                fillMissing(plate, null, "SEK")
                super.updateFuel(plate, fuel)
            }
        }
        dao.rows.add(CarEntity("ABC-123", null, null))

        CarRepository(dao).setFuel("ABC-123", FuelKind.DIESEL)

        assertEquals(CarEntity("ABC-123", FuelKind.DIESEL, "SEK"), dao.rows.single())
    }

    @Test
    fun `auton poisto poistaa vain sen profiilin ja kertoo, poistuiko auto`() = runTest {
        val dao = Cars()
        dao.upsert(CarEntity("TST-1", null, null))
        dao.upsert(CarEntity("ABC-123", FuelKind.DIESEL, null))
        dao.used += "ABC-123"
        val repository = CarRepository(dao)

        assertTrue(repository.delete("TST-1"))
        // Autolle ehti tulla kirjauksia (varmuuskopion tuonti): profiili säilyy, ja kutsuja saa tietää sen.
        assertFalse(repository.delete("ABC-123"))

        assertEquals(listOf(CarEntity("ABC-123", FuelKind.DIESEL, null)), dao.rows)
    }
}
