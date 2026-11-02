package fi.tyomatka.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import fi.tyomatka.R
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.history.PlateField
import fi.tyomatka.ui.history.forms.OptionalFuelChips
import kotlinx.coroutines.launch

/**
 * Ensikäynnistyksen esittely: sivu 1 kertoo mitä sovellus tekee, sivu 2 kysyy auton (ohitettavissa), sivu 3 pyytää
 * kaikki luvat yhdellä napilla. Aloita toimii myös ilman lupia; auto tallentuu esittelyn päättyessä.
 */
@Composable
fun OnboardingScreen(onFinish: (OnboardingCar) -> Unit) {
    val pagerState = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    // Auton tiedot säilyvät sivujen välillä ja prosessin kuoleman yli.
    var car by rememberSaveable(stateSaver = OnboardingCarSaver) { mutableStateOf(OnboardingCar()) }
    // Takaisin palaa edelliselle sivulle eikä sulje sovellusta kirjoitetun auton kanssa.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> CarPage(car, onPlate = { car = car.withPlate(it) }, onFuel = { car = car.withFuel(it) })
                    else -> PermissionsPage()
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageDots(count = PAGE_COUNT, current = pagerState.currentPage)
                Spacer(Modifier.weight(1f))
                val page = pagerState.currentPage
                if (page < PAGE_COUNT - 1) {
                    Button(onClick = Tap { scope.launch { pagerState.animateScrollToPage(page + 1) } }) {
                        // Autosivun voi ohittaa: tyhjällä rekisterinumerolla nappi kertoo sen.
                        Text(stringResource(if (page == 1 && car.skips) R.string.onboarding_skip else R.string.onboarding_next))
                    }
                } else {
                    Button(onClick = Tap { onFinish(car) }) { Text(stringResource(R.string.onboarding_start)) }
                }
            }
        }
    }
}

private const val PAGE_COUNT = 3

private val OnboardingCarSaver = listSaver<OnboardingCar, String?>(
    save = { listOf(it.plate, it.fuel?.name) },
    restore = { OnboardingCar(it[0].orEmpty(), it[1]?.let(FuelKind::valueOf)) },
)

@Composable
private fun WelcomePage() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        FeatureRow(Icons.Filled.Map, stringResource(R.string.onboarding_feature_route))
        FeatureRow(Icons.Filled.Navigation, stringResource(R.string.onboarding_feature_nav))
        FeatureRow(Icons.Filled.Description, stringResource(R.string.onboarding_feature_log))
    }
}

/** Auto heti alussa: oletus työpäivälle ja kirjauksille, käyttövoima valitsee sanaston (lataus, täyttö, tankkaus). */
@Composable
private fun CarPage(car: OnboardingCar, onPlate: (String) -> Unit, onFuel: (FuelKind?) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_car_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.onboarding_car_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        PlateField(car.plate, onPlate, stringResource(R.string.fuel_plate), knownPlates = emptyList())
        OptionalFuelChips(car.fuel, label = stringResource(R.string.onboarding_car_fuel), onSelect = onFuel)
        Text(
            stringResource(R.string.onboarding_car_later),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    val sdkInt = Build.VERSION.SDK_INT
    var granted by remember { mutableStateOf(grantedPermissions(context)) }
    var requestedOnce by remember { mutableStateOf(false) }
    // Palattaessa järjestelmäasetuksista tilat luetaan uudelleen.
    LifecycleResumeEffect(Unit) {
        granted = grantedPermissions(context)
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = grantedPermissions(context)
    }
    val toRequest = OnboardingPermissions.toRequest(sdkInt, granted)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.onboarding_permissions_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        OnboardingPermissions.applicable(sdkInt).forEach { card ->
            val isGranted = OnboardingPermissions.granted(card, sdkInt, granted)
            PermissionCardView(
                card = card,
                granted = isGranted,
                showSettingsLink = requestedOnce && !isGranted,
                onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
            )
        }
        if (toRequest.isNotEmpty()) {
            Button(
                onClick = Tap {
                    requestedOnce = true
                    launcher.launch(toRequest.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(R.string.onboarding_grant)) }
        }
    }
}

@Composable
private fun PermissionCardView(
    card: PermissionCard,
    granted: Boolean,
    showSettingsLink: Boolean,
    onOpenSettings: () -> Unit,
) {
    val (icon, titleRes, bodyRes) = when (card.key) {
        PermissionCardKey.LOCATION ->
            Triple(Icons.Filled.LocationOn, R.string.onboarding_location_title, R.string.onboarding_location_body)
        PermissionCardKey.NOTIFICATIONS ->
            Triple(Icons.Filled.Notifications, R.string.onboarding_notifications_title, R.string.onboarding_notifications_body)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (granted) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        stringResource(if (granted) R.string.onboarding_granted else R.string.onboarding_missing),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (showSettingsLink) {
                    TextButton(onClick = Tap(onOpenSettings), contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.onboarding_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(if (index == current) 10.dp else 8.dp)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
            )
        }
    }
}

private fun grantedPermissions(context: Context): Set<String> =
    OnboardingPermissions.cards.flatMap { it.permissions }
        .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        .toSet()

private fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
