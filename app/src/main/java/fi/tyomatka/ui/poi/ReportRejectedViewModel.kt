package fi.tyomatka.ui.poi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.data.repo.ReportRepository
import fi.tyomatka.diag.Diag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Palvelimen hylkäämät ilmoitukset: tieto pysyy, kunnes käyttäjä on kuitannut sen. */
@HiltViewModel
class ReportRejectedViewModel @Inject constructor(private val reports: ReportRepository) : ViewModel() {
    val unseen: StateFlow<Set<String>> = reports.rejectedUnseen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Vain [seen], eli näytetyt: kuittauksen jälkeen hylätty jää kerrottavaksi. */
    fun acknowledge(seen: Set<String>) {
        viewModelScope.launch {
            // Epäonnistunut kirjoitus (esim. levy täynnä) ei saa kaataa sovellusta: ikkuna jää näkyviin ja kuittauksen voi tehdä uudelleen.
            try {
                reports.acknowledgeRejected(seen)
            } catch (e: IOException) {
                Diag.event("ilmoitus", "hylkäyksen kuittaus epäonnistui ${e.javaClass.simpleName}")
            }
        }
    }
}
