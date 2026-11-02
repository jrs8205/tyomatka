package fi.tyomatka

import android.app.Application
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.HiltAndroidApp
import fi.tyomatka.data.repo.AppSettingsStore
import fi.tyomatka.data.repo.SyncSettings
import fi.tyomatka.diag.AppStartup
import fi.tyomatka.diag.Diag
import fi.tyomatka.data.photo.FillupPhotoRepository
import fi.tyomatka.data.photo.PhotoStore
import fi.tyomatka.data.repo.PoiRepository
import fi.tyomatka.data.repo.ReportRepository
import fi.tyomatka.data.repo.ValidatedNetworks
import fi.tyomatka.nav.ferrostar.GuidanceVoice
import fi.tyomatka.ui.DisplayLanguage
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.theme.applyTheme
import fi.tyomatka.work.WorkReminderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TyomatkaApp : Application() {

    @Inject
    lateinit var workReminderManager: WorkReminderManager

    @Inject
    lateinit var appSettings: AppSettingsStore

    @Inject
    lateinit var syncSettings: SyncSettings

    @Inject
    lateinit var photoStore: PhotoStore

    @Inject
    lateinit var photoRepository: FillupPhotoRepository

    @Inject
    lateinit var poiRepository: PoiRepository

    @Inject
    lateinit var reportRepository: ReportRepository

    /** Debug-buildin diagnostiikkaloki; julkaisuversiossa joukko on tyhjä. */
    @Inject
    lateinit var startups: Set<@JvmSuppressWildcards AppStartup>

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DisplayLanguage.update(resources.configuration)
    }

    override fun onCreate() {
        // Ennen injektiota: palvelut lukevat kielen luodessaan.
        DisplayLanguage.update(resources.configuration)
        super.onCreate()
        // Kirjoittimen asentaa vain debug-buildin DiagStartup: julkaisuversiossa tapahtumat ohitetaan kokonaan, eikä
        // hakusanoja, rekisterinumeroita tai osoitteita päädy edes Androidin lokiin.
        startups.forEach { it.start(this) }
        Diag.event("sovellus", "käynnistys versio ${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
        Tap.install(this)
        applyTheme(this, syncSettings.theme)
        // Edellisten prosessien vanhat keskeneräiset kuvat pois; tuoreet voivat kuulua palautuvaan lomakkeeseen.
        photoStore.clearStalePending()
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Lähettämättömät ilmoitukset lähtevät heti, kun internetyhteys toimii (ilmoitusvalikko lupaa niin): uusi
        // oletusverkko (myös verkko, jonka vahvistus on estetty) sekä vahvistus, kun saman Wi-Fi-verkon internet palaa tai
        // kirjautumissivu vapautuu. Pelkkä onAvailable ei riitä, koska verkko-olio ei vaihdu, kun internet katkeaa ja palaa
        // Wi-Fi-yhteyden säilyessä. Peräkkäiset lähetykset ovat harmittomia (Mutex, palvelin kuittaa saman rid:n).
        val networks = ValidatedNetworks<Network>()
        val flush = { io.launch { runCatching { reportRepository.flush() } } }
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networks.available(network)
                    flush()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (networks.update(network, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))) flush()
                }

                override fun onLost(network: Network) = networks.lost(network)
            },
        )
        io.launch {
            appSettings.settings.collect {
                Tap.enabled = it.tapVibration
                GuidanceVoice.preferredVoice = it.voiceName
                GuidanceVoice.vibrationEnabled = it.guidanceVibration
            }
        }
        io.launch {
            runCatching { workReminderManager.reschedule() }
            runCatching { photoRepository.sweep() }
            // Karttakohteiden yli 30 päivän välimuisti pois.
            runCatching { poiRepository.prune() }
            // Laitteen aiemmat omat korjaukset kerran ilmoituksiksi, sitten edellisellä kerralla lähtemättömät ilmoitukset.
            runCatching { reportRepository.migrateLocalEdits() }
            runCatching { reportRepository.flush() }
        }
    }
}
