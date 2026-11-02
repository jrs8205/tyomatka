package fi.tyomatka

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fi.tyomatka.data.repo.AppSettingsStore
import fi.tyomatka.data.repo.ServerUrls
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveSessionManager
import fi.tyomatka.ui.DisplayLanguage
import fi.tyomatka.ui.SharedPhotos
import fi.tyomatka.ui.TyomatkaRoot
import fi.tyomatka.ui.UiEvents
import fi.tyomatka.ui.map.LocalMapStyleUrl
import fi.tyomatka.ui.withCappedFontScale
import fi.tyomatka.ui.theme.TyomatkaTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var sessionManager: DriveSessionManager

    @Inject
    lateinit var appSettings: AppSettingsStore

    @Inject
    lateinit var serverUrls: ServerUrls

    @Inject
    lateinit var uiEvents: UiEvents

    @Inject
    lateinit var sharedPhotos: SharedPhotos

    /** Ajopyyntö, joka odottaa lupia/käyttöehtoja. */
    private var pendingStartDayId: Long? = null
    private var pendingSimulate = false

    private lateinit var screenAwake: ScreenAwakeController

    /** Himmennettynä alkanut ele kulutetaan kokonaan — se vain herättää näytön. */
    private var consumeGesture = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Tuloskartassa on vain pyydetyt luvat: jos sijainti oli jo myönnetty
        // ja vain ilmoituslupaa kysyttiin, sijaintia ei saa tulkita evätyksi.
        // Ilmoitusluvan epääminen ei estä ajoa — notifikaatio jää vain näkymättä.
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (locationGranted) {
            pendingStartDayId?.let(::startAfterPermissions)
        } else {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_LONG).show()
            pendingStartDayId = null
        }
    }

    // Järjestelmän fonttikoko enintään 150 %: suuremmalla tekstit katkeaisivat kesken sanan.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withCappedFontScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Sovelluskohtaisen kielen vaihto luo aktiviteetin uudelleen.
        DisplayLanguage.update(resources.configuration)
        // Luodaan ennen setContentia, jotta dimmedFlow on kompositiolle valmis.
        screenAwake = ScreenAwakeController(
            WindowScreenAwakePort(this) { lux -> screenAwake.onLux(lux) },
            lifecycleScope,
        )
        setContent {
            CompositionLocalProvider(LocalMapStyleUrl provides serverUrls.mapStyle) {
            TyomatkaTheme {
                TyomatkaRoot(
                    onStartDrive = { dayId -> requestStartDay(dayId) },
                    onStartSimulatedDrive = { dayId -> requestStartDay(dayId, simulate = true) },
                    uiEvents = uiEvents,
                    dimmed = screenAwake.dimmedFlow,
                    sharedPhotos = sharedPhotos.files,
                )
            }
            }
        }
        lifecycleScope.launch {
            appSettings.settings.map { it.dimInDark }.distinctUntilChanged().collect(screenAwake::setDimEnabled)
        }
        handleUiIntent(intent)
        // Uudelleen luotu aktiviteetti saa saman jakopyynnön: kuvat on jo otettu.
        if (savedInstanceState == null) handleShare(intent)
        lifecycleScope.launch {
            sessionManager.state
                .map { it.phase == DrivePhase.NAVIGATING || it.phase == DrivePhase.AT_STOP }
                .distinctUntilChanged()
                .collect(screenAwake::onDrivingChanged)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUiIntent(intent)
        handleShare(intent)
    }

    /**
     * Sovellukseen jaettu kuva (latausapin kuvakaappaus, kuitti) avaa Työmatka-sivulle kirjauksen kuva liitettynä.
     * Vain sisältöosoitteet: tiedosto-osoite voisi osoittaa sovelluksen omiin tiedostoihin.
     */
    private fun handleShare(intent: Intent?) {
        if (intent?.type?.startsWith("image/") != true) return
        val sources = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        sharedPhotos.receive(sources.filter { it.scheme == ContentResolver.SCHEME_CONTENT })
    }

    /** Muistutusilmoituksen "Kirjaa nyt" avaa Työmatka-sivun aloitusdialogiin. */
    private fun handleUiIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_WORK_START, false) == true) {
            intent.removeExtra(EXTRA_OPEN_WORK_START)
            uiEvents.requestOpenWorkStart()
        }
    }

    override fun onStart() {
        super.onStart()
        screenAwake.onForeground()
    }

    override fun onStop() {
        screenAwake.onBackground()
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::screenAwake.isInitialized) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            consumeGesture = screenAwake.onTouch()
        }
        val consuming = consumeGesture
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            consumeGesture = false
            // Ajastin nollataan myös eleen päättyessä, jotta pitkä raahaus
            // (esim. kartan selailu) ei himmene kesken kaiken.
            if (!consuming) screenAwake.onTouch()
        }
        return if (consuming) true else super.dispatchTouchEvent(ev)
    }

    /** Ajon käynnistys: luvat → sessio. [simulate] on debug-version pöytätesti. */
    private fun requestStartDay(dayId: Long, simulate: Boolean = false) {
        pendingStartDayId = dayId
        pendingSimulate = simulate
        val needed = buildList {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startAfterPermissions(dayId)
        }
    }

    private fun startAfterPermissions(dayId: Long) {
        pendingStartDayId = null
        val simulate = pendingSimulate
        pendingSimulate = false
        sessionManager.startDay(dayId, simulate)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_OPEN_WORK_START = "open_work_start"
    }
}
