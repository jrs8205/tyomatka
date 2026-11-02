package fi.tyomatka.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ottaa vastaan muistutuksen kellonajan ja ilmoituksen "Ohita tänään". */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var manager: WorkReminderManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WorkReminderManager.ACTION_SKIP -> launchAsync { manager.skipToday() }
            WorkReminderManager.ACTION_TIME -> launchAsync {
                try {
                    manager.maybeRemind()
                } finally {
                    manager.reschedule()
                }
            }
        }
    }
}

/** goAsync-kuvio: suspend-työ saa valmistua onReceiven jälkeen. */
internal fun BroadcastReceiver.launchAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
