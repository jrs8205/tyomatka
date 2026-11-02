package fi.tyomatka.ui.settings

import android.Manifest
import android.os.Build

data class BondedDevice(val address: String, val name: String?)

data class DeviceRow(val address: String, val label: String, val selected: Boolean, val paired: Boolean)

/** Muistutuksen käyttöönoton puuttuvat luvat. Bluetooth-lupaa ei pyydetä ennen kuin Bluetooth-muistutus otetaan käyttöön. */
fun remindersPermissions(sdk: Int, bluetooth: Boolean, granted: Set<String>): List<String> = buildList {
    if (bluetooth && sdk >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.filterNot { it in granted }

/** Parilaitteet aakkosissa; pariluettelosta poistettu valittu laite jää listan loppuun, jotta valinnan voi poistaa. */
fun deviceRows(bonded: List<BondedDevice>, selected: Set<String>, pairedKnown: Boolean = true): List<DeviceRow> {
    val paired = bonded
        .map { DeviceRow(it.address, it.name?.takeIf(String::isNotBlank) ?: addressTail(it.address), it.address in selected, paired = true) }
        .sortedBy { it.label.lowercase() }
    val known = bonded.map { it.address }.toSet()
    val gone = (selected - known).sorted().map { DeviceRow(it, addressTail(it), selected = true, paired = !pairedKnown) }
    return paired + gone
}

private fun addressTail(address: String) = address.takeLast(5)
