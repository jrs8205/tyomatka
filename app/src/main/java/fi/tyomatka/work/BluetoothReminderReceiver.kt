package fi.tyomatka.work

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Puhelin yhdistyi Bluetooth-laitteeseen: muistutus, jos laite on valittu asetuksista. Muista laitteista ei tehdä mitään. */
@AndroidEntryPoint
class BluetoothReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var manager: WorkReminderManager

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val address = runCatching {
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address
        }.getOrNull()
        launchAsync { manager.onBluetoothConnected(address) }
    }
}
