package fi.tyomatka.ui.onboarding

import androidx.lifecycle.ViewModel
import fi.tyomatka.diag.Diag
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.data.repo.CarRepository
import fi.tyomatka.data.repo.OnboardingStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Esittelyn auto tallentuu ennen kuin esittely merkitään tehdyksi: Työmatka-sivu näkee sen heti. */
internal suspend fun finishOnboarding(car: OnboardingCar, cars: CarRepository, store: OnboardingStore) {
    car.plateToSave?.let { cars.introduce(it, car.fuel) }
    store.markDone()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val store: OnboardingStore,
    private val carRepository: CarRepository,
) : ViewModel() {

    /** null kunnes DataStore on luettu — juuri ei saa välähtää väärää näkymää. */
    val isDone: StateFlow<Boolean?> = store.isDone
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finish(car: OnboardingCar) {
        Diag.event("esittely", "valmis ${car.plate}")
        viewModelScope.launch { finishOnboarding(car, carRepository, store) }
    }
}
