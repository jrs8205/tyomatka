package fi.tyomatka.ui.history

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.WorkHistoryDay
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportGateTest {
    private val segment = WorkSegmentEntity(1, 1, 1_700_000_000, 1_700_030_000, 100_000, 100_200, "ABC-123")
    private val fillup = FuelFillupEntity(1, 1_700_000_000, "ABC-123", 100_200, 40.0, 60.0, 1.5, currency = "EUR")
    private val car = CarEntity("UUS-1", FuelKind.ELECTRIC, "SEK")

    @Test
    fun `tyhjästä ei ole mitään vietävää`() {
        assertFalse(canExport(days = emptyList(), cars = emptyList()))
    }

    @Test
    fun `työpäivä tai kirjaus sallii viennin`() {
        assertTrue(canExport(days = listOf(WorkHistoryDay(20_000, listOf(segment), emptyList())), cars = emptyList()))
        assertTrue(canExport(days = listOf(WorkHistoryDay(20_000, emptyList(), listOf(fillup))), cars = emptyList()))
    }

    @Test
    fun `pelkät tallennetut reitit sallivat viennin`() {
        // Pohjat kulkevat vain koko varmuuskopiossa; ilman kirjauksia niitä ei muuten saisi talteen.
        assertTrue(canExport(days = emptyList(), cars = emptyList(), templates = 1))
        assertFalse(canExport(days = emptyList(), cars = emptyList(), templates = 0))
    }

    @Test
    fun `pelkkä autoprofiili sallii viennin`() {
        // Esittelyn tai Autot-näkymän auto käyttövoimineen on varmuuskopioitava jo ennen
        // ensimmäistä kirjausta.
        assertTrue(canExport(days = emptyList(), cars = listOf(car)))
    }
}
