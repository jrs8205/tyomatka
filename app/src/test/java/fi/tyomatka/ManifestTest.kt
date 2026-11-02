package fi.tyomatka

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestTest {
    private val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private val android = "http://schemas.android.com/apk/res/android"

    private fun elements(tag: String): List<Element> =
        document.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun receiver(name: String) = elements("receiver").single { it.getAttributeNS(android, "name") == name }

    private val permissions = elements("uses-permission").map { it.getAttributeNS(android, "name") }

    @Test
    fun `pilvivarmuuskopio on pois päältä`() {
        val application = elements("application").single()

        assertEquals("false", application.getAttributeNS(android, "allowBackup"))
        assertEquals("", application.getAttributeNS(android, "fullBackupContent"))
        assertEquals("", application.getAttributeNS(android, "dataExtractionRules"))
    }

    @Test
    fun `bluetooth-vastaanottimen pitää olla viety`() {
        // Lähettäjä on Bluetooth-paketti eikä järjestelmä, joten ei-viety vastaanotin ei saisi lähetystä koskaan.
        assertEquals("true", receiver(".work.BluetoothReminderReceiver").getAttributeNS(android, "exported"))
    }

    @Test
    fun `muut vastaanottimet eivät ole viety`() {
        assertEquals("false", receiver(".work.ReminderReceiver").getAttributeNS(android, "exported"))
        assertEquals("false", receiver(".work.BootReceiver").getAttributeNS(android, "exported"))
    }

    @Test
    fun `ajontunnistusta ei enää pyydetä`() {
        assertFalse("android.permission.ACTIVITY_RECOGNITION" in permissions)
    }

    @Test
    fun `tarkka hälytys ja bluetooth ovat luvissa`() {
        assertTrue("android.permission.USE_EXACT_ALARM" in permissions)
        assertTrue("android.permission.SCHEDULE_EXACT_ALARM" in permissions)
        assertTrue("android.permission.BLUETOOTH_CONNECT" in permissions)
    }
}
