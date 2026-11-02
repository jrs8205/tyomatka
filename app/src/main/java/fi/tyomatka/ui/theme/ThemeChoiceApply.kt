package fi.tyomatka.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import fi.tyomatka.data.repo.ThemeChoice

/**
 * Teema koko sovellukseen ennen ensimmäistä ruutua. API 31:stä alkaen myös järjestelmälle, koska AppCompat ei kerro valintaa
 * järjestelmän aloitusikkunalle, joka muuten välähtäisi väärällä teemalla.
 */
fun applyTheme(context: Context, choice: ThemeChoice) {
    AppCompatDelegate.setDefaultNightMode(
        when (choice) {
            ThemeChoice.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeChoice.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeChoice.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        },
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
            when (choice) {
                ThemeChoice.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }
}
