package fi.tyomatka.work

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.tyomatka.MainActivity
import fi.tyomatka.R
import fi.tyomatka.core.work.nextReminder
import fi.tyomatka.data.db.RouteDayDao
import fi.tyomatka.data.db.WorkSegmentDao
import fi.tyomatka.data.repo.ReminderSettingsStore
import fi.tyomatka.data.repo.shouldRemindOnConnect
import fi.tyomatka.data.repo.shouldRemindWork
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Muistuttaa kirjaamattomasta työpäivästä valitun Bluetooth-laitteen yhdistyessä tai valittuna kellonaikana.
 * Hälytys ei säily uudelleenkäynnistyksen yli, joten se ajastetaan uudelleen sovelluksen käynnistyessä ja
 * BootReceiverissä. Tarkka hälytys, koska epätarkka voisi myöhästyä jopa tunnin yön levon jälkeen.
 */
@Singleton
class WorkReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeDayDao: RouteDayDao,
    private val workSegmentDao: WorkSegmentDao,
    private val dataStore: DataStore<Preferences>,
    private val settings: ReminderSettingsStore,
) {
    /** Ajastaa seuraavan kellonaikamuistutuksen tai peruu sen. */
    suspend fun reschedule() {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(timePendingIntent())
        val current = settings.settings.first()
        if (!current.timeEnabled) return
        val next = nextReminder(ZonedDateTime.now(), LocalTime.ofSecondOfDay(current.minuteOfDay * 60L), current.days) ?: return
        val atMs = next.toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, timePendingIntent())
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, timePendingIntent())
        }
    }

    private fun timePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_TIME),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    suspend fun onBluetoothConnected(address: String?) {
        if (shouldRemindOnConnect(settings.settings.first(), address)) maybeRemind()
    }

    /** Näytä muistutus, jos tämän päivän kirjaus puuttuu. Ilman ilmoituslupaa päivää ei merkitä muistutetuksi. */
    suspend fun maybeRemind() {
        val today = LocalDate.now().toEpochDay()
        val day = routeDayDao.byDate(today)
        val hasSegments = day != null && workSegmentDao.forDay(day.id).isNotEmpty()
        val prefs = dataStore.data.first()
        val notifications = canPostNotifications()
        if (!shouldRemindWork(hasSegments, today, prefs[SKIPPED_DAY], prefs[NOTIFIED_DAY], notifications)) return
        dataStore.edit { it[NOTIFIED_DAY] = today }
        showNotification()
    }

    /** "Ohita tänään": ei enää muistutuksia tänä päivänä. */
    suspend fun skipToday() {
        dataStore.edit { it[SKIPPED_DAY] = LocalDate.now().toEpochDay() }
        cancelReminder()
    }

    /** Piilota muistutus — toimintonapin kosketus ei itsessään sulje sitä
     *  (setAutoCancel koskee vain sisältökosketusta). */
    fun cancelReminder() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Android 13+: ilman POST_NOTIFICATIONS-lupaa areNotificationsEnabled on jo false,
     *  mutta lupa tarkistetaan myös suoraan. */
    private fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    private fun showNotification() {
        val manager = NotificationManagerCompat.from(context)
        getSystemService(context).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.work_reminder_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val recordIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_WORK_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(context.getString(R.string.work_reminder_title))
            .setContentText(context.getString(R.string.work_reminder_text))
            .setContentIntent(recordIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, context.getString(R.string.work_reminder_record), recordIntent)
            .addAction(0, context.getString(R.string.work_reminder_skip), skipIntent)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Lupa peruttu tarkistuksen jälkeen: muistutus jää näyttämättä.
        }
    }

    private fun getSystemService(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_TIME = "fi.tyomatka.WORK_REMINDER_TIME"
        const val ACTION_SKIP = "fi.tyomatka.WORK_REMINDER_SKIP"
        private const val CHANNEL_ID = "work_reminder"
        private const val NOTIFICATION_ID = 2
        private val SKIPPED_DAY = longPreferencesKey("work_reminder_skipped_day")
        private val NOTIFIED_DAY = longPreferencesKey("work_reminder_notified_day")
    }
}
