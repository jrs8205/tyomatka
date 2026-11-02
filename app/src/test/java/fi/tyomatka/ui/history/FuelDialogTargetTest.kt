package fi.tyomatka.ui.history

import androidx.compose.runtime.saveable.SaverScope
import fi.tyomatka.data.db.FuelFillupEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FuelDialogTargetTest {
    private fun save(target: FuelDialogTarget?) = with(FuelDialogTargetSaver) { SaverScope { true }.save(target) }

    private fun restore(target: FuelDialogTarget) = FuelDialogTargetSaver.restore(save(target)!!)

    private fun fillup(id: Long) = FuelFillupEntity(
        id = id, epochSec = 1_000, licensePlate = "ABC-123", odometerKm = 1000, liters = 40.0, totalCost = 80.0, pricePerLiter = 2.0,
        currency = "EUR",
    )

    @Test
    fun `suljettu, uusi ja muokattava kirjaus palautuvat prosessin yli tunnisteineen`() {
        assertNull(save(null))
        val new = FuelDialogTarget.New()
        val edit = FuelDialogTarget.Edit(42)
        assertEquals(new, restore(new))
        assertEquals(edit, restore(edit))
        assertEquals(new.token, restore(new)!!.token)
        assertEquals(42, (restore(edit) as FuelDialogTarget.Edit).id)
        // Toisen version tai rikkinäinen tallenne ei kaada: lomake on silloin kiinni.
        assertNull(FuelDialogTargetSaver.restore(42L))
        assertNull(FuelDialogTargetSaver.restore(listOf("rikki")))
        assertNull(FuelDialogTargetSaver.restore(listOf(42L)))
    }

    @Test
    fun `palautettu avaus saa saman avaimen kuin ennen`() {
        // key(target) erottaa lomakkeiden tallenteet: palautetun avauksen avaimen on oltava sama, jotta se löytää oman
        // tallenteensa (syötteet, kuvat ja kameran käynnistimen avaimen).
        val new = FuelDialogTarget.New()
        val edit = FuelDialogTarget.Edit(42)
        assertEquals(new.hashCode(), restore(new).hashCode())
        assertEquals(edit.hashCode(), restore(edit).hashCode())
    }

    @Test
    fun `saman kirjauksen kaksi avausta saavat eri avaimen`() {
        // Latausodotuksessa peruttu palautus jättää lomakkeen tallenteen kuluttamatta; seuraava avaus ei saa osua
        // samaan avaimeen ja avata lomaketta hylätyin tiedoin.
        assertNotEquals(FuelDialogTarget.Edit(42), FuelDialogTarget.Edit(42))
        assertNotEquals(FuelDialogTarget.Edit(42).hashCode(), FuelDialogTarget.Edit(42).hashCode())
        assertNotEquals(FuelDialogTarget.New(), FuelDialogTarget.New())
        assertNotEquals(FuelDialogTarget.New().hashCode(), FuelDialogTarget.New().hashCode())
    }

    @Test
    fun `uusi lomake avautuu heti ja muokattava vasta kun kirjaus on latautunut`() {
        val entry = fillup(42)
        assertEquals(FuelDialogStep.Open(null), fuelDialogStep(FuelDialogTarget.New(), emptyList(), loaded = false))
        assertEquals(FuelDialogStep.Wait, fuelDialogStep(FuelDialogTarget.Edit(42), emptyList(), loaded = false))
        assertEquals(FuelDialogStep.Open(entry), fuelDialogStep(FuelDialogTarget.Edit(42), listOf(fillup(7), entry), loaded = true))
    }

    @Test
    fun `menneen päivän tankkaus kantaa päivän lomakkeelle ja prosessin yli`() {
        // eilen tekemättä jäänyt tankkaus pitää voida kirjata jälkikäteen päiväkortista.
        val past = FuelDialogTarget.New(epochDay = 20_380)
        assertEquals(FuelDialogStep.Open(null, epochDay = 20_380), fuelDialogStep(past, emptyList(), loaded = false))
        assertEquals(FuelDialogStep.Open(null, epochDay = null), fuelDialogStep(FuelDialogTarget.New(), emptyList(), loaded = false))
        assertEquals(past, restore(past))
        assertEquals(20_380L, (restore(past) as FuelDialogTarget.New).epochDay)
        assertNull((restore(FuelDialogTarget.New()) as FuelDialogTarget.New).epochDay)
    }

    @Test
    fun `latauksen jälkeen puuttuva muokattava kirjaus nollaa kohteen`() {
        assertEquals(FuelDialogStep.Reset, fuelDialogStep(FuelDialogTarget.Edit(42), listOf(fillup(7)), loaded = true))
        assertEquals(FuelDialogStep.Reset, fuelDialogStep(FuelDialogTarget.Edit(42), emptyList(), loaded = true))
        // Ennen latausta puuttuminen ei ole lopullista.
        assertEquals(FuelDialogStep.Wait, fuelDialogStep(FuelDialogTarget.Edit(42), listOf(fillup(7)), loaded = false))
    }
}
