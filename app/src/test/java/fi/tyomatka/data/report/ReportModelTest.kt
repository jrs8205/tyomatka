package fi.tyomatka.data.report

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportModelTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private val october = ExportPeriod.Month(YearMonth.of(2026, 10))
    private var nextId = 1L
    private val routeDays = mutableListOf<RouteDayEntity>()
    private val segments = mutableListOf<WorkSegmentEntity>()

    private fun day(m: Int, d: Int) = LocalDate.of(2026, m, d).toEpochDay()
    private fun sec(m: Int, d: Int, h: Int = 12) = LocalDate.of(2026, m, d).atTime(h, 0).atZone(zone).toEpochSecond()

    /** Työpäivä klo 7–15 ja 100 km. */
    private fun work(m: Int, d: Int, plate: String = "ABC-123", fromKm: Long = 1000) {
        val routeDay = RouteDayEntity(id = nextId++, dateEpochDay = day(m, d))
        routeDays += routeDay
        segments += WorkSegmentEntity(
            id = nextId++,
            routeDayId = routeDay.id,
            startEpochSec = sec(m, d, 7),
            endEpochSec = sec(m, d, 15),
            odometerStartKm = fromKm,
            odometerEndKm = fromKm + 100,
            licensePlate = plate,
        )
    }

    private fun fill(
        m: Int,
        d: Int,
        km: Long,
        liters: Double,
        plate: String = "ABC-123",
        kind: FuelKind = FuelKind.DIESEL,
        currency: String = "EUR",
        full: Boolean = true,
    ) = FuelFillupEntity(
        id = nextId++,
        epochSec = sec(m, d),
        licensePlate = plate,
        odometerKm = km,
        liters = liters,
        totalCost = liters * 2,
        pricePerLiter = 2.0,
        kind = kind,
        fullTank = full,
        currency = currency,
    )

    private fun photo(fillup: FuelFillupEntity, name: String) =
        FuelPhotoEntity(id = nextId++, fillupId = fillup.id, fileName = name, addedAtEpochSec = fillup.epochSec)

    private fun model(
        period: ExportPeriod,
        fillups: List<FuelFillupEntity> = emptyList(),
        photos: Map<Long, List<FuelPhotoEntity>> = emptyMap(),
        cars: List<CarEntity> = emptyList(),
    ) = reportModel(period, segments, routeDays, fillups, photos, cars, zone)

    @Test
    fun `kuukauden jakso ottaa mukaan ensimmäisen ja viimeisen päivän mutta ei edellistä`() {
        work(9, 30)
        work(10, 1)
        work(10, 31)
        val fills = listOf(fill(9, 30, 1100, 40.0), fill(10, 1, 1200, 41.0), fill(10, 31, 1300, 42.0))
        val m = model(october, fills)
        assertEquals(listOf(day(10, 31), day(10, 1)), m.days.map { it.date })
        assertEquals(listOf(fills[2].id, fills[1].id), m.entries.map { it.fillup.id })
        val month = m.months.single()
        assertEquals(83.0, assertNotNull(month.fuels[FuelKind.DIESEL]).liters)
        assertEquals(2, month.workDays)
        assertEquals(2, assertNotNull(m.total).workDays)
    }

    @Test
    fun `viikko, joka alkaa ennen jaksoa, sisältää vain jakson päivät`() {
        // Viikko 40 on 28.9.–4.10.2026.
        work(9, 28)
        work(9, 30)
        work(10, 1)
        work(10, 2)
        val m = model(october)
        val week = m.weeks.single()
        assertEquals(40, week.isoWeek)
        assertEquals(day(10, 1), week.fromDay)
        assertEquals(day(10, 2), week.toDay)
        assertEquals(2, week.workDays)
        assertEquals(200, week.km)
        assertEquals(2 * 8 * 3600L, week.workSec)
        assertNull(m.months.single().isoWeek)
        assertEquals(1, m.years.size)
        assertEquals(200, assertNotNull(m.total).km)
    }

    @Test
    fun `ryhmät ovat uusin ensin`() {
        work(10, 1)
        work(10, 20)
        work(11, 3)
        val m = model(ExportPeriod.Year(2026))
        assertEquals(listOf(45, 43, 40), m.weeks.map { it.isoWeek })
        assertEquals(listOf(day(11, 3), day(10, 1)), m.months.map { it.fromDay })
    }

    @Test
    fun `autoprofiileista tulevat jakson autot, koko varmuuskopiosta kaikki`() {
        work(9, 30, plate = "OLD-1")
        work(10, 1, plate = "ABC-123")
        val fills = listOf(fill(10, 2, 500, 30.0, plate = "EVO-1", kind = FuelKind.ELECTRIC))
        val cars = listOf(
            CarEntity("OLD-1", FuelKind.PETROL, null),
            CarEntity("NEW-9", null, null),
            CarEntity("EVO-1", FuelKind.ELECTRIC, null),
            CarEntity("ABC-123", FuelKind.DIESEL, null),
        )
        assertEquals(listOf("ABC-123", "EVO-1"), model(october, fills, cars = cars).carProfiles.map { it.plate })
        assertEquals(
            listOf("ABC-123", "EVO-1", "NEW-9", "OLD-1"),
            model(ExportPeriod.Full, fills, cars = cars).carProfiles.map { it.plate },
        )
    }

    @Test
    fun `usean valuutan autolla summat valuutoittain eikä hintaa kilometriltä`() {
        val fills = listOf(
            fill(9, 28, 1000, 40.0),
            fill(10, 5, 1500, 35.0),
            fill(10, 20, 2000, 45.0, currency = "SEK"),
        )
        val car = model(october, fills).cars.single()
        assertNull(car.costPerKm)
        assertEquals(setOf("EUR", "SEK"), car.costs.keys)
        assertEquals(2, car.count)
        assertEquals(80.0, car.amount)
        // Väli alkaa ennen jaksoa olleesta täydestä: (35 + 45) l / 1000 km × 100.
        assertEquals(8.0, assertNotNull(car.avgConsumption), 1e-9)
    }

    @Test
    fun `usean valuutan jaksolla hinta kilometriltä jää pois, vaikka välit ovat euroissa`() {
        val fills = listOf(
            fill(9, 28, 1000, 40.0),
            fill(10, 5, 1200, 20.0, currency = "SEK", full = false),
            fill(10, 10, 1500, 30.0),
            fill(10, 20, 2000, 35.0),
        )
        val car = model(october, fills).cars.single()
        assertEquals(setOf("SEK", "EUR"), car.costs.keys)
        assertNull(car.costPerKm)
    }

    @Test
    fun `hinnaton ulkomaan tankkaus tekee jaksosta usean valuutan jakson`() {
        val fills = listOf(
            fill(9, 28, 1000, 40.0),
            fill(10, 5, 1200, 20.0, currency = "SEK", full = false).copy(totalCost = 0.0, pricePerLiter = 0.0),
            fill(10, 10, 1500, 30.0),
            fill(10, 20, 2000, 35.0),
        )
        val car = model(october, fills).cars.single()
        // Summissa vain hinnoitellut euroissa, mutta hinta kilometriltä jää pois kuten jaksokortissa.
        assertEquals(setOf("EUR"), car.costs.keys)
        assertNull(car.costPerKm)
    }

    @Test
    fun `yhden valuutan autolla hinta kilometriltä, AdBlue omana rivinään ilman sitä`() {
        val fills = listOf(
            fill(10, 1, 1000, 40.0),
            fill(10, 2, 1000, 5.0, kind = FuelKind.ADBLUE),
            fill(10, 10, 1500, 30.0),
        )
        val cars = model(october, fills).cars
        assertEquals(listOf(FuelKind.DIESEL, FuelKind.ADBLUE), cars.map { it.kind })
        val diesel = cars.first()
        assertEquals(0.12, assertNotNull(diesel.costPerKm), 1e-9)
        assertEquals("EUR", diesel.currency)
        assertNull(cars.last().costPerKm)
    }

    @Test
    fun `kulutusväli lasketaan koko historiasta mutta näytetään vain jakson kirjauksille`() {
        val first = fill(9, 28, 1000, 40.0)
        val partial = fill(10, 3, 1200, 20.0, full = false)
        val closing = fill(10, 10, 1500, 30.0)
        val m = model(october, listOf(first, partial, closing))
        assertEquals(listOf(closing.id, partial.id), m.entries.map { it.fillup.id })
        val interval = assertNotNull(m.entries.first().interval)
        assertEquals(1000, interval.fromKm)
        assertEquals(10.0, interval.consumption, 1e-9)
        assertNull(m.entries.last().interval)
    }

    @Test
    fun `tyhjä jakso ei kaadu`() {
        work(9, 30)
        val m = model(october, listOf(fill(9, 30, 1100, 40.0)))
        assertTrue(m.days.isEmpty() && m.weeks.isEmpty() && m.months.isEmpty() && m.years.isEmpty())
        assertTrue(m.entries.isEmpty() && m.cars.isEmpty() && m.photos.isEmpty() && m.carProfiles.isEmpty())
        assertNull(m.total)
    }

    @Test
    fun `kuvat ovat kirjausten järjestyksessä ja numeroitu kirjauksen sisällä`() {
        val older = fill(10, 1, 1000, 40.0)
        val newer = fill(10, 5, 1400, 30.0)
        val photos = mapOf(
            older.id to listOf(photo(older, "a"), photo(older, "b")),
            newer.id to listOf(photo(newer, "c")),
        )
        val m = model(october, listOf(older, newer), photos)
        assertEquals(listOf("c", "a", "b"), m.photos.map { it.photo.fileName })
        assertEquals(listOf(0 to 1, 0 to 2, 1 to 2), m.photos.map { it.index to it.count })
        assertEquals(listOf(newer.id, older.id, older.id), m.photos.map { it.fillup.id })
        assertEquals(listOf("c"), m.entries.first().photos.map { it.photo.fileName })
        assertEquals(listOf("a", "b"), m.entries.last().photos.map { it.photo.fileName })
    }
}
