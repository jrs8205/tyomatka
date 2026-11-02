package fi.tyomatka.ui.settings

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderLogicTest {
    @Test
    fun `bluetooth pyytää luvan vain api 31 alkaen`() {
        assertEquals(emptyList(), remindersPermissions(sdk = 30, bluetooth = true, granted = emptySet()))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), remindersPermissions(sdk = 31, bluetooth = true, granted = emptySet()))
    }

    @Test
    fun `kellonaika ei pyydä bluetooth-lupaa`() {
        assertEquals(emptyList(), remindersPermissions(sdk = 31, bluetooth = false, granted = emptySet()))
    }

    @Test
    fun `ilmoituslupa pyydetään api 33 alkaen molemmille`() {
        assertEquals(emptyList(), remindersPermissions(sdk = 32, bluetooth = false, granted = emptySet()))
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), remindersPermissions(sdk = 33, bluetooth = false, granted = emptySet()))
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS),
            remindersPermissions(sdk = 33, bluetooth = true, granted = emptySet()),
        )
    }

    @Test
    fun `myönnettyä lupaa ei pyydetä uudelleen`() {
        val granted = setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)

        assertEquals(emptyList(), remindersPermissions(sdk = 36, bluetooth = true, granted = granted))
    }

    @Test
    fun `laitteet aakkosissa ja valinta merkitty`() {
        val rows = deviceRows(
            listOf(BondedDevice("AA:AA", "Volvo"), BondedDevice("BB:BB", "Auto")),
            selected = setOf("AA:AA"),
        )

        assertEquals(
            listOf(
                DeviceRow("BB:BB", "Auto", selected = false, paired = true),
                DeviceRow("AA:AA", "Volvo", selected = true, paired = true),
            ),
            rows,
        )
    }

    @Test
    fun `nimetön laite näyttää osoitteen lopun`() {
        val rows = deviceRows(
            listOf(BondedDevice("11:22:33:44:55:66", null), BondedDevice("AA:BB:CC:DD:EE:FF", "  ")),
            selected = emptySet(),
        )

        assertEquals(listOf("55:66", "EE:FF"), rows.map { it.label })
    }

    @Test
    fun `pariluettelosta poistettu valittu laite jää poistettavaksi riviksi`() {
        val rows = deviceRows(listOf(BondedDevice("AA:AA", "Volvo")), selected = setOf("11:22:33:44:55:66"))

        assertEquals(
            listOf(
                DeviceRow("AA:AA", "Volvo", selected = false, paired = true),
                DeviceRow("11:22:33:44:55:66", "55:66", selected = true, paired = false),
            ),
            rows,
        )
    }

    @Test
    fun `tuntematon pariluettelo ei merkitse valittua poistetuksi`() {
        val rows = deviceRows(emptyList(), selected = setOf("11:22:33:44:55:66"), pairedKnown = false)

        assertEquals(listOf(DeviceRow("11:22:33:44:55:66", "55:66", selected = true, paired = true)), rows)
    }
}
