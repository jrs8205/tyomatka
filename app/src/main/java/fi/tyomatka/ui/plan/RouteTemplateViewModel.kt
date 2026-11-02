package fi.tyomatka.ui.plan

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.R
import fi.tyomatka.diag.Diag
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.TemplateListItem
import fi.tyomatka.data.db.TemplateStopEntity
import fi.tyomatka.data.repo.OpenMode
import fi.tyomatka.data.repo.RouteRepository
import fi.tyomatka.data.repo.RouteTemplateRepository
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveSessionManager
import fi.tyomatka.ui.UserErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Tallennettujen reittien tila Suunnittelu-välilehdellä. */
data class TemplateUiState(
    val dayId: Long? = null,
    /** Päivän stopit yhteensä, huoltostopit mukaan lukien (avauskyselyn "N stoppia"). */
    val dayStopCount: Int = 0,
    val templates: List<TemplateListItem> = emptyList(),
    /** Pohja, josta päivä on avattu; tallennuskyselyn esitäyttö ja Korvaa-nappi. */
    val openedTemplate: RouteTemplateEntity? = null,
    val banner: TemplateBanner? = null,
    /** Ajo käynnissä: tallennus, avaus ja muutosrivi eivät ole käytössä. */
    val driving: Boolean = false,
    val notice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RouteTemplateViewModel @Inject constructor(
    private val application: Application,
    private val routeRepository: RouteRepository,
    private val templateRepository: RouteTemplateRepository,
    sessionManager: DriveSessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(TemplateUiState())
    val state: StateFlow<TemplateUiState> = _state.asStateFlow()

    private var noticeJob: Job? = null

    init {
        val driving = sessionManager.state
            .map { it.phase == DrivePhase.NAVIGATING || it.phase == DrivePhase.AT_STOP }
            .distinctUntilChanged()
        viewModelScope.launch {
            // Vuorokauden vaihtuessa tila alkaa alusta uudelle päivälle.
            routeRepository.observeTodayDay().collectLatest { today ->
                _state.value = TemplateUiState(dayId = today.id, templates = _state.value.templates, driving = _state.value.driving)
                val day = templateRepository.observeDay(today.id).filterNotNull()
                val template = day.map { it.templateId }.distinctUntilChanged().flatMapLatest { id ->
                    if (id == null) {
                        flowOf<Pair<RouteTemplateEntity?, List<TemplateStopEntity>>>(null to emptyList())
                    } else {
                        combine(templateRepository.observeTemplate(id), templateRepository.observeTemplateStops(id)) { t, s -> t to s }
                    }
                }
                combine(day, routeRepository.observeStops(today.id), template, driving, templateRepository.observeTemplates()) {
                    d, stops, (opened, openedStops), isDriving, templates ->
                    _state.value.copy(
                        dayId = d.id,
                        dayStopCount = stops.size,
                        templates = templates,
                        openedTemplate = opened,
                        banner = TemplateBannerLogic.banner(d, stops, opened, openedStops, isDriving),
                        driving = isDriving,
                    )
                }.collect { _state.value = it }
            }
        }
    }

    fun saveNew(name: String) = write { dayId ->
        Diag.event("pohja", "tallenna uutena '$name'")
        templateRepository.saveNew(dayId, name)
        showNotice(R.string.template_saved, name.trim())
    }

    fun overwrite(templateId: Long) = write { dayId ->
        Diag.event("pohja", "korvaa ${nameOf(templateId)}")
        templateRepository.overwrite(templateId, dayId)
        showNotice(R.string.template_saved, nameOf(templateId))
    }

    fun open(templateId: Long, mode: OpenMode) = write { dayId ->
        val name = nameOf(templateId)
        Diag.event("pohja", "avaa $name $mode")
        val skipped = templateRepository.open(templateId, dayId, mode)
        if (skipped > 0) {
            showNotice(application.resources.getQuantityString(R.plurals.template_opened_skipped, skipped, name, skipped))
        } else {
            showNotice(R.string.template_opened, name)
        }
    }

    fun rename(templateId: Long, name: String) = write {
        Diag.event("pohja", "nimeä ${nameOf(templateId)} -> '$name'")
        templateRepository.rename(templateId, name)
    }

    fun delete(templateId: Long) = write {
        val name = nameOf(templateId)
        Diag.event("pohja", "poista $name")
        templateRepository.delete(templateId)
        showNotice(R.string.template_deleted, name)
    }

    fun discardChanges() = write { dayId ->
        Diag.event("pohja", "hylkää muutokset")
        templateRepository.discardChanges(dayId)
    }

    /** Myöhemmin: rivi piiloon, kunnes päivän asiakasstopit muuttuvat taas. */
    fun later() = write { dayId ->
        val banner = _state.value.banner ?: return@write
        Diag.event("pohja", "myöhemmin ${banner.templateName}")
        templateRepository.acknowledge(dayId, banner.ackKey)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** Kirjoitukset vain, kun päivä tunnetaan eikä ajo ole käynnissä; virhe näytetään ilmoituksena. */
    private fun write(block: suspend (dayId: Long) -> Unit) {
        val snapshot = _state.value
        if (snapshot.driving) return
        val dayId = snapshot.dayId ?: return
        viewModelScope.launch {
            try {
                block(dayId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.event("reitit", "virhe ${e.javaClass.simpleName}: ${e.message}")
                showNotice(application.getString(UserErrors.messageRes(e, online = false)))
            }
        }
    }

    private fun nameOf(templateId: Long): String =
        _state.value.templates.firstOrNull { it.template.id == templateId }?.template?.name ?: ""

    private fun showNotice(resId: Int, vararg args: Any) = showNotice(application.getString(resId, *args))

    private fun showNotice(message: String) {
        Diag.event("pohja", "ilmoitus $message")
        noticeJob?.cancel()
        _state.value = _state.value.copy(notice = message)
        noticeJob = viewModelScope.launch {
            delay(5_000)
            _state.value = _state.value.copy(notice = null)
        }
    }
}
