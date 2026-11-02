package fi.tyomatka.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkGapTest {

    private val friday = 20666L

    @Test
    fun `yksi puuttuva tyopaiva saa koko erotuksen`() {
        val gap = findUnrecordedGap(
            newReadingKm = 461_800,
            lastReading = LastReading(epochDay = friday, km = 461_565, isEnd = true),
            missingWorkDays = listOf(friday + 1),
        )
        assertEquals(235, gap?.diffKm)
        assertEquals(mapOf(friday + 1 to 235L), gap?.perDay)
    }

    @Test
    fun `useampi puuttuva paiva jaetaan tasan ja jaannos vanhimmille`() {
        val gap = findUnrecordedGap(
            newReadingKm = 461_800,
            lastReading = LastReading(epochDay = friday, km = 461_565, isEnd = true),
            missingWorkDays = listOf(friday + 2, friday + 1),
        )
        // 235 = 118 + 117, jäännös vanhimmalle; järjestys nousevaksi.
        assertEquals(listOf(friday + 1, friday + 2), gap?.perDay?.keys?.toList())
        assertEquals(listOf(118L, 117L), gap?.perDay?.values?.toList())
    }

    @Test
    fun `ei ehdotusta ilman aiempaa lukemaa`() {
        assertNull(findUnrecordedGap(461_800, null, listOf(friday + 1)))
    }

    @Test
    fun `ei ehdotusta ilman puuttuvia tyopaivia`() {
        val last = LastReading(friday, 461_565, isEnd = true)
        assertNull(findUnrecordedGap(461_800, last, emptyList()))
    }

    @Test
    fun `ei ehdotusta kun lukema ei ole noussut`() {
        val last = LastReading(friday, 461_565, isEnd = true)
        assertNull(findUnrecordedGap(461_565, last, listOf(friday + 1)))
        assertNull(findUnrecordedGap(461_000, last, listOf(friday + 1)))
    }

    @Test
    fun `muistutus vain kirjaamattomana paivana ja korkeintaan kerran`() {
        val today = friday
        assertEquals(true, shouldRemindWork(false, today, null, null))
        assertEquals(false, shouldRemindWork(true, today, null, null))
        assertEquals(false, shouldRemindWork(false, today, skippedEpochDay = today, notifiedEpochDay = null))
        assertEquals(false, shouldRemindWork(false, today, skippedEpochDay = null, notifiedEpochDay = today))
        // Eilinen ohitus tai ilmoitus ei vaienna tätä päivää.
        assertEquals(true, shouldRemindWork(false, today, skippedEpochDay = today - 1, notifiedEpochDay = today - 1))
    }

    @Test
    fun `tyhja rekkari kummalla tahansa puolella ei esta ehdotusta`() {
        // Vanhat segmentit (ennen v6) ovat ilman rekkaria — ensimmäinen rekkarillinen
        // aloitus ei saa hiljentää välintunnistusta.
        val last = LastReading(epochDay = 100, km = 1000, isEnd = true, licensePlate = null)
        assertNotNull(findUnrecordedGap(1200, last, listOf(101L), newPlate = "ABC-123"))
        assertNotNull(findUnrecordedGap(1200, last.copy(licensePlate = "ABC-123"), listOf(101L), newPlate = null))
    }

    @Test
    fun `eri auto ei saa ehdotusta`() {
        val last = LastReading(epochDay = 100, km = 1000, isEnd = true, licensePlate = "ABC-123")
        assertNull(findUnrecordedGap(1200, last, listOf(101L), newPlate = "xyz 789"))
        assertNotNull(findUnrecordedGap(1200, last, listOf(101L), newPlate = "abc123"))
    }

    @Test
    fun `ilman ilmoituslupaa päivää ei kuluteta`() {
        val today = 20_000L

        assertEquals(false, shouldRemindWork(false, today, null, null, notificationsEnabled = false))
        assertEquals(true, shouldRemindWork(false, today, null, null, notificationsEnabled = true))
    }
}
