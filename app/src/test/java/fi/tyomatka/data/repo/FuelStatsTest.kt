package fi.tyomatka.data.repo

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelStatsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Helsinki")

    private fun day(d: Int): Long = LocalDate.of(2026, 9, d).atTime(12, 0).atZone(zone).toEpochSecond()

    private fun fillup(
        id: Long,
        plate: String,
        dayOfMonth: Int,
        km: Long,
        liters: Double,
        cost: Double = liters * 1.8,
        kind: FuelKind = FuelKind.DIESEL,
        full: Boolean = true,
        currency: String = "EUR",
    ) = FuelFillupEntity(
        id = id,
        epochSec = day(dayOfMonth),
        licensePlate = plate,
        odometerKm = km,
        liters = liters,
        totalCost = cost,
        pricePerLiter = if (liters > 0) cost / liters else 0.0,
        kind = kind,
        fullTank = full,
        currency = currency,
    )

    /** Kaikille lajeille samat jakson kilometrit (yhden polttoaineen jakso). */
    private fun allKm(km: Long): Map<FuelKind, Long> = FuelKind.entries.associateWith { km }

    private val abc1 = fillup(1, "ABC-123", 1, 100_000, 50.0)
    private val abc2 = fillup(2, "ABC-123", 5, 100_500, 40.0, cost = 72.0)
    private val abc3 = fillup(3, "ABC-123", 9, 101_500, 90.0, cost = 162.0)
    private val xyz1 = fillup(4, "XYZ-789", 2, 20_000, 30.0)
    private val xyz2 = fillup(5, "XYZ-789", 7, 20_400, 40.0, cost = 80.0)

    @Test
    fun `valit lasketaan saman auton perakkaisista tankkauksista`() {
        val intervals = fuelIntervals(listOf(abc3, xyz2, abc1, xyz1, abc2))
        val abc = intervals.filter { it.plate == "ABC-123" }
        assertEquals(listOf(2L, 3L), abc.map { it.fillupId })
        assertEquals(500, abc[0].km)
        assertEquals(8.0, abc[0].consumption, 1e-9)
        assertEquals(0.144, abc[0].costPerKm!!, 1e-9)
        assertEquals(1000, abc[1].km)
        assertEquals(9.0, abc[1].consumption, 1e-9)
        val xyz = intervals.filter { it.plate == "XYZ-789" }
        assertEquals(listOf(5L), xyz.map { it.fillupId })
        assertEquals(10.0, xyz[0].consumption, 1e-9)
    }

    @Test
    fun `laskematon vali ohitetaan`() {
        val wrong = fillup(6, "ABC-123", 3, 99_000, 10.0)
        val intervals = fuelIntervals(listOf(abc1, wrong, abc2))
        // 1→wrong ei nouse; wrong→2 nousee (99 000 → 100 500).
        assertEquals(listOf(2L), intervals.map { it.fillupId })
        assertEquals(1500, intervals[0].km)
    }

    @Test
    fun `nollahinta ei anna euroa per km`() {
        val free = fillup(7, "ABC-123", 6, 101_000, 40.0, cost = 0.0)
        val interval = fuelIntervals(listOf(abc2, free)).single()
        assertNull(interval.costPerKm)
    }

    @Test
    fun `autoyhteenveto painottaa kilometreilla ja jarjestaa viimeisimman mukaan`() {
        val summaries = carSummaries(listOf(abc1, abc2, abc3, xyz1, xyz2))
        assertEquals(listOf("ABC-123", "XYZ-789"), summaries.map { it.plate })
        val abc = summaries[0]
        assertEquals(3L, abc.latest?.fillupId)
        // (40 + 90) / (500 + 1000) * 100 = 8,667 — ei välien keskiarvo 8,5.
        assertEquals(130.0 / 1500 * 100, abc.avgConsumption!!, 1e-9)
        assertEquals((72.0 + 162.0) / 1500, abc.avgCostPerKm!!, 1e-9)
        assertEquals(1500, abc.totalKm)
        assertEquals(180.0, abc.totalLiters, 1e-9)
        assertEquals(3, abc.fillupCount)
    }

    @Test
    fun `yksi tankkaus ei anna kulutusta`() {
        val summary = carSummaries(listOf(abc1)).single()
        assertNull(summary.latest)
        assertNull(summary.avgConsumption)
        assertEquals(1, summary.fillupCount)
        assertEquals(50.0, summary.totalLiters, 1e-9)
    }

    @Test
    fun `jakson luvut paivalle ilman kulutusta`() {
        val fuel = periodFuel(listOf(abc2), periodKm = 120, includeConsumption = false, kind = FuelKind.DIESEL)
        assertNotNull(fuel)
        assertEquals(40.0, fuel.liters, 1e-9)
        assertEquals(72.0, fuel.costs.getValue("EUR"), 1e-9)
        assertEquals(1.8, fuel.avgPricePerLiter!!, 1e-9)
        assertNull(fuel.consumption)
        assertNull(fuel.costPerKm)
    }

    @Test
    fun `jakson luvut viikolle kulutuksineen`() {
        val fuel = periodFuel(listOf(abc2, abc3), periodKm = 1_300, includeConsumption = true, kind = FuelKind.DIESEL)
        assertNotNull(fuel)
        assertEquals(130.0 / 1300 * 100, fuel.consumption!!, 1e-9)
        assertEquals(234.0 / 1300, fuel.costPerKm!!, 1e-9)
    }

    @Test
    fun `jakson luvut ilman kilometreja tai hintaa`() {
        assertNull(periodFuel(emptyList(), 100, true, kind = FuelKind.DIESEL))
        val noKm = periodFuel(listOf(abc2), periodKm = 0, includeConsumption = true, kind = FuelKind.DIESEL)
        assertNull(noKm!!.consumption)
        val free = periodFuel(listOf(fillup(8, "ABC-123", 6, 101_000, 40.0, cost = 0.0)), 400, true, kind = FuelKind.DIESEL)
        assertNull(free!!.avgPricePerLiter)
        assertNull(free.costPerKm)
        assertEquals(10.0, free.consumption!!, 1e-9)
    }

    @Test
    fun `lukeman rajat uudelle tankkaukselle tulevat viimeisimmasta`() {
        val all = listOf(abc1, abc2, abc3, xyz2)
        val bounds = fillupKmBounds(all, "ABC-123", excludeId = null, atEpochSec = day(25), kind = FuelKind.DIESEL)
        assertEquals(101_500, bounds.previousKm)
        assertNull(bounds.nextKm)
        val none = fillupKmBounds(all, "QQQ-1", excludeId = null, atEpochSec = day(25), kind = FuelKind.DIESEL)
        assertNull(none.previousKm)
        assertNull(none.nextKm)
    }

    @Test
    fun `lukeman rajat muokattaessa ensimmaista kolmesta`() {
        val all = listOf(abc1, abc2, abc3, xyz2)
        val bounds = fillupKmBounds(all, "ABC-123", excludeId = 1, atEpochSec = abc1.epochSec, kind = FuelKind.DIESEL)
        assertNull(bounds.previousKm)
        assertEquals(100_500, bounds.nextKm)
        val middle = fillupKmBounds(all, "ABC-123", excludeId = 2, atEpochSec = abc2.epochSec, kind = FuelKind.DIESEL)
        assertEquals(100_000, middle.previousKm)
        assertEquals(101_500, middle.nextKm)
    }

    @Test
    fun `lukeman rajat takautuvalle tankkaukselle valiin`() {
        val all = listOf(abc1, abc2, abc3)
        // Unohtunut tankkaus 7.9. (2. ja 3. väliin): raja 2:sta ja 3:sta.
        val bounds = fillupKmBounds(all, "ABC-123", excludeId = null, atEpochSec = day(7), kind = FuelKind.DIESEL)
        assertEquals(100_500, bounds.previousKm)
        assertEquals(101_500, bounds.nextKm)
    }

    @Test
    fun `hinnaton tankkaus ei laske jakson keskihintaa eika euroa per km`() {
        val free = fillup(9, "ABC-123", 6, 101_000, 50.0, cost = 0.0)
        val priced = fillup(10, "ABC-123", 8, 101_500, 50.0, cost = 90.0)
        val fuel = periodFuel(listOf(free, priced), periodKm = 1_000, includeConsumption = true, kind = FuelKind.DIESEL)
        assertNotNull(fuel)
        assertEquals(100.0, fuel.liters, 1e-9)
        assertEquals(90.0, fuel.costs.getValue("EUR"), 1e-9)
        assertEquals(1.8, fuel.avgPricePerLiter!!, 1e-9)
        // €/km arvioidaan kaikille litroille hinnoiteltujen keskihinnalla.
        assertEquals(100.0 * 1.8 / 1_000, fuel.costPerKm!!, 1e-9)
    }

    @Test
    fun `hinnaton vali ei laske auton keskiarvoa euroa per km`() {
        val free = fillup(9, "ABC-123", 6, 101_000, 40.0, cost = 0.0)
        // abc2 → free (500 km, 0 €) ja free → abc3 (500 km, 162 €).
        val summary = carSummaries(listOf(abc2, free, abc3)).single()
        assertEquals(162.0 / 500, summary.avgCostPerKm!!, 1e-9)
        assertEquals(130.0 / 1000 * 100, summary.avgConsumption!!, 1e-9)
    }

    @Test
    fun `legacy duplicate comparison preserves same noon and liters with different readings`() {
        val dedupe = FuelDedupe(listOf(abc2))
        assertTrue(dedupe.isNew(abc2.copy(odometerKm = 100_900), hasBackupId = false, legacy = true))
        assertFalse(dedupe.isNew(abc2.copy(id = 99), hasBackupId = false, legacy = true))
    }

    @Test
    fun `stable id identifies edits without reserving rejected reading or time`() {
        val dedupe = FuelDedupe(listOf(abc2))
        val correction = abc2.copy(odometerKm = 100_900, epochSec = day(6))
        assertFalse(dedupe.isNew(correction, hasBackupId = true, legacy = false))
        val separate = correction.copy(backupId = "another-fillup")
        assertTrue(dedupe.isNew(separate, hasBackupId = true, legacy = false))
        assertFalse(dedupe.isNew(separate, hasBackupId = true, legacy = false))
    }

    @Test
    fun `equal times use odometer order for price only edits`() {
        val second = abc1.copy(id = 2, odometerKm = 100_500)
        val all = listOf(second, abc1)
        val firstBounds = fillupKmBounds(all, "ABC-123", abc1.id, abc1.epochSec, kind = FuelKind.DIESEL)
        assertNull(firstBounds.previousKm)
        assertEquals(100_500, firstBounds.nextKm)
        val secondBounds = fillupKmBounds(all, "ABC-123", second.id, second.epochSec, kind = FuelKind.DIESEL)
        assertEquals(100_000, secondBounds.previousKm)
        assertNull(secondBounds.nextKm)
        assertEquals(2L, fuelIntervals(all).single().fillupId)
    }

    @Test
    fun `retroactive same day fillup can be inserted before or between existing readings`() {
        val second = abc1.copy(id = 2, odometerKm = 100_500)
        val all = listOf(abc1, second)
        val middle = fillupKmBounds(all, "ABC-123", null, abc1.epochSec, 100_250, kind = FuelKind.DIESEL)
        assertEquals(100_000, middle.previousKm)
        assertEquals(100_500, middle.nextKm)
        val before = fillupKmBounds(all, "ABC-123", null, abc1.epochSec, 99_500, kind = FuelKind.DIESEL)
        assertNull(before.previousKm)
        assertEquals(100_000, before.nextKm)
    }

    @Test
    fun `tankkauksen oletusrekkari on avoimen segmentin oma tai muuten oletusauto`() {
        val open = WorkSegmentEntity(id = 1, routeDayId = 1, startEpochSec = 1, licensePlate = null)
        val charge = fillup(9, "EVX-1", 10, 12_000, 40.0, kind = FuelKind.ELECTRIC)
        assertNull(defaultFuelPlate(open, defaultPlate = "ABC-123", fillups = listOf(charge)))
        assertEquals("XYZ-789", defaultFuelPlate(open.copy(licensePlate = "XYZ-789"), defaultPlate = "ABC-123", fillups = listOf(charge)))
        assertEquals("ABC-123", defaultFuelPlate(null, defaultPlate = "ABC-123", fillups = listOf(charge)))
        // Viimeisin työjakso ilman autoa: Tänään-nappi ja lomake ottavat viimeisimmän kirjauksen auton.
        assertEquals("EVX-1", defaultFuelPlate(null, defaultPlate = null, fillups = listOf(abc1, charge)))
        assertNull(defaultFuelPlate(null, defaultPlate = null, fillups = emptyList()))
    }

    @Test
    fun `oletusauto on viimeisin työauto, ilman työjaksoja kirjauksen auto ja muuten ensimmäinen profiili`() {
        val segment = WorkSegmentEntity(id = 1, routeDayId = 1, startEpochSec = day(2), licensePlate = "ABC-123")
        val charge = fillup(9, "EVX-1", 10, 12_000, 40.0, kind = FuelKind.ELECTRIC)
        val car = CarEntity("TST-1", FuelKind.ELECTRIC, null)
        assertEquals("ABC-123", defaultCarPlate(listOf(segment), listOf(charge), listOf(car)))
        // Viimeisin työjakso ilman autoa: ei arvata kirjauksista.
        val noPlate = segment.copy(id = 2, startEpochSec = day(3), licensePlate = null)
        assertNull(defaultCarPlate(listOf(segment, noPlate), listOf(charge), listOf(car)))
        // Ilman työjaksoja viimeisimmän kirjauksen auto (pelkkiä latauksia kirjannut).
        assertEquals("EVX-1", defaultCarPlate(emptyList(), listOf(abc1, charge, abc2), listOf(car)))
        // Ilman työjaksoja ja kirjauksia ensimmäisenä lisätty profiili (esittelyn auto).
        assertEquals("TST-1", defaultCarPlate(emptyList(), emptyList(), listOf(car, CarEntity("GAS-1", FuelKind.GAS, null))))
        assertNull(defaultCarPlate(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `tankkaushetki tanaan on nyt ja menneelle paivalle keskipaiva`() {
        val now = LocalDate.of(2026, 9, 25).atTime(17, 40).atZone(zone).toEpochSecond()
        val today = LocalDate.of(2026, 9, 25).toEpochDay()
        assertEquals(now, fillupEpochSec(today, null, now, zone))
        assertEquals(day(20), fillupEpochSec(LocalDate.of(2026, 9, 20).toEpochDay(), null, now, zone))
    }

    @Test
    fun `tankkaushetki sailyy muokattaessa jos paiva ei vaihdu`() {
        val now = LocalDate.of(2026, 9, 25).atTime(17, 40).atZone(zone).toEpochSecond()
        val existing = LocalDate.of(2026, 9, 20).atTime(8, 15).atZone(zone).toEpochSecond()
        assertEquals(existing, fillupEpochSec(LocalDate.of(2026, 9, 20).toEpochDay(), existing, now, zone))
        assertEquals(day(21), fillupEpochSec(LocalDate.of(2026, 9, 21).toEpochDay(), existing, now, zone))
    }

    @Test
    fun `jakson autokohtainen kulutus lasketaan jaksoon paattyvista valeista`() {
        val all = listOf(abc1, abc2, abc3, xyz1, xyz2)
        val intervals = fuelIntervals(all).associateBy { it.fillupId }
        // Jakso: 5.–9.9. → tankkaukset abc2, xyz2, abc3 (välit 2, 5, 3).
        val inPeriod = listOf(abc2, xyz2, abc3)
        val cars = carPeriodFuel(inPeriod, intervals)
        assertEquals(listOf("ABC-123", "XYZ-789"), cars.map { it.plate })
        val abc = cars[0]
        assertEquals(130.0 / 1500 * 100, abc.consumption!!, 1e-9)
        assertEquals(234.0 / 1500, abc.costPerKm!!, 1e-9)
        assertEquals(2, abc.fillupCount)
        val xyz = cars[1]
        assertEquals(10.0, xyz.consumption!!, 1e-9)
        assertEquals(1, xyz.fillupCount)
    }

    @Test
    fun `jakson autokohtainen kulutus ilman valia on null`() {
        val cars = carPeriodFuel(listOf(abc1), fuelIntervals(listOf(abc1)).associateBy { it.fillupId })
        assertEquals(1, cars.size)
        assertNull(cars[0].consumption)
        assertEquals(1, cars[0].fillupCount)
    }

    @Test
    fun `kesken oleva tyopaiva ei piilota jakson kulutussuhdetta mutta puuttuva lukema piilottaa`() {
        val seg = fi.tyomatka.data.db.WorkSegmentEntity(
            id = 1, routeDayId = 1, startEpochSec = day(24), endEpochSec = day(24) + 3600,
            odometerStartKm = 100_000, odometerEndKm = 100_200,
        )
        val done = WorkHistoryDay(date = 1, segments = listOf(seg), fillups = emptyList())
        val open = WorkHistoryDay(date = 2, segments = listOf(seg.copy(id = 2, endEpochSec = null, odometerEndKm = null)), fillups = emptyList())
        val missing = WorkHistoryDay(date = 3, segments = listOf(seg.copy(id = 3, odometerEndKm = null)), fillups = emptyList())
        assertTrue(hasCompleteWorkReadings(listOf(done, open)))
        assertFalse(hasCompleteWorkReadings(listOf(done, missing)))
        assertFalse(hasCompleteWorkReadings(emptyList()))
    }

    @Test
    fun `vajaat summataan seuraavan täyden väliin`() {
        val start = fillup(11, "ABC-123", 1, 100_000, 60.0)
        val partial = fillup(12, "ABC-123", 3, 100_300, 20.0, cost = 36.0, full = false)
        val end = fillup(13, "ABC-123", 6, 100_800, 30.0, cost = 54.0)

        val interval = fuelIntervals(listOf(start, partial, end)).single()

        assertEquals(13L, interval.fillupId)
        assertEquals(800, interval.km)
        assertEquals(50.0, interval.liters, 1e-9)
        assertEquals(90.0, interval.cost!!, 1e-9)
        assertEquals(1, interval.partialCount)
        assertEquals(50.0 / 800 * 100, interval.consumption, 1e-9)
    }

    @Test
    fun `vajaa viimeisenä tai ilman aloittavaa täyttä ei tuota väliä`() {
        val partialFirst = fillup(14, "ABC-123", 1, 100_000, 20.0, full = false)
        val full = fillup(15, "ABC-123", 3, 100_400, 40.0)
        val partialLast = fillup(16, "ABC-123", 6, 100_700, 15.0, full = false)

        assertTrue(fuelIntervals(listOf(partialFirst, full, partialLast)).isEmpty())
    }

    @Test
    fun `diesel ja AdBlue lasketaan erikseen ja AdBlue litroina tuhannelle kilometrille`() {
        val d1 = fillup(21, "ABC-123", 1, 100_000, 60.0)
        val a1 = fillup(22, "ABC-123", 1, 100_000, 10.0, cost = 9.0, kind = FuelKind.ADBLUE)
        val d2 = fillup(23, "ABC-123", 5, 100_800, 64.0)
        val a2 = fillup(24, "ABC-123", 9, 102_000, 4.0, cost = 3.6, kind = FuelKind.ADBLUE)

        val intervals = fuelIntervals(listOf(d1, a1, d2, a2)).associateBy { it.fillupId }

        assertEquals(64.0 / 800 * 100, intervals.getValue(23).consumption, 1e-9)
        assertEquals(FuelKind.ADBLUE, intervals.getValue(24).kind)
        assertEquals(2000, intervals.getValue(24).km)
        assertEquals(4.0 / 2000 * 1000, intervals.getValue(24).consumption, 1e-9)
    }

    @Test
    fun `usean valuutan välillä ja jaksolla ei hintaa per km`() {
        val eur = fillup(31, "ABC-123", 1, 100_000, 50.0)
        val sekPartial = fillup(32, "ABC-123", 3, 100_400, 20.0, cost = 400.0, full = false, currency = "SEK")
        val eurEnd = fillup(33, "ABC-123", 6, 100_900, 40.0, cost = 72.0)

        val interval = fuelIntervals(listOf(eur, sekPartial, eurEnd)).single()
        assertNull(interval.cost)
        assertNull(interval.costPerKm)
        assertNull(interval.currency)

        val period = periodFuel(listOf(sekPartial, eurEnd), periodKm = 900, includeConsumption = true, kind = FuelKind.DIESEL)!!
        assertEquals(mapOf("SEK" to 400.0, "EUR" to 72.0), period.costs)
        assertNull(period.currency)
        assertNull(period.avgPricePerLiter)
        assertNull(period.costPerKm)
        assertEquals(60.0 / 900 * 100, period.consumption!!, 1e-9)
    }

    @Test
    fun `hinnaton tankkaus toisessa valuutassa tekee jaksosta usean valuutan jakson`() {
        // 50 l / 90 € ja 20 l SEK ilman hintaa: euron litrahintaa ei saa käyttää SEK-litroille.
        val eur = fillup(35, "ABC-123", 2, 100_000, 50.0, cost = 90.0)
        val sekWithoutPrice = fillup(36, "ABC-123", 4, 100_500, 20.0, cost = 0.0, currency = "SEK")

        val period = periodFuel(listOf(eur, sekWithoutPrice), periodKm = 1000, includeConsumption = true, kind = FuelKind.DIESEL)!!

        assertEquals(mapOf("EUR" to 90.0), period.costs)
        assertNull(period.currency)
        assertNull(period.avgPricePerLiter)
        assertNull(period.costPerKm)
        assertTrue(period.missingPrices)
        assertEquals(70.0 / 1000 * 100, period.consumption!!, 1e-9)
    }

    @Test
    fun `AdBluen osuus dieselistä jaksolla ja autoittain`() {
        val d = fillup(41, "ABC-123", 2, 100_000, 50.0)
        val a = fillup(42, "ABC-123", 2, 100_000, 2.0, cost = 1.8, kind = FuelKind.ADBLUE)

        val fuels = periodFuels(listOf(d, a), allKm(600), includeConsumption = true)
        assertEquals(50.0, fuels[FuelKind.DIESEL]!!.liters, 1e-9)
        assertEquals(2.0, fuels[FuelKind.ADBLUE]!!.liters, 1e-9)
        assertEquals(4.0, fuels.adBluePercent!!, 1e-9)
        assertEquals(2.0 / 600 * 1000, fuels[FuelKind.ADBLUE]!!.consumption!!, 1e-9)

        val cars = carPeriodFuel(listOf(d, a), emptyMap())
        assertEquals(listOf(FuelKind.DIESEL, FuelKind.ADBLUE), cars.map { it.kind })
        assertEquals(4.0, cars[1].percentOfDiesel!!, 1e-9)
        assertNull(cars[0].percentOfDiesel)
    }

    @Test
    fun `kaasu ja sähkö lasketaan lajin yksikössä sadalle kilometrille`() {
        val g1 = fillup(61, "GAS-1", 1, 50_000, 20.0, kind = FuelKind.GAS)
        val g2 = fillup(62, "GAS-1", 5, 50_400, 24.0, kind = FuelKind.GAS)
        val e1 = fillup(63, "EL-1", 1, 10_000, 40.0, cost = 8.0, kind = FuelKind.ELECTRIC)
        val e2 = fillup(64, "EL-1", 5, 10_250, 45.0, cost = 9.0, kind = FuelKind.ELECTRIC)

        val byId = fuelIntervals(listOf(g1, g2, e1, e2)).associateBy { it.fillupId }

        assertEquals(FuelKind.GAS, byId.getValue(62).kind)
        assertEquals(24.0 / 400 * 100, byId.getValue(62).consumption, 1e-9)
        assertEquals(FuelKind.ELECTRIC, byId.getValue(64).kind)
        assertEquals(45.0 / 250 * 100, byId.getValue(64).consumption, 1e-9)
        assertEquals(9.0 / 250, byId.getValue(64).costPerKm!!, 1e-9)
    }

    @Test
    fun `samalla lukemalla tehdyt tankkaukset ovat yksi tankkauskerta`() {
        // saman auton ja lajin peräkkäiset kuitit samalla lukemalla lasketaan yhteen.
        val start = fillup(171, "ABC-123", 1, 100_000, 50.0)
        val partial = fillup(172, "ABC-123", 4, 100_500, 20.0, cost = 36.0, full = false)
        val full = fillup(173, "ABC-123", 4, 100_500, 30.0, cost = 54.0)

        val interval = fuelIntervals(listOf(start, partial, full)).single()

        assertEquals(173L, interval.fillupId)
        assertEquals(listOf(172L, 173L), interval.eventIds)
        assertEquals(listOf(172L, 173L), fillEventOf(listOf(start, partial, full), 172).map { it.id })
        assertEquals(500, interval.km)
        assertEquals(50.0, interval.liters, 1e-9)
        assertEquals(90.0, interval.cost!!, 1e-9)
        assertEquals(0, interval.partialCount)
        assertEquals(50.0 / 500 * 100, interval.consumption, 1e-9)

        // Täysi ensin ja vajaa perään samalla lukemalla: sama kerta, väli kuuluu täydelle.
        val fullFirst = fillup(174, "ABC-123", 4, 100_500, 30.0, cost = 54.0)
        val topUp = fillup(175, "ABC-123", 4, 100_500, 20.0, cost = 36.0, full = false)
        val reversed = fuelIntervals(listOf(start, fullFirst, topUp)).single()
        assertEquals(174L, reversed.fillupId)
        assertEquals(50.0, reversed.liters, 1e-9)
    }

    @Test
    fun `kahden tankin auton täydet samalla lukemalla summataan ja seuraava väli alkaa siitä`() {
        val start = fillup(181, "ABC-123", 1, 100_000, 300.0)
        val tank1 = fillup(182, "ABC-123", 4, 100_800, 200.0)
        val tank2 = fillup(183, "ABC-123", 4, 100_800, 100.0)
        val next = fillup(184, "ABC-123", 8, 101_600, 280.0)

        val intervals = fuelIntervals(listOf(start, tank1, tank2, next))

        assertEquals(listOf(183L, 184L), intervals.map { it.fillupId })
        assertEquals(300.0, intervals[0].liters, 1e-9)
        assertEquals(280.0, intervals[1].liters, 1e-9)
        assertEquals(800, intervals[1].km)
    }

    @Test
    fun `saman lukeman kerta ketjun alussa ei siirrä määriä seuraavaan väliin`() {
        // Ensimmäinen kerta on lähtötaso: sen täysi ja vajaa eivät ole seuraavan välin kulutusta.
        val first = fillup(191, "ABC-123", 1, 100_000, 50.0)
        val topUp = fillup(192, "ABC-123", 1, 100_000, 10.0, full = false)
        val end = fillup(193, "ABC-123", 5, 100_500, 40.0)
        val interval = fuelIntervals(listOf(first, topUp, end)).single()
        assertEquals(193L, interval.fillupId)
        assertEquals(40.0, interval.liters, 1e-9)
        assertEquals(0, interval.partialCount)

        val partialFirst = fillup(194, "ABC-123", 1, 100_000, 10.0, full = false)
        val fullAfter = fillup(195, "ABC-123", 1, 100_000, 50.0)
        val other = fuelIntervals(listOf(partialFirst, fullAfter, end)).single()
        assertEquals(100_000, other.fromKm)
        assertEquals(40.0, other.liters, 1e-9)
    }

    @Test
    fun `laskeva lukema yhdistetyn kerran jälkeen aloittaa uuden ketjun`() {
        val start = fillup(201, "ABC-123", 1, 100_000, 50.0)
        val partial = fillup(202, "ABC-123", 4, 100_500, 20.0, full = false)
        val full = fillup(203, "ABC-123", 4, 100_500, 30.0)
        val wrongReading = fillup(204, "ABC-123", 6, 100_300, 25.0)
        val next = fillup(205, "ABC-123", 9, 100_800, 40.0)

        val intervals = fuelIntervals(listOf(start, partial, full, wrongReading, next))

        assertEquals(listOf(203L, 205L), intervals.map { it.fillupId })
        assertEquals(50.0, intervals[0].liters, 1e-9)
        assertEquals(100_300, intervals[1].fromKm)
        assertEquals(40.0, intervals[1].liters, 1e-9)
    }

    @Test
    fun `kaksi vajaata kuittia samalla lukemalla ovat yksi vajaa kerta`() {
        // saman pysähdyksen kuitit lasketaan yhdeksi tankkaukseksi myös vajaiden määrässä.
        val start = fillup(211, "ABC-123", 1, 100_000, 50.0)
        val receipt1 = fillup(212, "ABC-123", 3, 100_300, 10.0, full = false)
        val receipt2 = fillup(213, "ABC-123", 3, 100_300, 5.0, full = false)
        val end = fillup(214, "ABC-123", 6, 100_800, 30.0)

        val interval = fuelIntervals(listOf(start, receipt1, receipt2, end)).single()

        assertEquals(45.0, interval.liters, 1e-9)
        assertEquals(1, interval.partialCount)
        assertEquals(listOf(214L), interval.eventIds)
    }

    @Test
    fun `laskeva vajaa katkaisee ketjun`() {
        val start = fillup(71, "ABC-123", 1, 100_000, 60.0)
        val wrongReading = fillup(72, "ABC-123", 3, 99_900, 20.0, full = false)
        val end = fillup(73, "ABC-123", 6, 100_800, 30.0)

        assertTrue(fuelIntervals(listOf(start, wrongReading, end)).isEmpty())
    }

    @Test
    fun `hinnaton vajaa ketjun keskellä jättää välin hinnattomaksi`() {
        val start = fillup(111, "ABC-123", 1, 100_000, 50.0)
        val unpriced = fillup(112, "ABC-123", 3, 100_300, 20.0, cost = 0.0, full = false)
        val end = fillup(113, "ABC-123", 6, 100_800, 30.0)

        val interval = fuelIntervals(listOf(start, unpriced, end)).single()

        assertEquals(50.0, interval.liters, 1e-9)
        assertNull(interval.cost)
        assertNull(interval.costPerKm)
        assertEquals("EUR", interval.currency)
    }

    @Test
    fun `kruunuväli antaa hinnan kilometriltä ja keskihinnan kruunuina`() {
        val s1 = fillup(101, "ABC-123", 1, 100_000, 50.0, cost = 1000.0, currency = "SEK")
        val s2 = fillup(102, "ABC-123", 4, 100_500, 40.0, cost = 800.0, currency = "SEK")

        val interval = fuelIntervals(listOf(s1, s2)).single()
        assertEquals("SEK", interval.currency)
        assertEquals(800.0 / 500, interval.costPerKm!!, 1e-9)

        val period = periodFuel(listOf(s1, s2), periodKm = 500, includeConsumption = true, kind = FuelKind.DIESEL)!!
        assertEquals("SEK", period.currency)
        assertEquals(1800.0 / 90, period.avgPricePerLiter!!, 1e-9)
    }

    @Test
    fun `autoyhteenveto ja jakson autorivi eivät anna hintaa kilometriltä kahden valuutan väleistä`() {
        val d1 = fillup(81, "ABC-123", 1, 100_000, 50.0, cost = 90.0)
        val d2 = fillup(82, "ABC-123", 3, 100_500, 40.0, cost = 72.0)
        val d3 = fillup(83, "ABC-123", 6, 101_000, 45.0, cost = 500.0, currency = "SEK")
        val a1 = fillup(84, "ABC-123", 6, 101_000, 5.0, cost = 4.5, kind = FuelKind.ADBLUE)

        val summaries = carSummaries(listOf(d1, d2, d3, a1))
        val diesel = summaries.first { it.kind == FuelKind.DIESEL }
        assertEquals(mapOf("EUR" to 162.0, "SEK" to 500.0), diesel.costs)
        assertNull(diesel.currency)
        assertNull(diesel.avgCostPerKm)
        assertEquals(85.0 / 1000 * 100, diesel.avgConsumption!!, 1e-9)
        val adBlue = summaries.first { it.kind == FuelKind.ADBLUE }
        assertNull(adBlue.avgConsumption)
        assertEquals(1, adBlue.fillupCount)

        val intervals = fuelIntervals(listOf(d1, d2, d3)).associateBy { it.fillupId }
        val row = carPeriodFuel(listOf(d2, d3), intervals).single()
        assertEquals(85.0 / 1000 * 100, row.consumption!!, 1e-9)
        assertNull(row.costPerKm)
        assertNull(row.currency)
    }

    @Test
    fun `lajit ovat vakiojärjestyksessä ja AdBluen osuus lasketaan autoittain`() {
        val a = fillup(122, "ABC-123", 2, 100_000, 2.0, cost = 1.8, kind = FuelKind.ADBLUE)
        val d = fillup(121, "ABC-123", 2, 100_000, 50.0)
        val a2 = fillup(124, "XYZ-789", 2, 20_000, 1.0, cost = 0.9, kind = FuelKind.ADBLUE)
        val d2 = fillup(123, "XYZ-789", 2, 20_000, 100.0)

        // AdBlue ensin syötteessä: diesel on silti ensin.
        assertEquals(listOf(FuelKind.DIESEL, FuelKind.ADBLUE), periodFuels(listOf(a, d), allKm(600), true).byKind.map { it.kind })
        val adBlueRows = carPeriodFuel(listOf(a, d, a2, d2), emptyMap()).filter { it.kind == FuelKind.ADBLUE }.associateBy { it.plate }
        assertEquals(4.0, adBlueRows.getValue("ABC-123").percentOfDiesel!!, 1e-9)
        assertEquals(1.0, adBlueRows.getValue("XYZ-789").percentOfDiesel!!, 1e-9)

        // Ilman dieseliä ei osuutta.
        assertNull(periodFuels(listOf(a), allKm(600), includeConsumption = true).adBluePercent)
        assertNull(carPeriodFuel(listOf(a), emptyMap()).single().percentOfDiesel)
    }

    @Test
    fun `pelkät hinnattomat tankkaukset antavat litrat ja kulutuksen ilman hintoja`() {
        val f = fillup(95, "ABC-123", 2, 100_000, 40.0, cost = 0.0)

        val period = periodFuel(listOf(f), periodKm = 500, includeConsumption = true, kind = FuelKind.DIESEL)!!

        assertEquals(emptyMap(), period.costs)
        assertNull(period.avgPricePerLiter)
        assertNull(period.costPerKm)
        assertTrue(period.missingPrices)
        assertEquals("EUR", period.currency)
        assertEquals(40.0 / 500 * 100, period.consumption!!, 1e-9)
    }

    @Test
    fun `jakson autorivit ryhmitellään autoittain ja lajit auton sisällä vakiojärjestyksessä`() {
        val a1 = fillup(131, "ABC-123", 2, 100_000, 50.0)
        val a2 = fillup(132, "ABC-123", 4, 100_500, 40.0)
        val aAd = fillup(133, "ABC-123", 4, 100_500, 3.0, cost = 2.7, kind = FuelKind.ADBLUE)
        val x1 = fillup(134, "XYZ-789", 3, 20_000, 30.0)
        val xAd = fillup(135, "XYZ-789", 3, 20_000, 1.0, cost = 0.9, kind = FuelKind.ADBLUE)

        val rows = carPeriodFuel(listOf(xAd, aAd, x1, a1, a2), emptyMap())

        assertEquals(
            listOf("ABC-123" to FuelKind.DIESEL, "ABC-123" to FuelKind.ADBLUE, "XYZ-789" to FuelKind.DIESEL, "XYZ-789" to FuelKind.ADBLUE),
            rows.map { it.plate to it.kind },
        )
    }

    private fun seg(id: Long, plate: String?, start: Long, end: Long?) = WorkSegmentEntity(
        id = id, routeDayId = 1, startEpochSec = day(3), endEpochSec = end?.let { day(3) + 3600 },
        odometerStartKm = start, odometerEndKm = end, licensePlate = plate,
    )

    @Test
    fun `usean polttoaineen jakson kilometrit jaetaan autoittain jakson tankkausten mukaan`() {
        val segments = listOf(seg(1, "ABC-123", 100_000, 100_300), seg(2, "EL-1", 5_000, 5_100))
        val diesel = fillup(141, "ABC-123", 3, 100_300, 30.0)
        val adBlue = fillup(142, "ABC-123", 3, 100_300, 1.0, cost = 0.9, kind = FuelKind.ADBLUE)
        val charge = fillup(143, "EL-1", 3, 5_100, 20.0, cost = 5.0, kind = FuelKind.ELECTRIC)
        val mixed = listOf(diesel, adBlue, charge)

        val km = periodKmByKind(mixed, segments) { null }

        assertEquals(mapOf(FuelKind.DIESEL to 300L, FuelKind.ELECTRIC to 100L, FuelKind.ADBLUE to 300L), km)
        val fuels = periodFuels(mixed, km, includeConsumption = true)
        assertEquals(30.0 / 300 * 100, fuels[FuelKind.DIESEL]!!.consumption!!, 1e-9)
        assertEquals(20.0 / 100 * 100, fuels[FuelKind.ELECTRIC]!!.consumption!!, 1e-9)
        assertEquals(1.0 / 300 * 1000, fuels[FuelKind.ADBLUE]!!.consumption!!, 1e-9)
    }

    @Test
    fun `usean polttoaineen jaksolla kohdistamaton työjakso estää kulutussuhteen`() {
        // Rekkarittoman jakson litrat jäisivät jaettavaan, mutta kilometrit puuttuisivat jakajasta.
        val segments = listOf(seg(1, "ABC-123", 100_000, 100_300), seg(2, null, 100_300, 100_500), seg(3, "EL-1", 5_000, 5_100))
        val mixed = listOf(fillup(141, "ABC-123", 3, 100_300, 30.0), fillup(143, "EL-1", 3, 5_100, 20.0, cost = 5.0, kind = FuelKind.ELECTRIC))

        val km = periodKmByKind(mixed, segments) { null }

        assertEquals(emptyMap(), km)
        assertNull(periodFuels(mixed, km, true)[FuelKind.DIESEL]!!.consumption)
    }

    @Test
    fun `yhden polttoaineen jakso saa rekkarittomat kilometrit mutta ei toisen polttoaineen auton kilometrejä`() {
        // Sähköauto ajoi, mutta sitä ei ladattu jaksolla: sen kilometrit eivät laimenna dieseliä.
        val segments = listOf(seg(1, "ABC-123", 100_000, 100_300), seg(2, null, 100_300, 100_500), seg(3, "EL-1", 5_000, 5_500))
        val diesel = listOf(fillup(141, "ABC-123", 3, 100_300, 30.0))
        val fuelOf = mapOf("EL-1" to FuelKind.ELECTRIC)::get

        val km = periodKmByKind(diesel, segments, fuelOf)

        assertEquals(500L, km[FuelKind.DIESEL])
        assertEquals(500L, km[FuelKind.ADBLUE])
        assertEquals(30.0 / 500 * 100, periodFuels(diesel, km, true)[FuelKind.DIESEL]!!.consumption!!, 1e-9)
    }

    @Test
    fun `jakson kilometrit muulle kuin dieselille, pelkälle AdBluelle ja hybridille`() {
        val plateless = seg(1, null, 1_000, 1_400)
        // Ainoa polttoaine on sähkö: rekkarittomat kilometrit kuuluvat sille.
        val charge = fillup(151, "EL-1", 3, 1_400, 30.0, kind = FuelKind.ELECTRIC)
        assertEquals(400L, periodKmByKind(listOf(charge), listOf(plateless)) { null }[FuelKind.ELECTRIC])
        // Pelkkä AdBlue: rekkarittomat kilometrit dieselille ja siitä AdBluelle.
        val adBlue = fillup(152, "ABC-123", 3, 1_400, 2.0, kind = FuelKind.ADBLUE)
        assertEquals(400L, periodKmByKind(listOf(adBlue), listOf(plateless)) { null }[FuelKind.ADBLUE])
        // Ladattava hybridi: sama auto tankkaa bensiiniä ja lataa, ja molemmat lajit saavat auton kilometrit.
        val hybrid = listOf(
            fillup(153, "PH-1", 3, 2_100, 5.0, kind = FuelKind.PETROL),
            fillup(154, "PH-1", 3, 2_100, 8.0, kind = FuelKind.ELECTRIC),
        )
        val km = periodKmByKind(hybrid, listOf(seg(2, "PH-1", 2_000, 2_100))) { null }
        assertEquals(100L, km[FuelKind.PETROL])
        assertEquals(100L, km[FuelKind.ELECTRIC])
    }

    @Test
    fun `auton laji tulee jakson omista tankkauksista ennen profiilia ja keskeneräiset jaksot ohitetaan`() {
        // XYZ tankkasi jaksolla dieseliä: myöhempi bensiiniprofiili ei muuta vanhan jakson lukuja.
        val segments = listOf(
            seg(1, "XYZ-1", 10_000, 10_200),
            seg(2, "EL-1", 5_000, 5_100),
            seg(3, "XYZ-1", 10_200, null),
            seg(4, "EL-1", 5_100, 5_000),
        )
        val fillups = listOf(fillup(161, "XYZ-1", 3, 10_200, 20.0), fillup(162, "EL-1", 3, 5_100, 10.0, kind = FuelKind.ELECTRIC))
        val fuelOf = mapOf("XYZ-1" to FuelKind.PETROL)::get

        val km = periodKmByKind(fillups, segments, fuelOf)

        assertEquals(200L, km[FuelKind.DIESEL])
        assertEquals(100L, km[FuelKind.ELECTRIC])
        assertNull(km[FuelKind.PETROL])
    }

    @Test
    fun `lukemarajat lasketaan saman lajin tankkauksista`() {
        val diesel = fillup(51, "ABC-123", 5, 100_500, 50.0)
        val adBlue = fillup(52, "ABC-123", 5, 100_600, 5.0, kind = FuelKind.ADBLUE)
        val all = listOf(diesel, adBlue)

        val forAdBlue = fillupKmBounds(all, "ABC-123", excludeId = null, atEpochSec = day(5), odometerKm = 100_550, kind = FuelKind.ADBLUE)
        assertNull(forAdBlue.previousKm)
        assertEquals(100_600, forAdBlue.nextKm)

        val forDiesel = fillupKmBounds(all, "ABC-123", excludeId = null, atEpochSec = day(5), odometerKm = 100_550, kind = FuelKind.DIESEL)
        assertEquals(100_500, forDiesel.previousKm)
        assertNull(forDiesel.nextKm)
    }
}
