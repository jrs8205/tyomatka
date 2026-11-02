package fi.tyomatka.ui.history

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.*
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class WorkPeriodsTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private fun date(value: String) = LocalDate.parse(value)
    private fun at(value: String, hour: Int = 12) = date(value).atTime(hour, 0).atZone(zone).toEpochSecond()
    private fun fuel(id: Long, value: String, km: Long = id * 500, liters: Double = 50.0, cost: Double = 90.0) =
        FuelFillupEntity(id, at(value), "ABC-123", km, liters, cost, if (cost > 0) cost / liters else 0.0, currency = "EUR")
    private fun segment(id: Long, value: String, km: Long = 200) =
        WorkSegmentEntity(id, id, at(value, 8), at(value, 16), 100_000, 100_000 + km, "ABC-123")
    private fun history(segments: List<WorkSegmentEntity>, fuels: List<FuelFillupEntity>) = workHistoryDays(
        segments.map { RouteDayEntity(id = it.routeDayId, dateEpochDay = java.time.Instant.ofEpochSecond(it.startEpochSec).atZone(zone).toLocalDate().toEpochDay()) },
        segments, fuels, zone,
    )
    private fun rows(days: List<WorkHistoryDay>, period: WorkPeriod, all: List<FuelFillupEntity> = days.flatMap { it.fillups }) =
        workPeriodRows(days, period, fuelIntervals(all).associateBy { it.fillupId }, fuelOf = { null }) { week, year -> "Vko $week / $year" }

    @Test fun `day row shows a note marker only for day rows with a note`() {
        val seg = segment(1, "2026-09-25")
        val routeDay = RouteDayEntity(id = 1, dateEpochDay = date("2026-09-25").toEpochDay(), note = "Latauspiste rikki")
        val days = workHistoryDays(listOf(routeDay), listOf(seg), emptyList(), zone)
        assertEquals("Latauspiste rikki", days.single().note)
        assertTrue(rows(days, WorkPeriod.DAY).single().hasNote)
        assertFalse(rows(days, WorkPeriod.WEEK).single().hasNote, "viikkorivillä ei merkkiä")
    }

    @Test fun `fuel only day without a day row has no note`() {
        val days = workHistoryDays(emptyList(), emptyList(), listOf(fuel(1, "2026-09-26")), zone)
        assertEquals("", days.single().note)
        assertFalse(rows(days, WorkPeriod.DAY).single().hasNote)
    }

    @Test fun `blank note gives no marker`() {
        val seg = segment(1, "2026-09-25")
        val routeDay = RouteDayEntity(id = 1, dateEpochDay = date("2026-09-25").toEpochDay(), note = "   ")
        assertFalse(rows(workHistoryDays(listOf(routeDay), listOf(seg), emptyList(), zone), WorkPeriod.DAY).single().hasNote)
    }

    @Test fun `ISO week keeps December and January together but calendar years separate`() {
        val days = history(listOf(segment(1, "2020-12-31"), segment(2, "2021-01-01")), listOf(fuel(1, "2021-01-03")))
        val week = rows(days, WorkPeriod.WEEK).single()
        assertEquals("Vko 53 / 2020", week.label)
        assertEquals(400, week.km)
        assertEquals(16 * 3600, week.durationSec)
        assertEquals(50.0, week.fuel!![FuelKind.DIESEL]!!.liters)
        val years = rows(days, WorkPeriod.YEAR)
        assertEquals(listOf("2021", "2020"), years.map { it.label })
        assertEquals(listOf(200L, 200L), years.map { it.km })
        assertEquals(listOf(50.0, null), years.map { it.fuel?.get(FuelKind.DIESEL)?.liters })
    }

    @Test fun `week splits kilometres by car fuel across days`() {
        val dieselSeg = WorkSegmentEntity(1, 1, at("2026-09-21", 8), at("2026-09-21", 16), 100_000, 100_300, "ABC-123")
        val electricSeg = WorkSegmentEntity(2, 2, at("2026-09-22", 8), at("2026-09-22", 16), 5_000, 5_200, "EL-1")
        val fuels = listOf(
            fuel(1, "2026-09-21", km = 100_300, liters = 30.0),
            FuelFillupEntity(2, at("2026-09-22"), "EL-1", 5_200, 40.0, 10.0, 0.25, kind = FuelKind.ELECTRIC, currency = "EUR"),
        )

        val week = rows(history(listOf(dieselSeg, electricSeg), fuels), WorkPeriod.WEEK).single()

        assertEquals(500, week.km)
        assertEquals(30.0 / 300 * 100, week.fuel!![FuelKind.DIESEL]!!.consumption!!, 1e-9)
        assertEquals(40.0 / 200 * 100, week.fuel[FuelKind.ELECTRIC]!!.consumption!!, 1e-9)
    }

    @Test fun `weeks separate at Monday and not at New Year`() {
        val fuels = listOf(fuel(1, "2020-12-31"), fuel(2, "2021-01-03"), fuel(3, "2021-01-04"))
        val weeks = rows(history(emptyList(), fuels), WorkPeriod.WEEK)
        assertEquals(listOf("Vko 1 / 2021", "Vko 53 / 2020"), weeks.map { it.label })
        assertEquals(listOf(50.0, 100.0), weeks.map { it.fuel!![FuelKind.DIESEL]!!.liters })
    }

    @Test fun `every period includes fuel only dates and months`() {
        val days = history(listOf(segment(1, "2026-08-31")), listOf(fuel(1, "2026-09-25")))
        for (period in WorkPeriod.entries) {
            val result = rows(days, period)
            assertEquals(200, result.sumOf { it.km })
            assertEquals(50.0, result.sumOf { it.fuel?.get(FuelKind.DIESEL)?.liters ?: 0.0 })
        }
        val month = rows(days, WorkPeriod.MONTH).first()
        assertEquals("Syyskuu 2026", month.label)
        assertFalse(month.hasWork)
        assertNull(month.fuel!![FuelKind.DIESEL]!!.consumption)
        assertNull(month.fuel[FuelKind.DIESEL]!!.costPerKm)
        assertFalse(rows(days, WorkPeriod.DAY).first().hasWork)
    }

    @Test fun `local midnight and leap day put fuel on correct calendar date`() {
        val leap = fuel(1, "2024-02-29").copy(epochSec = at("2024-03-01", 0) - 1)
        val march = fuel(2, "2024-03-01").copy(epochSec = at("2024-03-01", 0))
        val days = history(emptyList(), listOf(leap, march))
        assertEquals(listOf(date("2024-03-01").toEpochDay(), date("2024-02-29").toEpochDay()), days.map { it.date })
        assertEquals(listOf("Maaliskuu 2024", "Helmikuu 2024"), rows(days, WorkPeriod.MONTH).map { it.label })
    }

    @Test fun `car intervals cross month boundaries and use weighted ratios`() {
        val fuels = listOf(fuel(1, "2026-08-31", 100_000), fuel(2, "2026-09-01", 100_500, 40.0, 72.0), fuel(3, "2026-09-20", 101_500, 90.0, 162.0))
        val september = rows(history(emptyList(), fuels), WorkPeriod.MONTH).first()
        assertEquals(130.0, september.fuel!![FuelKind.DIESEL]!!.liters)
        assertEquals(130.0 / 1500 * 100, september.carFuel.single().consumption!!, 1e-9)
        assertEquals(234.0 / 1500, september.carFuel.single().costPerKm!!, 1e-9)
    }

    @Test fun `missing readings suppress period ratio but keep measured car consumption`() {
        val fuels = listOf(fuel(1, "2026-09-01"), fuel(2, "2026-09-02"))
        val finished = segment(1, "2026-09-01")
        val incomplete = segment(2, "2026-09-02").copy(odometerEndKm = null)
        val week = rows(history(listOf(finished, incomplete), fuels), WorkPeriod.WEEK).single()
        assertNull(week.fuel!![FuelKind.DIESEL]!!.consumption)
        assertNull(week.fuel[FuelKind.DIESEL]!!.costPerKm)
        assertEquals(10.0, week.carFuel.single().consumption)
        assertEquals(200, week.km)
    }

    @Test fun `ongoing day keeps period ratio from finished kilometres`() {
        // Avoin segmentti ei ole kilometreissä mukana; suhde on arvio valmiista
        // päivistä — muuten rivi puuttuisi joka työpäivä.
        val fuels = listOf(fuel(1, "2026-09-01"), fuel(2, "2026-09-02"))
        val finished = segment(1, "2026-09-01")
        val ongoing = segment(2, "2026-09-02").copy(odometerEndKm = null, endEpochSec = null)
        val week = rows(history(listOf(finished, ongoing), fuels), WorkPeriod.WEEK).single()
        assertNotNull(week.fuel!![FuelKind.DIESEL]!!.consumption)
        assertEquals(200, week.km)
    }

    @Test fun `car changes sum differences rather than subtracting different odometers`() {
        val a = segment(1, "2026-09-25", 120).copy(endEpochSec = at("2026-09-25", 12))
        val b = a.copy(id = 2, startEpochSec = at("2026-09-25", 12), endEpochSec = at("2026-09-25", 16), odometerStartKm = 20_000, odometerEndKm = 20_080, licensePlate = "XYZ-789")
        val day = WorkHistoryDay(date("2026-09-25").toEpochDay(), listOf(a, b), emptyList())
        for (period in WorkPeriod.entries) {
            val row = rows(listOf(day), period).single()
            assertEquals(200, row.km)
            assertEquals(8 * 3600, row.durationSec)
        }
    }

    @Test fun `latest car keeps empty plate and missing reading instead of borrowing older car`() {
        val a = segment(1, "2026-09-24")
        val b = segment(2, "2026-09-25").copy(licensePlate = null, odometerStartKm = null, odometerEndKm = null)
        assertNull(latestWorkSegment(listOf(b, a))!!.licensePlate)
        assertNull(latestWorkReading(listOf(a, b)))
        assertEquals(200L, latestWorkReading(listOf(a, b.copy(odometerStartKm = 200))))
    }

    @Test fun `empty history stays empty in every period`() {
        for (period in WorkPeriod.entries) assertTrue(rows(emptyList(), period).isEmpty())
    }

    @Test fun `Finnish summer and winter clock changes count elapsed hours`() {
        for ((startDate, endDate, hours) in listOf(
            Triple("2026-03-28", "2026-03-29", 7L),
            Triple("2026-10-24", "2026-10-25", 9L),
        )) {
            val overnight = segment(1, startDate).copy(startEpochSec = at(startDate, 22), endEpochSec = at(endDate, 6))
            val days = history(listOf(overnight), emptyList())
            assertEquals(date(startDate).toEpochDay(), days.single().date)
            for (period in WorkPeriod.entries) assertEquals(hours * 3600, rows(days, period).single().durationSec)
        }
    }

    @Test fun `legacy decreasing or missing readings are visible but excluded from kilometre sums`() {
        val good = segment(1, "2026-09-25", 200)
        for (bad in listOf(
            good.copy(id = 2, odometerEndKm = 99_999),
            good.copy(id = 2, odometerStartKm = -100),
            good.copy(id = 2, odometerStartKm = null),
        )) {
            val day = WorkHistoryDay(date("2026-09-25").toEpochDay(), listOf(good, bad), emptyList())
            for (period in WorkPeriod.entries) {
                val row = rows(listOf(day), period).single()
                assertTrue(row.missingReadings)
                assertEquals(200, row.km)
            }
        }
    }

    @Test fun `aggregated period counts work days but not fuel only days`() {
        val days = history(listOf(segment(1, "2021-01-04"), segment(2, "2021-01-05")), listOf(fuel(1, "2021-01-06")))
        val week = rows(days, WorkPeriod.WEEK).single()
        assertEquals(2, week.workDays)
        val dayRows = rows(days, WorkPeriod.DAY)
        assertEquals(listOf(0, 1, 1), dayRows.map { it.workDays })
    }
}
