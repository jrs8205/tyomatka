package fi.tyomatka.ui.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.CarRepository
import fi.tyomatka.data.repo.CarRepositoryTest
import fi.tyomatka.data.repo.MemoryDataStore
import fi.tyomatka.data.repo.OnboardingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnboardingFinishTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `esittelyn päättyessä auto tallentuu ja esittely merkitään tehdyksi`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = OnboardingStore(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFolder().resolve("prefs.preferences_pb") })
        val cars = CarRepositoryTest.Cars()

        finishOnboarding(OnboardingCar().withPlate("tst1").withFuel(FuelKind.ELECTRIC), CarRepository(cars), store)

        assertEquals(listOf(CarEntity("TST-1", FuelKind.ELECTRIC, null)), cars.rows)
        assertTrue(store.isDone.first())
        // Ohitettu autovaihe ei tallenna autoa.
        finishOnboarding(OnboardingCar(), CarRepository(cars), store)
        assertEquals(1, cars.rows.size)
        scope.cancel()
    }

    @Test
    fun `esittelyn uusinta ei muuta olemassa olevaa autoa`() = runBlocking {
        val store = OnboardingStore(MemoryDataStore())
        val cars = CarRepositoryTest.Cars()
        finishOnboarding(OnboardingCar().withPlate("tst1").withFuel(FuelKind.ELECTRIC), CarRepository(cars), store)

        store.reset()
        assertEquals(false, store.isDone.first())
        finishOnboarding(OnboardingCar().withPlate("tst1"), CarRepository(cars), store)

        assertEquals(listOf(CarEntity("TST-1", FuelKind.ELECTRIC, null)), cars.rows)
        assertTrue(store.isDone.first())
    }
}
