package fi.tyomatka.ui.history.forms

import androidx.compose.runtime.saveable.SaverScope
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.repo.fuelIntervals
import fi.tyomatka.ui.history.FuelField
import fi.tyomatka.ui.history.parseDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelFormTest {
    private val zone: ZoneId = ZoneId.of("Europe/Helsinki")
    private val today: Long = LocalDate.of(2026, 10, 15).toEpochDay()
    private val now: Long = LocalDate.of(2026, 10, 15).atTime(18, 0).atZone(zone).toEpochSecond()

    private fun fillup(
        id: Long,
        daysAgo: Long,
        km: Long,
        liters: Double = 40.0,
        total: Double = 80.0,
        kind: FuelKind = FuelKind.DIESEL,
        full: Boolean = true,
        currency: String = "EUR",
        plate: String = "ABC-123",
    ) = FuelFillupEntity(
        id = id,
        epochSec = LocalDate.ofEpochDay(today - daysAgo).atTime(12, 0).atZone(zone).toEpochSecond(),
        licensePlate = plate,
        odometerKm = km,
        liters = liters,
        totalCost = total,
        pricePerLiter = total / liters,
        backupId = "b$id",
        kind = kind,
        fullTank = full,
        currency = currency,
    )

    private fun form(
        fillups: List<FuelFillupEntity> = emptyList(),
        existing: FuelFillupEntity? = null,
        cars: Map<String, CarEntity> = emptyMap(),
    ) = FuelForm.initial(FuelFormContext(fillups, existing, now, zone, cars), defaultPlate = "ABC-123", todayEpochDay = today)

    private fun car(plate: String, fuel: FuelKind?, currency: String?) = mapOf(plate to CarEntity(plate, fuel, currency))

    @Test
    fun `uusi kirjaus menneelle päivälle alkaa siitä päivästä keskipäivällä`() {
        val context = FuelFormContext(emptyList(), null, now, zone, emptyMap())
        val f = FuelForm.initial(context, defaultPlate = "ABC-123", todayEpochDay = today, initialEpochDay = today - 1)
        assertEquals(today - 1, f.epochDay)
        assertEquals(LocalDate.ofEpochDay(today - 1).atTime(12, 0).atZone(zone).toEpochSecond(), f.epochSec)
        // Ilman päivää uusi kirjaus on tänään nykyhetkellä kuten ennen.
        assertEquals(today, FuelForm.initial(context, "ABC-123", today).epochDay)
    }

    @Test
    fun `litrat ja hinta per litra täydentävät summan`() {
        val f = form().withLiters("40").withPerLiter("2")
        assertEquals(80.0, parseDecimal(f.prices.total))
    }

    @Test
    fun `summa ja litrat täydentävät hinnan per litra`() {
        val f = form().withLiters("40").withTotal("100")
        assertEquals(2.5, parseDecimal(f.prices.perLiter))
    }

    @Test
    fun `lukema alle saman auton edellisen tankkauksen estää tallennuksen`() {
        val f = form(listOf(fillup(1, daysAgo = 3, km = 1000))).withKm("900").withLiters("30")
        assertTrue(f.tooSmall)
        assertEquals(1000L, f.previousKm)
        assertFalse(f.canSave)
    }

    @Test
    fun `esikatselu laskee kulutuksen ja hinnan edellisestä tankkauksesta`() {
        val f = form(listOf(fillup(1, daysAgo = 3, km = 1000))).withKm("1500").withLiters("50").withTotal("100")
        val preview = assertNotNull(f.preview)
        assertEquals(10.0, preview.consumption, 0.001)
        assertEquals(0.2, assertNotNull(preview.costPerKm), 0.001)
    }

    @Test
    fun `esikatselua ei ole ilman edellistä tankkausta`() {
        assertNull(form().withKm("1500").withLiters("50").preview)
    }

    @Test
    fun `tyhjä rekkari estää tallennuksen`() {
        val f = form().withPlate("").withKm("1500").withLiters("50")
        assertFalse(f.canSave)
        assertNull(f.entry())
    }

    @Test
    fun `muokkaus säilyttää rivin tunnisteen ja rajoihin käytetyn aikaleiman kun päivä ei vaihdu`() {
        val existing = fillup(7, daysAgo = 0, km = 2000)
        val f = form(listOf(existing), existing).withLiters("45")
        val entry = assertNotNull(f.entry())
        assertEquals(7L, entry.id)
        assertEquals(today, entry.epochDay)
        assertEquals(existing.epochSec, f.epochSec)
    }

    @Test
    fun `muokattu kirjaus tallentuu vain kun jokin syöte on muuttunut`() {
        val existing = fillup(7, daysAgo = 0, km = 2000)
        val original = form(listOf(existing), existing)
        assertTrue(original.isUnchanged(original))
        // Tausta (nykyhetki, autot) saa päivittyä lomakkeen ollessa auki ilman, että syötteet lasketaan muuttuneiksi.
        val refreshed = FuelForm.initial(
            FuelFormContext(listOf(existing), existing, now + 60, zone, emptyMap()), defaultPlate = "ABC-123", todayEpochDay = today,
        )
        assertTrue(refreshed.isUnchanged(original))
        assertFalse(original.withKm("2001").isUnchanged(original))
        assertFalse(original.withNote("Kuitti").isUnchanged(original))
        assertFalse(original.withFullTank(false).isUnchanged(original))
        // Takaisin alkuarvoon kirjoitettu kenttä ei ole muutos.
        assertTrue(original.withKm("2001").withKm("2000").isUnchanged(original))
    }

    @Test
    fun `tuntematon rekkari kelpaa kentästä`() {
        val f = form().withPlate("xyz-987").withKm("10").withLiters("5")
        assertEquals("XYZ-987", f.normalizedPlate)
        assertTrue(f.canSave)
    }

    @Test
    fun `edellinen täysi tankkaus tunnistetaan jo ennen lukeman syöttöä`() {
        assertTrue(form(listOf(fillup(1, daysAgo = 3, km = 1000))).hasPrevious)
        assertFalse(form().hasPrevious)
        // Pelkistä vajaista ei voi laskea kulutusta, joten vihje litroista ja lukemasta olisi harhaanjohtava.
        assertFalse(form(listOf(fillup(1, daysAgo = 3, km = 1000, full = false))).hasPrevious)
        // Toisen lajin täysi ei kelpaa.
        assertFalse(form(listOf(fillup(1, daysAgo = 3, km = 1000, kind = FuelKind.ADBLUE))).hasPrevious)
    }

    @Test
    fun `vajaan muuttaminen täydeksi laskee välin edellisestä täydestä`() {
        val start = fillup(1, daysAgo = 6, km = 1000, liters = 50.0)
        val partial = fillup(2, daysAgo = 3, km = 1500, liters = 40.0, total = 80.0, full = false)

        val preview = assertNotNull(form(listOf(start, partial), partial).withFullTank(true).preview)

        assertEquals(40.0 / 500 * 100, preview.consumption, 1e-9)
        assertEquals(0, preview.partialCount)
    }

    @Test
    fun `myöhemmin latautuneet tankkaukset päivittävät rajat syötteitä nollaamatta`() {
        val f = form().withKm("900").withLiters("30")
        assertFalse(f.tooSmall)

        val updated = f.withFillups(listOf(fillup(1, daysAgo = 3, km = 1000)))

        assertTrue(updated.tooSmall)
        assertEquals("900", updated.km)
        assertEquals("30", updated.prices.liters)
    }

    @Test
    fun `lajin vaihto vaihtaa lukemarajat ja esikatselun lajin`() {
        val diesel = fillup(1, daysAgo = 3, km = 1000)
        val adBlue = fillup(2, daysAgo = 10, km = 500, liters = 10.0, total = 9.0, kind = FuelKind.ADBLUE)

        val f = form(listOf(diesel, adBlue)).withKind(FuelKind.ADBLUE).withKm("900").withLiters("4")

        assertEquals(500L, f.previousKm)
        assertFalse(f.tooSmall) // 900 alittaa dieselin 1000, mutta AdBluen raja on 500
        val preview = assertNotNull(f.preview)
        assertEquals(FuelKind.ADBLUE, preview.kind)
        assertEquals(4.0 / 400 * 1000, preview.consumption, 1e-9)
    }

    @Test
    fun `vajaalla ei ole esikatselua ja täysi laskee välissä olevat vajaat mukaan`() {
        val start = fillup(1, daysAgo = 6, km = 1000, liters = 50.0)
        val partial = fillup(2, daysAgo = 3, km = 1200, liters = 20.0, total = 40.0, full = false)

        val partialForm = form(listOf(start)).withKm("1200").withLiters("20").withFullTank(false)
        assertTrue(partialForm.isPartial)
        assertNull(partialForm.preview)

        val full = form(listOf(start, partial)).withKm("1500").withLiters("30").withTotal("60")
        val preview = assertNotNull(full.preview)
        assertEquals(1, preview.partialCount)
        assertEquals(50.0 / 500 * 100, preview.consumption, 1e-9)
        assertEquals(100.0 / 500, assertNotNull(preview.costPerKm), 1e-9)
    }

    @Test
    fun `uusi tankkaus on aina euroissa, vaikka autolle on tallennettu muu valuutta`() {
        val f = form(cars = car("ABC-123", null, "SEK"))
        assertEquals("EUR", f.currency)
        assertEquals("EUR", f.withPlate("abc-123").currency)
        // Myöhemmin latautuneet autot eivät muuta valuuttaa.
        assertEquals("EUR", f.withFillups(emptyList(), car("ABC-123", null, "DKK")).currency)
        assertEquals("EUR", assertNotNull(f.withKm("1000").withLiters("10").entry()).currency)
    }

    @Test
    fun `muokattava tankkaus säilyttää lajin, täyden ja oman valuutan`() {
        val existing = fillup(7, daysAgo = 2, km = 2000, kind = FuelKind.ADBLUE, full = false, currency = "SEK")
        val edited = form(listOf(existing), existing, cars = car("XYZ-1", FuelKind.PETROL, "DKK"))
            .withLiters("5")
            .withPlate("XYZ-1")
        val entry = assertNotNull(edited.entry())

        assertEquals(FuelKind.ADBLUE, entry.kind)
        assertFalse(entry.fullTank)
        assertEquals("SEK", entry.currency)
    }

    @Test
    fun `esikatselu on sama kuin tallennetun tankkauksen väli tasatilanteessa`() {
        // Menneelle päivälle tallennus on keskipäivä: uusi täysi ja eilinen vajaa samalla hetkellä ja lukemalla.
        val start = fillup(1, daysAgo = 3, km = 1000, liters = 50.0)
        val partial = fillup(2, daysAgo = 1, km = 1500, liters = 20.0, full = false)
        val f = form(listOf(start, partial)).withDay(today - 1).withKm("1500").withLiters("30").withTotal("60")
        val saved = FuelFillupEntity(
            id = 3, epochSec = f.epochSec, licensePlate = "ABC-123", odometerKm = 1500, liters = 30.0, totalCost = 60.0,
            pricePerLiter = 2.0, kind = FuelKind.DIESEL, fullTank = true, currency = "EUR",
        )

        val card = assertNotNull(fuelIntervals(listOf(start, partial, saved)).firstOrNull { it.fillupId == 3L })

        assertEquals(10.0, card.consumption, 1e-9)
        assertEquals(card.consumption, assertNotNull(f.preview).consumption, 1e-9)
    }

    @Test
    fun `vajaa samalla lukemalla kuin täysi esikatselee saman kerran välin`() {
        // Vajaa kuuluu saman lukeman täyden kertaan eikä odota seuraavaa täyttä.
        val start = fillup(1, daysAgo = 10, km = 100_000, liters = 300.0)
        val full = fillup(2, daysAgo = 2, km = 100_800, liters = 200.0)
        val f = form(listOf(start, full)).withFullTank(false).withKm("100800").withLiters("5")

        val preview = assertNotNull(f.preview)

        assertEquals(205.0 / 800 * 100, preview.consumption, 1e-9)
        assertEquals(FuelFormEvent(merged = true, full = true), f.event)
        // Välissä oleva vajaa odottaa edelleen seuraavaa täyttä.
        assertNull(form(listOf(start, full)).withFullTank(false).withKm("101000").withLiters("5").preview)
    }

    @Test
    fun `saman lukeman täydet esikatselevat kerran välin kumpaa tahansa muokattaessa`() {
        val start = fillup(1, daysAgo = 10, km = 100_000, liters = 300.0)
        val tank1 = fillup(2, daysAgo = 2, km = 100_800, liters = 200.0)
        val tank2 = fillup(3, daysAgo = 2, km = 100_800, liters = 100.0)

        val firstForm = form(listOf(start, tank1, tank2), existing = tank1).withLiters("220")
        val first = assertNotNull(firstForm.preview)
        val second = assertNotNull(form(listOf(start, tank1, tank2), existing = tank2).preview)

        assertEquals(320.0 / 800 * 100, first.consumption, 1e-9)
        assertEquals(300.0 / 800 * 100, second.consumption, 1e-9)
        assertEquals(true, firstForm.event?.merged)
        assertEquals(false, form(listOf(start, tank1)).withKm("101600").withLiters("40").event?.merged)
    }

    @Test
    fun `ketjun aloittavan kerran vajaa ja toinen täysi saavat kulutuksen seuraavasta`() {
        // Ensimmäisen täyden kerran määrät ovat lähtötaso eivätkä seuraavan täyden väliä.
        val first = fillup(1, daysAgo = 2, km = 100_000, liters = 50.0)
        val partial = form(listOf(first)).withFullTank(false).withKm("100000").withLiters("5")
        val second = form(listOf(first)).withKm("100000").withLiters("20")

        assertNull(partial.preview)
        assertEquals(FuelFormEvent(merged = true, full = true), partial.event)
        assertNull(second.preview)
        assertEquals(FuelFormEvent(merged = true, full = true), second.event)
        // Välissä oleva vajaa on oma kertansa, ja ilman määrää kertaa ei ole.
        assertEquals(FuelFormEvent(merged = false, full = false), form(listOf(first)).withFullTank(false).withKm("100300").withLiters("5").event)
        assertNull(form(listOf(first)).withKm("100300").event)
    }

    @Test
    fun `takautuva täysi myöhemmän täyden lukemalla esikatselee kerran välin`() {
        val start = fillup(1, daysAgo = 10, km = 100_000, liters = 300.0)
        val later = fillup(2, daysAgo = 1, km = 100_800, liters = 100.0)
        val f = form(listOf(start, later)).withDay(today - 3).withKm("100800").withLiters("200")

        assertEquals(300.0 / 800 * 100, assertNotNull(f.preview).consumption, 1e-9)
    }

    @Test
    fun `kahden tankin auton toinen tankki esikatselee saman kuin kortti`() {
        // Tänään ja menneelle päivälle (molemmat keskipäivällä, uusi tallentuu viimeiseksi).
        val start = fillup(1, daysAgo = 10, km = 100_000, liters = 300.0)
        for (daysAgo in listOf(0L, 3L)) {
            val tank1 = fillup(2, daysAgo = daysAgo, km = 100_800, liters = 200.0)
            val f = form(listOf(start, tank1)).withDay(today - daysAgo).withKm("100800").withLiters("100").withTotal("200")
            val saved = FuelFillupEntity(
                id = 3, epochSec = f.epochSec, licensePlate = "ABC-123", odometerKm = 100_800, liters = 100.0,
                totalCost = 200.0, pricePerLiter = 2.0, kind = FuelKind.DIESEL, fullTank = true, currency = "EUR",
            )

            val card = fuelIntervals(listOf(start, tank1, saved)).single()

            assertEquals(37.5, card.consumption, 1e-9)
            assertEquals(card.consumption, assertNotNull(f.preview).consumption, 1e-9)
        }
    }

    @Test
    fun `lajin oletus on auton polttoaine`() {
        // Profiili voittaa, muuten auton viimeisin polttoainetankkaus (AdBlue ei ole polttoaine), muuten diesel.
        assertEquals(FuelKind.PETROL, form(cars = car("ABC-123", FuelKind.PETROL, null)).kind)
        val charged = fillup(1, daysAgo = 3, km = 1000, kind = FuelKind.ELECTRIC)
        val adBlue = fillup(2, daysAgo = 1, km = 1100, kind = FuelKind.ADBLUE)
        assertEquals(FuelKind.ELECTRIC, form(listOf(charged, adBlue)).kind)
        assertEquals(FuelKind.DIESEL, form().kind)
    }

    @Test
    fun `auton vaihto päivittää lajin, kunnes se valitaan itse`() {
        val f = form(cars = car("EL-1", FuelKind.ELECTRIC, null))
        assertEquals(FuelKind.DIESEL, f.kind)

        val switched = f.withPlate("el-1")
        assertEquals(FuelKind.ELECTRIC, switched.kind)

        val chosen = switched.withKind(FuelKind.ADBLUE).withPlate("ABC-123")
        assertEquals(FuelKind.ADBLUE, chosen.kind)
    }

    @Test
    fun `myöhemmin latautuneet tankkaukset päivittävät valitsemattoman lajin`() {
        val f = form().withKm("900")

        val loaded = f.withFillups(listOf(fillup(1, daysAgo = 3, km = 800, kind = FuelKind.GAS)))

        assertEquals(FuelKind.GAS, loaded.kind)
        assertEquals("900", loaded.km)
        // Myöhemmin latautunut auton polttoaine menee tankkausten edelle.
        assertEquals(FuelKind.PETROL, loaded.withFillups(loaded.context.fillups, car("ABC-123", FuelKind.PETROL, null)).kind)
    }

    @Test
    fun `syötteet palautuvat prosessin yli ja konteksti tulee tuoreena`() {
        // Kaikki syötteet ja valintaliput poikkeavat oletuksista: rekkari, päivä, lukema, määrä, hinnat ja ankkuri, laji, vajaa.
        val before = form().withPlate("xyz-789").withDay(today - 2).withKm("1500").withLiters("50").withTotal("100")
            .withKind(FuelKind.PETROL).withFullTank(false)
        assertEquals(FuelField.TOTAL, before.priceAnchor)

        val saved = assertNotNull(with(fuelFormSaver { before.context }) { SaverScope { true }.save(before) })
        assertEquals(before, fuelFormSaver { before.context }.restore(saved))

        // Palautuksessa kirjaukset ja autot ovat jo voineet muuttua: ne tulevat tuoreina, syötteet säilyvät.
        val earlier = fillup(1, daysAgo = 3, km = 1000, kind = FuelKind.PETROL, plate = "XYZ-789")
        val fresh = FuelFormContext(listOf(earlier), null, now + 60, zone, car("XYZ-789", FuelKind.GAS, null))
        val restored = assertNotNull(fuelFormSaver { fresh }.restore(saved))
        assertEquals(before.copy(context = fresh), restored)
        assertEquals(1000L, restored.previousKm)
        // Itse valittu laji ei seuraa autoa palautuksen jälkeenkään; valitsematon seuraisi.
        assertEquals(FuelKind.PETROL, restored.withFillups(fresh.fillups).kind)
        val unchosen = form().withPlate("xyz-789")
        val unchosenSaved = assertNotNull(with(fuelFormSaver { unchosen.context }) { SaverScope { true }.save(unchosen) })
        assertEquals(FuelKind.GAS, assertNotNull(fuelFormSaver { fresh }.restore(unchosenSaved)).withFillups(fresh.fillups).kind)
    }

    @Test
    fun `lisätiedot kulkevat kirjaukseen normalisoituna ja säilyvät tallenteessa`() {
        val f = form().withKm("1500").withLiters("40").withNote("  Latauspiste rikki \n")
        assertEquals("Latauspiste rikki", assertNotNull(f.entry()).note)
        assertEquals(500, form().withNote("x".repeat(600)).note.length)
        val saved = assertNotNull(with(fuelFormSaver { f.context }) { SaverScope { true }.save(f) })
        assertEquals(f, fuelFormSaver { f.context }.restore(saved))
    }

    @Test
    fun `muokattavan kirjauksen lisätiedot esitäyttyvät`() {
        val existing = fillup(5, daysAgo = 1, km = 2000, liters = 30.0, total = 60.0).copy(note = "Kuitti puuttuu")
        assertEquals("Kuitti puuttuu", form(listOf(existing), existing = existing).note)
        assertEquals("", form().note)
    }

    @Test
    fun `muokattavan kirjauksen syötteet palautuvat muokkaukseksi`() {
        val existing = fillup(5, daysAgo = 1, km = 2000, liters = 30.0, total = 60.0, currency = "SEK")
        val before = form(listOf(existing), existing = existing).withKm("2100")
        val saved = assertNotNull(with(fuelFormSaver { before.context }) { SaverScope { true }.save(before) })

        val restored = assertNotNull(fuelFormSaver { before.context }.restore(saved))

        assertEquals(before, restored)
        assertEquals(5L, assertNotNull(restored.entry()).id)
        assertEquals("SEK", assertNotNull(restored.entry()).currency)
    }

    @Test
    fun `kelvoton tallennettu tila ei palaudu vaan lomake alkaa alusta`() {
        assertNull(fuelFormSaver { form().context }.restore(listOf("vain yksi")))
        assertNull(fuelFormSaver { form().context }.restore(listOf<Any?>("ABC-123", "ei päivä", "", "", "", "", null, "DIESEL", true, "EUR", false, "")))
        // Edellisen version tallenne, jossa oli valuutan valintalippu.
        assertNull(fuelFormSaver { form().context }.restore(listOf<Any?>("ABC-123", today, "", "", "", "", null, "DIESEL", true, "SEK", false, true, "")))
        // Kelvolliset kentät, mutta ylimääräinen perässä: toisen version tallenne.
        assertNull(fuelFormSaver { form().context }.restore(listOf<Any?>("ABC-123", today, "", "", "", "", null, "DIESEL", true, "EUR", false, "", "lisä")))
    }

    @Test
    fun `kiireinen lomake pitää tallennusta edeltäneet kirjaukset, autot ja kuvat`() {
        val before = fillup(1, daysAgo = 3, km = 1000)
        val data = FuelFormData(listOf(before), emptyMap(), emptyList())
        val saved = fillup(2, daysAgo = 0, km = 1500)
        val photo = FuelPhotoEntity(id = 1, fillupId = 2, fileName = "a.jpg", addedAtEpochSec = 10)
        val cars = car("ABC-123", FuelKind.PETROL, null)

        // Tallennuksen aikana Room tuo juuri tallennetun rivin ja kuvan: lomake ei ota niitä käyttöön.
        data.refresh(busy = true, listOf(before, saved), cars, listOf(photo))
        assertEquals(listOf(before), data.fillups)
        assertTrue(data.cars.isEmpty())
        assertTrue(data.storedPhotos.isEmpty())

        // Epäonnistunut tallennus vapauttaa lomakkeen, ja tuoreet tiedot tulevat käyttöön.
        data.refresh(busy = false, listOf(before, saved), cars, listOf(photo))
        assertEquals(listOf(before, saved), data.fillups)
        assertEquals(cars, data.cars)
        assertEquals(listOf(photo), data.storedPhotos)
    }
}
