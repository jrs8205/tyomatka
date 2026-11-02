package fi.tyomatka.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.tyomatka.ui.onboarding.OnboardingScreen
import fi.tyomatka.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Esittely ensikäynnistyksellä, muuten välilehdet. Lipun lukemisen ajan tyhjä pinta estää välähdyksen. */
@Composable
fun TyomatkaRoot(
    onStartDrive: (Long) -> Unit,
    onStartSimulatedDrive: (Long) -> Unit,
    uiEvents: UiEvents,
    dimmed: StateFlow<Boolean>,
    sharedPhotos: StateFlow<List<File>>,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val done by viewModel.isDone.collectAsStateWithLifecycle()
    when (done) {
        null -> Surface(Modifier.fillMaxSize()) {}
        false -> OnboardingScreen(
            onFinish = viewModel::finish,
        )
        true -> TyomatkaNavHost(
            onStartDrive = onStartDrive,
            onStartSimulatedDrive = onStartSimulatedDrive,
            uiEvents = uiEvents,
            dimmed = dimmed,
            sharedPhotos = sharedPhotos,
        )
    }
}
