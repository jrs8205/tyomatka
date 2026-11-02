package fi.tyomatka.data.repo

import fi.tyomatka.data.db.RouteDayEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Päivän lisätiedot kirjoitetaan normalisoituna; tuntematon päivä ei tee mitään. */
class WorkLogNoteTest {
    private fun store() = WorkLogTestStore().apply { days.add(RouteDayEntity(id = 1, dateEpochDay = 20_700)) }

    @Test fun `teksti tallentuu normalisoituna ja tyhjennys tyhjentaa`() = runTest {
        val store = store()
        store.repository.setDayNote(1, "  Latauspiste rikki \r\n")
        assertEquals("Latauspiste rikki", store.days.single().note)
        store.repository.setDayNote(1, "   ")
        assertEquals("", store.days.single().note)
    }

    @Test fun `paivamaaralla kirjoitus osuu samaan riviin`() = runTest {
        val store = store()
        store.repository.setDayNoteByDate(20_700, "Rengas vaihdettu")
        assertEquals("Rengas vaihdettu", store.days.single().note)
    }

    @Test fun `tuntematon paiva ei kaada eika luo rivia`() = runTest {
        val store = store()
        store.repository.setDayNote(99, "x")
        store.repository.setDayNoteByDate(20_701, "x")
        assertEquals(1, store.days.size)
        assertEquals("", store.days.single().note)
    }
}
