package fi.tyomatka.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fi.tyomatka.R
import fi.tyomatka.diag.Diag
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.ui.drive.DriveLayer
import fi.tyomatka.ui.drive.DriveScreen
import fi.tyomatka.ui.drive.DriveViewModel
import fi.tyomatka.ui.history.HistoryScreen
import fi.tyomatka.ui.map.OverviewScreen
import fi.tyomatka.ui.plan.PlanScreen
import fi.tyomatka.ui.poi.ReportRejectedViewModel
import fi.tyomatka.ui.settings.bottomBarVisible
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp

private data class Tab(val route: String, val labelRes: Int, val icon: @Composable () -> Unit)

@Composable
fun TyomatkaNavHost(
    onStartDrive: (Long) -> Unit,
    onStartSimulatedDrive: (Long) -> Unit,
    uiEvents: UiEvents,
    dimmed: StateFlow<Boolean>,
    sharedPhotos: StateFlow<List<File>>,
) {
    val navController = rememberNavController()
    // Sovellukseen jaettu kuva: siirry Työmatka-sivulle, joka avaa kirjauksen ja kuluttaa kuvat.
    LaunchedEffect(sharedPhotos) {
        sharedPhotos.collect { files ->
            if (files.isNotEmpty()) {
                navController.navigate("history") {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
    // Muistutusilmoituksen "Kirjaa nyt": siirry Työmatka-sivulle; lipun
    // kuluttaa HistoryScreen avattuaan aloitusdialogin.
    LaunchedEffect(uiEvents) {
        uiEvents.openWorkStart.collect { requested ->
            if (requested) {
                navController.navigate("history") {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
    val tabs = listOf(
        Tab("plan", R.string.tab_plan) { Icon(Icons.AutoMirrored.Filled.List, null) },
        Tab("map", R.string.tab_map) { Icon(Icons.Filled.Map, null) },
        Tab("drive", R.string.tab_drive) { Icon(Icons.Filled.Navigation, null) },
        Tab("history", R.string.tab_history_short) { Icon(Icons.Filled.DirectionsCar, null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Ajonäkymä elää aktiviteetin tasolla koko ajosession: kartta, reitti ja kamera säilyvät välilehtivaihdossa.
    val driveViewModel: DriveViewModel = hiltViewModel()
    val driveState by driveViewModel.driveState.collectAsStateWithLifecycle()
    val driving = driveState.phase == DrivePhase.NAVIGATING || driveState.phase == DrivePhase.AT_STOP

    val hideBottomBar by driveViewModel.hideBottomBar.collectAsStateWithLifecycle()

    // Palvelimen hylkäämä ilmoitus kerrotaan vasta ajon jälkeen: valintaikkuna ei kuulu ajonäkymän päälle.
    val rejectedViewModel: ReportRejectedViewModel = hiltViewModel()
    val rejected by rejectedViewModel.unseen.collectAsStateWithLifecycle()
    if (rejected.isNotEmpty() && !driving) {
        // Kiinteä arvo: napautus kuittaa vain tämän näkyvän määrän, vaikka uusi hylkäys ehtisi tilaan ennen seuraavaa ruutua.
        val shown = rejected
        AlertDialog(
            onDismissRequest = { rejectedViewModel.acknowledge(shown) },
            text = { Text(stringResource(R.string.poi_report_rejected, shown.size)) },
            confirmButton = { TextButton(onClick = Tap { rejectedViewModel.acknowledge(shown) }) { Text(stringResource(R.string.ok)) } },
        )
    }

    Scaffold(
        bottomBar = {
            if (bottomBarVisible(hideBottomBar, currentRoute, driving)) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = Tap {
                            Diag.event("nav", "välilehti ${tab.route}")
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = tab.icon,
                        // Yksi rivi ja tarvittaessa pienempi teksti: suurella fonttikoolla nimi ei katkea kesken sanan.
                        label = {
                            Text(
                                stringResource(tab.labelRes),
                                maxLines = 1,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                            )
                        },
                    )
                }
            }
        }
    ) { padding ->
        // Alapalkin korkeus sisältää jo navigointipalkin insetin; ilman
        // kulutusta sivujen omat Scaffoldit lisäisivät sen toiseen kertaan.
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            NavHost(
                navController = navController,
                startDestination = "plan",
                // Oletus häivyttää sivut ristiin 700 ms: edellisen sivun osat jäivät haamuina uuden päälle.
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
                // Läpinäkymätön pohja ja kosketuskohde: piilotettu ajonäkymä on tämän takana, eikä sen saa
                // saada kosketuksia, vaikka jokin välilehti jättäisi tyhjää tilaa.
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {},
            ) {
                composable("plan") { PlanScreen() }
                composable("map") { OverviewScreen() }
                composable("drive") {
                    DriveScreen(onStartDrive = onStartDrive, onStartSimulatedDrive = onStartSimulatedDrive)
                }
                composable("history") { HistoryScreen() }
            }
            if (driving) {
                // Sama kompositiokohta näkyvänä ja piilossa: vain modifier vaihtuu, joten kartan tila säilyy.
                val visible = currentRoute == "drive"
                DriveLayer(
                    state = driveState,
                    viewModel = driveViewModel,
                    dimmed = dimmed,
                    visible = visible,
                    modifier = if (visible) {
                        Modifier.fillMaxSize().zIndex(1f)
                    } else {
                        Modifier.fillMaxSize().zIndex(-1f).clearAndSetSemantics {}
                    },
                )
            }
        }
    }
}
