package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.FuelType
import fi.tyomatka.core.poi.ReportField
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportEditsTest {
    private val shopHours = HoursForm(DayHours.Range("06:00", "18:00"), DayHours.Closed, DayHours.Closed)
    private val saved = ReportForm(
        fuels = setOf(FuelType.DIESEL),
        hours = mapOf(ReportField.HOURS_SHOP to shopHours),
        maxHeight = "3,8",
    )

    @Test
    fun `koskematon osio seuraa tallennettua eikä näytä muutokselta`() {
        val later = saved.copy(fuels = setOf(FuelType.DIESEL, FuelType.HVO), maxHeight = "4")

        assertEquals(later, ReportEdits().over(later))
        listOf(ReportField.FUELS, ReportField.HOURS_SHOP, ReportField.MAX_HEIGHT, ReportField.MAX_WEIGHT).forEach {
            assertEquals(EditState.NONE, ReportEdits().state(it, later), "$it")
        }
    }

    @Test
    fun `muokattu osio pysyy muokattuna, vaikka tallennettu muuttuu`() {
        val edits = ReportEdits(fuels = setOf(FuelType.HVO), maxWeight = "12")
        val later = saved.copy(fuels = setOf(FuelType.DIESEL, FuelType.PETROL95))

        val form = edits.over(later)

        assertEquals(setOf(FuelType.HVO), form.fuels)
        assertEquals("12", form.maxWeight)
        assertEquals("3,8", form.maxHeight)
        assertEquals(EditState.UNSENT, edits.state(ReportField.FUELS, later))
        assertEquals(EditState.UNSENT, edits.state(ReportField.MAX_WEIGHT, later))
    }

    @Test
    fun `muokattu aukiolo voittaa tallennetun, muut lajit seuraavat tallennettua`() {
        val changed = HoursForm(DayHours.AllDay, DayHours.Closed, DayHours.Closed)
        val lunch = HoursForm(DayHours.Range("11:00", "14:00"), DayHours.Closed, DayHours.Closed)
        val later = saved.copy(hours = saved.hours + (ReportField.HOURS_LUNCH to lunch))

        val form = ReportEdits(hours = mapOf(ReportField.HOURS_SHOP to changed)).over(later)

        assertEquals(changed, form.hours[ReportField.HOURS_SHOP])
        assertEquals(lunch, form.hours[ReportField.HOURS_LUNCH])
    }

    @Test
    fun `ennalleen palautettu muokkaus ei ole muutos`() {
        assertEquals(EditState.NONE, ReportEdits(fuels = setOf(FuelType.DIESEL)).state(ReportField.FUELS, saved))
        assertEquals(EditState.NONE, ReportEdits(hours = mapOf(ReportField.HOURS_SHOP to shopHours)).state(ReportField.HOURS_SHOP, saved))
    }

    @Test
    fun `tyhjä valikoima tuntemattoman tilalla on ilmoitus, ei polttoaineita`() {
        assertEquals(EditState.UNSENT, ReportEdits(fuels = emptySet()).state(ReportField.FUELS, ReportForm()))
    }

    @Test
    fun `saman rajan toinen kirjoitusasu ei ole muutos`() {
        listOf("3,80", "3.8", " 3,8 ").forEach {
            assertEquals(EditState.NONE, ReportEdits(maxHeight = it).state(ReportField.MAX_HEIGHT, saved), it)
        }
        assertEquals(EditState.UNSENT, ReportEdits(maxHeight = "3,85").state(ReportField.MAX_HEIGHT, saved))
    }

    @Test
    fun `kelvoton tai tyhjennetty raja kerrotaan, tyhjä tyhjän päällä ei`() {
        assertEquals(EditState.INVALID, ReportEdits(maxHeight = "3,855").state(ReportField.MAX_HEIGHT, saved))
        assertEquals(EditState.INVALID, ReportEdits(maxHeight = "").state(ReportField.MAX_HEIGHT, saved))
        assertEquals(EditState.INVALID, ReportEdits(maxWeight = "abc").state(ReportField.MAX_WEIGHT, saved))
        assertEquals(EditState.NONE, ReportEdits(maxWeight = "").state(ReportField.MAX_WEIGHT, saved))
    }

    @Test
    fun `kesken oleva aukiolo kerrotaan, tyhjäksi palautettu ei`() {
        val typed = HoursForm.EMPTY.copy(weekdays = DayHours.Range("6", ""))

        assertEquals(EditState.INVALID, ReportEdits(hours = mapOf(ReportField.HOURS_LUNCH to typed)).state(ReportField.HOURS_LUNCH, saved))
        assertEquals(EditState.NONE, ReportEdits(hours = mapOf(ReportField.HOURS_LUNCH to HoursForm.EMPTY)).state(ReportField.HOURS_LUNCH, saved))
    }

    @Test
    fun `kokonaan suljettu aukiolo ei kelpaa lähetettäväksi`() {
        val closed = HoursForm(DayHours.Closed, DayHours.Closed, DayHours.Closed)

        assertEquals(EditState.INVALID, ReportEdits(hours = mapOf(ReportField.HOURS_SHOP to closed)).state(ReportField.HOURS_SHOP, saved))
    }

    @Test
    fun `aukiolon eri kirjoitusasu samasta ajasta ei ole muutos`() {
        val typed = HoursForm(DayHours.Range("6", "18"), DayHours.Closed, DayHours.Closed)
        assertEquals(EditState.NONE, ReportEdits(hours = mapOf(ReportField.HOURS_SHOP to typed)).state(ReportField.HOURS_SHOP, saved))

        val allDay = saved.copy(hours = mapOf(ReportField.HOURS_SHOP to HoursForm.from("Mo-Su 00:00-24:00")!!))
        assertEquals(EditState.NONE, ReportEdits(hours = allDay.hours).state(ReportField.HOURS_SHOP, allDay))
    }

    @Test
    fun `muutos aukioloon on lähettämätön`() {
        val changed = HoursForm(DayHours.Range("06:00", "20:00"), DayHours.Closed, DayHours.Closed)

        assertEquals(EditState.UNSENT, ReportEdits(hours = mapOf(ReportField.HOURS_SHOP to changed)).state(ReportField.HOURS_SHOP, saved))
    }

    @Test
    fun `perille tullut sama arvo päättää muokkauksen, ja kenttä seuraa taas tallennettua`() {
        val hours = HoursForm(DayHours.AllDay, DayHours.Closed, DayHours.Closed)
        val edits = ReportEdits(fuels = setOf(FuelType.HVO), hours = mapOf(ReportField.HOURS_SHOP to hours), maxHeight = "4,2", maxWeight = "12")
        val arrived = saved.copy(fuels = setOf(FuelType.HVO), hours = mapOf(ReportField.HOURS_SHOP to hours), maxHeight = "4,2", maxWeight = "12")

        val settled = edits.settled(SavedReport(saved), SavedReport(arrived))

        assertEquals(ReportEdits(), settled)
        val newer = arrived.copy(fuels = setOf(FuelType.DIESEL))
        assertEquals(setOf(FuelType.DIESEL), settled.over(newer).fuels)
    }

    @Test
    fun `odottava rivi ei päätä muokkausta, jotta hylätty lähetys ei hävitä kirjoitettua`() {
        val edits = ReportEdits(maxHeight = "4,2", fuels = setOf(FuelType.HVO))
        val queued = saved.copy(maxHeight = "4,2", fuels = setOf(FuelType.HVO))
        val waiting = setOf(ReportField.MAX_HEIGHT, ReportField.FUELS)

        val kept = edits.settled(SavedReport(saved), SavedReport(queued, waiting))

        assertEquals(edits, kept)
        assertEquals(EditState.NONE, kept.state(ReportField.MAX_HEIGHT, queued), "odottaessa ei ole lähettämätöntä muutosta")
        assertEquals(EditState.UNSENT, kept.state(ReportField.MAX_HEIGHT, saved), "hylätty palaa lähettämättömäksi")
        assertEquals("4,2", kept.over(saved).maxHeight)
        assertEquals(ReportEdits(), kept.settled(SavedReport(queued, waiting), SavedReport(queued)), "kuitattu päättää")
        assertEquals(kept, kept.settled(SavedReport(queued, waiting), SavedReport(saved)), "hylätty jatkuu lähettämättömänä")
    }

    @Test
    fun `odottava aukiolo säilyy, muu kenttä päättyy`() {
        val hours = HoursForm(DayHours.AllDay, DayHours.Closed, DayHours.Closed)
        val edits = ReportEdits(hours = mapOf(ReportField.HOURS_SHOP to hours), maxWeight = "12")
        val queued = saved.copy(hours = mapOf(ReportField.HOURS_SHOP to hours), maxWeight = "12")

        assertEquals(ReportEdits(hours = edits.hours), edits.settled(SavedReport(saved), SavedReport(queued, setOf(ReportField.HOURS_SHOP))))
        assertEquals(ReportEdits(maxWeight = "12"), edits.settled(SavedReport(saved), SavedReport(queued, setOf(ReportField.MAX_WEIGHT))))
    }

    @Test
    fun `eri tallennettu arvo ei päätä muokkausta eikä kesken olevaa kirjoitusta`() {
        val typed = HoursForm.EMPTY.copy(weekdays = DayHours.Range("6", ""))
        val edits = ReportEdits(fuels = setOf(FuelType.HVO), hours = mapOf(ReportField.HOURS_LUNCH to typed), maxHeight = "4,")

        assertEquals(edits, edits.settled(SavedReport(saved.copy(maxHeight = "9")), SavedReport(saved)))
    }

    @Test
    fun `toisen kentän kuittaus ei päätä kesken olevaa kirjoitusta`() {
        // "6" on sama aika kuin tallennettu 06:00, mutta kirjoittaja voi olla matkalla arvoon 6.30.
        val typed = HoursForm(DayHours.Range("6", "18:00"), DayHours.Closed, DayHours.Closed)
        val edits = ReportEdits(hours = mapOf(ReportField.HOURS_SHOP to typed), maxHeight = "3,80")
        assertEquals(EditState.NONE, edits.state(ReportField.HOURS_SHOP, saved))

        val other = setOf(ReportField.TOILETS)

        assertEquals(edits, edits.settled(SavedReport(saved, other), SavedReport(saved)), "toisen kentän kuittaus")
        assertEquals(edits, edits.settled(SavedReport(saved), SavedReport(saved, other)), "toinen kenttä jonoon")
        assertEquals(edits, edits.settled(SavedReport(saved), SavedReport(saved.copy(fuels = setOf(FuelType.HVO)))), "toisen kentän arvo")
    }

    @Test
    fun `oman kentän muutos päättää saman arvoisen kirjoituksen`() {
        val typed = HoursForm(DayHours.Range("6", "18:00"), DayHours.Closed, DayHours.Closed)
        val edits = ReportEdits(hours = mapOf(ReportField.HOURS_SHOP to typed))
        val before = saved.copy(hours = mapOf(ReportField.HOURS_SHOP to HoursForm(DayHours.AllDay, DayHours.Closed, DayHours.Closed)))

        assertEquals(ReportEdits(), edits.settled(SavedReport(before), SavedReport(saved)), "arvo muuttui")
        assertEquals(ReportEdits(), edits.settled(SavedReport(saved, setOf(ReportField.HOURS_SHOP)), SavedReport(saved)), "kuittaus tuli")
    }

    @Test
    fun `rajan ja valikoiman oma muutos tunnistetaan kentittäin`() {
        val edits = ReportEdits(fuels = setOf(FuelType.DIESEL), maxHeight = "3,8", maxWeight = "12")
        val later = saved.copy(maxWeight = "12")

        assertEquals(ReportEdits(fuels = setOf(FuelType.DIESEL), maxHeight = "3,8"), edits.settled(SavedReport(saved), SavedReport(later)))
        assertEquals(ReportEdits(maxHeight = "3,8", maxWeight = "12"), edits.settled(SavedReport(saved.copy(fuels = null)), SavedReport(saved)))
        assertEquals(ReportEdits(fuels = setOf(FuelType.DIESEL), maxWeight = "12"), edits.settled(SavedReport(saved.copy(maxHeight = "9")), SavedReport(saved)))
    }
}
