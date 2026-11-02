package fi.tyomatka.ui.history

import fi.tyomatka.data.repo.WorkHistoryDay
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryNoteTest {
    private fun day(date: Long, note: String) = WorkHistoryDay(date, emptyList(), emptyList(), note)

    @Test
    fun `tanaan-kortin lisatieto paivittyy kannan paivista`() {
        // Aloita työt -lomakkeen teksti ei näkynyt Lopeta työt -lomakkeessa, ja tallennus tyhjensi sen.
        val days = listOf(day(20_380, "eilen"), day(20_381, "aamulla kirjoitettu"))
        assertEquals("aamulla kirjoitettu", todayNoteFrom(days, todayEpochDay = 20_381, current = ""))
        assertEquals("", todayNoteFrom(listOf(day(20_381, "")), todayEpochDay = 20_381, current = "vanha"))
    }

    @Test
    fun `ilman paivan rivia tai paivaa nykyinen teksti sailyy`() {
        assertEquals("nykyinen", todayNoteFrom(listOf(day(20_380, "eilen")), todayEpochDay = 20_381, current = "nykyinen"))
        assertEquals("nykyinen", todayNoteFrom(emptyList(), todayEpochDay = null, current = "nykyinen"))
    }
}
