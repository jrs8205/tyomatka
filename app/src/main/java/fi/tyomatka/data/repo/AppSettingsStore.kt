package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Sovelluksen valinnat. Oletus on aina käytös ennen asetussivua. */
data class AppSettings(
    /** Puheäänen nimi; null on järjestelmän oletus. */
    val voiceName: String? = null,
    val guidanceVibration: Boolean = true,
    val hideBottomBarDriving: Boolean = false,
    val dimInDark: Boolean = true,
    val tapVibration: Boolean = true,
)

@Singleton
class AppSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map(::read)

    suspend fun update(change: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = change(read(prefs))
            val voice = next.voiceName
            if (voice == null) prefs.remove(KEY_VOICE) else prefs[KEY_VOICE] = voice
            prefs[KEY_GUIDANCE_VIBRATION] = next.guidanceVibration
            prefs[KEY_HIDE_BAR] = next.hideBottomBarDriving
            prefs[KEY_DIM] = next.dimInDark
            prefs[KEY_TAP_VIBRATION] = next.tapVibration
        }
    }

    private fun read(prefs: Preferences) = AppSettings(
        voiceName = prefs[KEY_VOICE],
        guidanceVibration = prefs[KEY_GUIDANCE_VIBRATION] ?: true,
        hideBottomBarDriving = prefs[KEY_HIDE_BAR] ?: false,
        dimInDark = prefs[KEY_DIM] ?: true,
        tapVibration = prefs[KEY_TAP_VIBRATION] ?: true,
    )

    private companion object {
        val KEY_VOICE = stringPreferencesKey("settings_voice")
        val KEY_GUIDANCE_VIBRATION = booleanPreferencesKey("settings_guidance_vibration")
        val KEY_HIDE_BAR = booleanPreferencesKey("settings_hide_bottom_bar")
        val KEY_DIM = booleanPreferencesKey("settings_dim_in_dark")
        val KEY_TAP_VIBRATION = booleanPreferencesKey("settings_tap_vibration")
    }
}
