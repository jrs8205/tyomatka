package fi.tyomatka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fi.tyomatka.MainActivity
import fi.tyomatka.R
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveSessionManager
import fi.tyomatka.ui.formatClock
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Pitää navigointisession hengissä taustalla ja näyttää notifikaatiossa
 * seuraavan stopin, sen ETA:n ja päivän valmistumisennusteen.
 */
@AndroidEntryPoint
class NavigationForegroundService : LifecycleService() {

    @Inject
    lateinit var sessionManager: DriveSessionManager


    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("", ""),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        lifecycleScope.launch {
            sessionManager.state.collect { state ->
                // START_STICKY voi käynnistää palvelun uudelleen prosessin
                // kuoltua, jolloin sessiota ei enää ole — ei jäädä haamuksi.
                if (state.phase == DrivePhase.IDLE || state.phase == DrivePhase.FINISHED) {
                    stopSelf()
                    return@collect
                }
                val current = state.currentStop
                val eta = state.schedule?.stopEtas?.firstOrNull()?.arrivalEpochSec
                val finish = state.schedule?.finishEpochSec
                val title = current?.label ?: getString(R.string.notification_title)
                val text = buildString {
                    if (eta != null) append(getString(R.string.eta_short, formatTime(eta)))
                    if (finish != null) {
                        if (isNotEmpty()) append(" · ")
                        append(getString(R.string.finish_estimate, formatTime(finish)))
                    }
                }
                notifyUpdate(title, text)
            }
        }
        return START_STICKY
    }

    private fun formatTime(epochSec: Long): String =
        formatClock(epochSec)

    private fun notifyUpdate(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(title.ifBlank { getString(R.string.notification_title) })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "navigation"
        const val NOTIFICATION_ID = 1
    }
}
