package fi.tyomatka.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Koko näytön dialogi, jonka sisältö mitoitetaan ikkunan todellisen kehyksen
 * mukaan.
 *
 * Android 15:n pakotetussa reunasta reunaan -tilassa kelluva dialogi-ikkuna
 * mitataan koko näytön korkuiseksi, mutta WindowManager sijoittaa sen
 * järjestelmäpalkkien väliin, jolloin sisällön alimmat ~250 px leikkautuvat
 * pois (14 stopin listasta näkyisi vain 13).
 * `usePlatformDefaultWidth = false` ei kelpaa, koska silloin Compose asettaa
 * ikkunan koon joka layoutissa sisällön mitatun koon mukaan ja ylikirjoittaa
 * alla asetetun MATCH_PARENTin. Oletustilassa MATCH_PARENT pysyy voimassa,
 * ikkuna on kehyksen kokoinen ja `fillMaxSize`-sisältö mahtuu näkyviin.
 * Kehys ei mene palkkien alle, joten `systemBarsPadding()` on sisällössä nollaa.
 */
@Composable
fun FullScreenDialog(
    onDismissRequest: () -> Unit,
    // false: takaisin-ele ei sulje ikkunaa, vaan sisältö käsittelee sen itse (lisäysikkunan sivut).
    dismissOnBackPress: Boolean = true,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = true, dismissOnBackPress = dismissOnBackPress),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            // Ikkuna peittää koko kehyksen, joten himmennys näkyisi vain järjestelmäpalkeissa: vaaleassa teemassa
            // kellon ja kuvakkeiden kontrasti heikkenisi harmaata taustaa vasten.
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        content()
    }
}
