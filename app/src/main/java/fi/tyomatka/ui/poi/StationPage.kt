package fi.tyomatka.ui.poi

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** Ketjun oma asemasivu Custom Tabissa (kuljettaja pysyy sovelluksessa); ilman selaintukea tavallinen selain. */
fun openStationPage(context: Context, url: String) {
    val uri = url.toUri()
    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }
        .onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
}
