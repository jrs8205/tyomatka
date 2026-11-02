package fi.tyomatka.data.repo

import fi.tyomatka.data.db.CarDao
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Autoprofiilit: polttoaine rekisterinumeron mukaan; vanhojen versioiden tallentama valuutta säilyy rivillä. */
@Singleton
class CarRepository @Inject constructor(
    private val carDao: CarDao,
) {
    private val writeMutex = Mutex()

    fun observeAll(): Flow<List<CarEntity>> = carDao.observeAll()

    /**
     * Esittelyn auto: uusi profiili, tai jo olemassa olevalle autolle annettu polttoaine. Valuutta säilyy aina, ja
     * aiempi polttoaine säilyy, kun polttoainetta ei valittu.
     */
    suspend fun introduce(plate: String, fuel: FuelKind?): Unit = writeMutex.withLock {
        val car = carDao.get(plate) ?: CarEntity(plate, null, null)
        carDao.upsert(car.copy(fuel = fuel?.takeIf { it.isMainFuel } ?: car.fuel))
    }

    /**
     * Autot-näkymän poisto autolle, jolla ei ole työjaksoja eikä kirjauksia (kirjoitusvirhe tai turha auto). Kertoo,
     * poistuiko auto: tarkistus tehdään tietokannassa, koska autolle on voinut tulla kirjauksia (varmuuskopion tuonti)
     * sen jälkeen, kun poisto näytettiin sallittuna.
     */
    suspend fun delete(plate: String): Boolean = writeMutex.withLock { carDao.deleteIfUnused(plate) > 0 }

    /**
     * Käyttäjän suoraan antama polttoaine (Autot-näkymä, auton valinta työn aloituksessa ja autonvaihdossa); null =
     * automaattinen. Uusi auto lisätään, ja olemassa olevan auton muut tiedot säilyvät.
     */
    suspend fun setFuel(plate: String, fuel: FuelKind?): Unit = writeMutex.withLock {
        carDao.insertIfMissing(CarEntity(plate, null, null))
        carDao.updateFuel(plate, fuel?.takeIf { it.isMainFuel })
    }
}
