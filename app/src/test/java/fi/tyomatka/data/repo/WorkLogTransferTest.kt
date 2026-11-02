package fi.tyomatka.data.repo

import fi.tyomatka.R
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.PoiCorrectionEntity
import fi.tyomatka.data.db.PoiPriceEntity
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.TemplateStopEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.report.ExportPeriod
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.*

class WorkLogTransferTest {
    private val nb = '\u00A0'
    private val zone = ZoneId.systemDefault()
    private val date = LocalDate.of(2026, 9, 25)
    private val noon = date.atTime(12, 0).atZone(zone).toEpochSecond()
    private fun fillup(id: Long, km: Long = 100_000, at: Long = noon) =
        FuelFillupEntity(id, at, "ABC-123", km, 50.0, 90.0, 1.8, currency = "EUR")
    private fun html(json: String) = "<script type=\"application/json\" id=\"stoppi-data\">$json</script>"

    private fun tStop(
        label: String,
        address: String,
        placeId: String? = null,
        deadline: Int? = null,
        service: Long? = null,
        lat: Double = 60.29,
        lng: Double = 25.04,
    ) = TemplateStopEntity(
        templateId = 0, orderIndex = 0, label = label, address = address, placeId = placeId, lat = lat, lng = lng,
        deadlineMinutesOfDay = deadline, serviceSec = service,
    )

    /** Tallennettu reitti stoppeineen suoraan storeen; palauttaa pohjan tunnisteen. */
    private fun WorkLogTestStore.template(name: String, stops: List<TemplateStopEntity>, updated: Long = 20): Long {
        val id = (templates.maxOfOrNull { it.id } ?: 0) + 1
        templates.add(RouteTemplateEntity(id = id, name = name, createdEpochSec = 10, updatedEpochSec = updated))
        stops.forEachIndexed { i, s -> templateStops.add(s.copy(id = templateStops.size + 1L, templateId = id, orderIndex = i)) }
        return id
    }

    private fun WorkLogTestStore.stopsOf(templateId: Long) =
        templateStops.filter { it.templateId == templateId }.sortedBy { it.orderIndex }

    @Test fun `v8 backup carries route templates only in the full export`() = runTest {
        val source = WorkLogTestStore()
        source.template(
            "Vantaa-kierros",
            listOf(
                tStop("Asiakas A", "Tie 1, Vantaa", placeId = "pelias:1", deadline = 600, service = 900),
                tStop("Asiakas B", "Tie 2, Vantaa", lat = 60.30, lng = 25.05),
            ),
        )
        val full = source.repository.exportHtml(noon)
        assertTrue(full.contains("\"version\":8"))
        assertTrue(full.contains("Vantaa-kierros"))
        val month = source.repository.exportHtml(noon, ExportPeriod.Month(YearMonth.of(2026, 9)))
        assertFalse(month.contains("\"templates\":[{"))
        assertFalse(month.contains("Vantaa-kierros"))

        val destination = WorkLogTestStore()
        assertEquals(1, destination.repository.importFromHtml(full).templates)
        val template = destination.templates.single()
        assertEquals("Vantaa-kierros", template.name)
        assertEquals(10L, template.createdEpochSec)
        assertEquals(20L, template.updatedEpochSec)
        val stops = destination.stopsOf(template.id)
        assertEquals(listOf("Asiakas A", "Asiakas B"), stops.map { it.label })
        assertEquals(listOf("Tie 1, Vantaa", "Tie 2, Vantaa"), stops.map { it.address })
        assertEquals("pelias:1", stops[0].placeId)
        assertEquals(600, stops[0].deadlineMinutesOfDay)
        assertEquals(900L, stops[0].serviceSec)
        assertNull(stops[1].placeId)
        assertEquals(60.30, stops[1].lat)
        assertEquals(25.05, stops[1].lng)
        // Toinen tuonti korvaa samannimisen: pohjia yhä yksi, laskuri kertoo korvatun.
        assertEquals(1, destination.repository.importFromHtml(full).templates)
        assertEquals(1, destination.templates.size)
        assertEquals(2, destination.stopsOf(destination.templates.single().id).size)
    }

    @Test fun `same-named template is replaced on import`() = runTest {
        val source = WorkLogTestStore()
        source.template("Vantaa-kierros", listOf(tStop("Asiakas A", "Tie 1, Vantaa"), tStop("Asiakas B", "Tie 2, Vantaa")), updated = 20)
        val report = source.repository.exportHtml(noon)
        val destination = WorkLogTestStore()
        val existing = destination.template("Vantaa-kierros", listOf(tStop("Vanha", "Tie 9, Vantaa")), updated = 5)
        destination.template("Espoo-kierros", listOf(tStop("Muu", "Tie 3, Espoo")), updated = 5)

        assertEquals(1, destination.repository.importFromHtml(report).templates)

        assertEquals(2, destination.templates.size)
        assertEquals(listOf("Asiakas A", "Asiakas B"), destination.stopsOf(existing).map { it.label })
        assertEquals(20L, destination.templates.single { it.id == existing }.updatedEpochSec)
        assertEquals(listOf("Muu"), destination.stopsOf(2).map { it.label })
    }

    @Test fun `full export summary counts route templates`() = runTest {
        val source = WorkLogTestStore()
        source.template("Vantaa-kierros", listOf(tStop("Asiakas A", "Tie 1, Vantaa")))
        assertEquals(1, source.repository.exportSummary(ExportPeriod.Full).templates)
        assertEquals(0, source.repository.exportSummary(ExportPeriod.Month(YearMonth.of(2026, 9))).templates)
    }

    @Test fun `template import waits for the shared stops lock`() = runTest {
        val source = WorkLogTestStore()
        source.template("Vantaa-kierros", listOf(tStop("Asiakas A", "Tie 1, Vantaa")))
        val report = source.repository.exportHtml(noon)
        val destination = WorkLogTestStore()
        destination.lock.mutex.lock()
        val job = launch { destination.repository.importFromHtml(report) }
        runCurrent()
        assertTrue(destination.templates.isEmpty())

        destination.lock.mutex.unlock()
        job.join()

        assertEquals(listOf("Vantaa-kierros"), destination.templates.map { it.name })
    }

    @Test fun `template import replaces and inserts inside one transaction`() = runTest {
        val source = WorkLogTestStore()
        source.template("Vantaa-kierros", listOf(tStop("Asiakas A", "Tie 1, Vantaa")))
        source.template("Espoo-kierros", listOf(tStop("Asiakas B", "Tie 2, Espoo")))
        val report = source.repository.exportHtml(noon)
        val destination = WorkLogTestStore()
        destination.template("Vantaa-kierros", listOf(tStop("Vanha", "Tie 9, Vantaa")))

        assertEquals(2, destination.repository.importFromHtml(report).templates)

        assertEquals(1, destination.transactions)
        assertEquals(0, WorkLogTestStore().also { it.repository.importFromHtml(html("""{"version":7,"exported":0,"days":[]}""")) }.transactions)
    }

    @Test fun `v7 backup without templates imports unchanged`() = runTest {
        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(html("""{"version":7,"exported":0,"days":[]}"""))
        assertEquals(0, result.templates)
        assertTrue(destination.templates.isEmpty())
    }

    @Test fun `invalid template coordinates are rejected`() = runTest {
        val destination = WorkLogTestStore()
        val file = html(
            """{"version":8,"exported":0,"days":[{"date":${date.toEpochDay()},"segments":[{"start":${noon - 3600},"end":$noon,"kmStart":1,"kmEnd":2,"plate":"ABC-123","backupId":"s1"}]}],""" +
                """"templates":[{"name":"Virhe","created":1,"updated":1,"stops":[{"label":"A","address":"Tie 1","lat":95.0,"lng":24.0}]}]}""",
        )
        val e = assertFailsWith<IllegalArgumentException> { destination.repository.importFromHtml(file) }
        assertEquals("Varmuuskopiossa on virheellinen tallennettu reitti. Tietoja ei tuotu.", e.message)
        assertTrue(destination.templates.isEmpty())
        assertTrue(destination.days.isEmpty())
        assertTrue(destination.segments.isEmpty())
    }

    @Test fun `v8 backup carries poi corrections and prices only in the full export`() = runTest {
        val source = WorkLogTestStore()
        source.poiDao.upsertCorrection(PoiCorrectionEntity("n1", "shower", "yes", 100))
        source.poiDao.upsertPrice(PoiPriceEntity("n1", FuelKind.DIESEL, 1.789, "EUR", 20_750))
        val full = source.repository.exportHtml(noon)
        assertTrue(full.contains("\"poiCorrections\":[{"))
        assertTrue(full.contains("\"poiPrices\":[{"))
        val month = source.repository.exportHtml(noon, ExportPeriod.Month(YearMonth.of(2026, 9)))
        assertFalse(month.contains("\"poiCorrections\":[{"))
        assertFalse(month.contains("\"poiPrices\":[{"))

        val destination = WorkLogTestStore()
        // Näkymätöntä hintaa ei lasketa käyttäjälle kerrottavaan määrään, vaikka se säilyy kannassa.
        assertEquals(1, destination.repository.importFromHtml(full).poiEdits)
        assertEquals(PoiCorrectionEntity("n1", "shower", "yes", 100), destination.poiDao.corrections.getValue("n1" to "shower"))
        assertEquals(PoiPriceEntity("n1", FuelKind.DIESEL, 1.789, "EUR", 20_750), destination.poiDao.prices.getValue("n1" to FuelKind.DIESEL))
        // Toinen tuonti ei kirjoita samaa uudelleen.
        assertEquals(0, destination.repository.importFromHtml(full).poiEdits)
    }

    @Test fun `newer local poi edits are kept and older ones replaced on import`() = runTest {
        val source = WorkLogTestStore()
        source.poiDao.upsertCorrection(PoiCorrectionEntity("n1", "shower", "yes", 100))
        source.poiDao.upsertCorrection(PoiCorrectionEntity("n1", "toilets", "no", 100))
        source.poiDao.upsertPrice(PoiPriceEntity("n1", FuelKind.DIESEL, 1.789, "EUR", 20_750))
        val full = source.repository.exportHtml(noon)

        val destination = WorkLogTestStore()
        destination.poiDao.upsertCorrection(PoiCorrectionEntity("n1", "shower", "no", 200))
        destination.poiDao.upsertCorrection(PoiCorrectionEntity("n1", "toilets", "yes", 50))
        destination.poiDao.upsertPrice(PoiPriceEntity("n1", FuelKind.DIESEL, 1.899, "EUR", 20_760))

        assertEquals(1, destination.repository.importFromHtml(full).poiEdits)
        assertEquals("no", destination.poiDao.corrections.getValue("n1" to "shower").value)
        assertEquals(PoiCorrectionEntity("n1", "toilets", "no", 100), destination.poiDao.corrections.getValue("n1" to "toilets"))
        assertEquals(1.899, destination.poiDao.prices.getValue("n1" to FuelKind.DIESEL).price)
    }

    @Test fun `invalid poi price is rejected before anything is written`() = runTest {
        val destination = WorkLogTestStore()
        val file = html(
            """{"version":8,"exported":0,"days":[{"date":${date.toEpochDay()},"segments":[{"start":${noon - 3600},"end":$noon,"kmStart":1,"kmEnd":2,"plate":"ABC-123","backupId":"s1"}]}],""" +
                """"poiPrices":[{"poi":"n1","fuel":"FOO","price":1.5,"currency":"EUR","day":20750}]}""",
        )
        val e = assertFailsWith<IllegalArgumentException> { destination.repository.importFromHtml(file) }
        assertEquals("Varmuuskopiossa on virheellinen kohdekorjaus. Tietoja ei tuotu.", e.message)
        assertTrue(destination.poiDao.prices.isEmpty())
        assertTrue(destination.segments.isEmpty())
        val bad = html("""{"version":8,"exported":0,"days":[],"poiCorrections":[{"poi":"n1","field":"unknown","value":"yes","at":1}]}""")
        assertFailsWith<IllegalArgumentException> { destination.repository.importFromHtml(bad) }
    }

    @Test fun `v8 backup without poi fields imports with zero edits`() = runTest {
        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(html("""{"version":8,"exported":0,"days":[]}"""))
        assertEquals(0, result.poiEdits)
        assertTrue(destination.poiDao.corrections.isEmpty())
    }

    @Test fun `backup roundtrip preserves distinct equal time and equal reading fuel records`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.addAll(listOf(fillup(20), fillup(21, 100_500), fillup(22).copy(liters = 40.0, totalCost = 72.0)))
        val report = source.repository.exportHtml(noon)
        assertTrue(report.contains("\"version\":8"))
        val destination = WorkLogTestStore()
        assertEquals(3, destination.repository.importFromHtml(report).fuelAdded)
        assertEquals(source.fillups.map { it.backupId }.toSet(), destination.fillups.map { it.backupId }.toSet())
        assertEquals(140.0, destination.fillups.sumOf { it.liters })
        assertEquals(3, destination.repository.importFromHtml(report).fuelSkipped)
        val reexport = destination.repository.exportHtml(noon)
        assertEquals(3, source.repository.importFromHtml(reexport).fuelSkipped)
        assertEquals(3, source.fillups.size)
    }

    private fun WorkLogTestStore.workDay(dayNote: String = "", dayId: Long = 1): Long {
        days.add(RouteDayEntity(id = dayId, dateEpochDay = date.toEpochDay(), note = dayNote))
        segments.add(WorkSegmentEntity(id = dayId, routeDayId = dayId, startEpochSec = noon - 3600, endEpochSec = noon, odometerStartKm = 1_000, odometerEndKm = 1_100, licensePlate = "ABC-123", backupId = "s$dayId"))
        return dayId
    }

    @Test fun `v7 backup carries day and fill-up notes through export and import`() = runTest {
        val source = WorkLogTestStore()
        source.workDay(dayNote = "Latauspiste rikki\nkorjattu 12.10.")
        source.fillups.add(fillup(20).copy(note = "<script>alert(1)</script> & \"lainaus\""))
        val report = source.repository.exportHtml(noon)
        assertTrue(report.contains("\"version\":8"))

        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(report)

        assertEquals(1, result.added)
        assertEquals(1, result.fuelAdded)
        assertEquals("Latauspiste rikki\nkorjattu 12.10.", destination.days.single().note)
        assertEquals("<script>alert(1)</script> & \"lainaus\"", destination.fillups.single().note)
    }

    @Test fun `backup without note fields imports empty notes`() = runTest {
        val destination = WorkLogTestStore()
        val file = html(
            """{"version":6,"exported":0,"days":[{"date":${date.toEpochDay()},"segments":[{"start":${noon - 3600},"end":$noon,"kmStart":1,"kmEnd":2,"plate":"ABC-123","backupId":"s1"}]}],""" +
                """"fuel":[{"at":$noon,"plate":"ABC-123","km":100,"liters":1.0,"cost":1.0,"price":1.0,"backupId":"f1","kind":"DIESEL","full":true,"currency":"EUR"}]}""",
        )
        destination.repository.importFromHtml(file)
        assertEquals("", destination.days.single().note)
        assertEquals("", destination.fillups.single().note)
    }

    @Test fun `import fills a blank note on an existing day and record but keeps an existing note`() = runTest {
        val source = WorkLogTestStore()
        source.workDay(dayNote = "Varmuuskopion teksti")
        source.fillups.add(fillup(20).copy(note = "Kuitti puuttuu"))
        val report = source.repository.exportHtml(noon)

        val blank = WorkLogTestStore()
        blank.workDay()
        blank.fillups.add(source.fillups.single().copy(note = ""))
        val filled = blank.repository.importFromHtml(report)
        assertEquals(0, filled.added, "sama päivä ei tuplaudu")
        assertEquals(1, filled.fuelSkipped, "teksti ei tee kirjauksesta uutta")
        assertEquals(1, blank.fillups.size)
        assertEquals("Varmuuskopion teksti", blank.days.single().note)
        assertEquals("Kuitti puuttuu", blank.fillups.single().note)

        val own = WorkLogTestStore()
        own.workDay(dayNote = "oma")
        own.fillups.add(source.fillups.single().copy(note = "oma kirjaus"))
        own.repository.importFromHtml(report)
        assertEquals("oma", own.days.single().note)
        assertEquals("oma kirjaus", own.fillups.single().note)
    }

    @Test fun `import fills the note without overwriting a concurrent edit of the same record`() = runTest {
        // tuonti kirjoitti koko rivin tilannekuvasta, jolloin tuonnin aikana tehty muokkaus (105 km) hävisi.
        val source = WorkLogTestStore()
        source.workDay()
        source.fillups.add(fillup(20).copy(note = "Kuitti puuttuu"))
        val report = source.repository.exportHtml(noon)

        val destination = WorkLogTestStore()
        destination.workDay()
        destination.fillups.add(source.fillups.single().copy(note = ""))
        destination.onFuelSnapshot = { destination.fillups[0] = destination.fillups[0].copy(odometerKm = 100_105) }
        destination.repository.importFromHtml(report)
        assertEquals(100_105, destination.fillups.single().odometerKm, "tuonnin aikainen muokkaus säilyy")
        assertEquals("Kuitti puuttuu", destination.fillups.single().note)
    }

    @Test fun `imported notes are normalised to 500 characters`() = runTest {
        val source = WorkLogTestStore()
        source.workDay(dayNote = "p".repeat(600))
        source.fillups.add(fillup(20).copy(note = " " + "k".repeat(600)))
        val destination = WorkLogTestStore()
        destination.repository.importFromHtml(source.repository.exportHtml(noon))
        assertEquals(500, destination.days.single().note.length)
        assertEquals(500, destination.fillups.single().note.length)
    }

    @Test fun `v6 period backup imports its records and photos and reports the period`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.add(fillup(1))
        source.fillups.add(fillup(2, km = 99_000, at = noon - 40 * 86_400))
        source.addPhoto(1, byteArrayOf(1, 2))
        val september = ExportPeriod.Month(YearMonth.of(2026, 9))

        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(source.repository.exportHtml(noon, september))

        assertEquals(1, result.fuelAdded)
        assertEquals(1, result.photosAdded)
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay() to LocalDate.of(2026, 9, 30).toEpochDay(), result.period)
        assertEquals(listOf(source.fillups.first().backupId), destination.fillups.map { it.backupId })
        // Koko varmuuskopiolla ja vanhoilla versioilla jaksoa ei ole.
        assertNull(WorkLogTestStore().repository.importFromHtml(source.repository.exportHtml(noon)).period)
        assertNull(WorkLogTestStore().repository.importFromHtml(html("""{"version":5,"exported":0,"days":[]}""")).period)
    }

    @Test fun `v4 backup keeps fuel kind, full tank, currency and car profiles`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.add(fillup(1).copy(kind = FuelKind.ADBLUE, fullTank = false, currency = "SEK"))
        source.cars.add(CarEntity("ABC-123", FuelKind.ELECTRIC, "NOK"))
        val report = source.repository.exportHtml(noon)
        assertTrue(report.contains("\"version\":8"))

        val destination = WorkLogTestStore()
        destination.repository.importFromHtml(report)

        val restored = destination.fillups.single()
        assertEquals(FuelKind.ADBLUE, restored.kind)
        assertFalse(restored.fullTank)
        assertEquals("SEK", restored.currency)
        assertEquals(listOf(CarEntity("ABC-123", FuelKind.ELECTRIC, "NOK")), destination.cars)
    }

    @Test fun `v3 fuel imports as full diesel in euros without duplicating a migrated row`() = runTest {
        val content = html("""{"version":3,"exported":0,"days":[],"fuel":[
            {"at":$noon,"plate":"ABC-123","km":100000,"liters":50.0,"cost":90.0,"price":1.8,"backupId":"v3-1"}]}""")
        val store = WorkLogTestStore()
        store.repository.importFromHtml(content)
        val imported = store.fillups.single()
        assertEquals(FuelKind.DIESEL, imported.kind)
        assertTrue(imported.fullTank)
        assertEquals("EUR", imported.currency)

        // Migraatio merkitsi rivin aikanaan puhelimen valuutalla: vanhan tiedoston sisältövertailu ei katso
        // valuuttaa, joten tuonti ei tuplaa.
        val migrated = WorkLogTestStore()
        migrated.fillups.add(imported.copy(id = 9, backupId = "toinen", currency = "SEK"))
        assertEquals(1, migrated.repository.importFromHtml(content).fuelSkipped)
        assertEquals(1, migrated.fillups.size)
        assertEquals("SEK", migrated.fillups.single().currency)
    }

    @Test fun `backup row without a currency imports in euros and a stored currency stays`() = runTest {
        val store = WorkLogTestStore()
        store.repository.importFromHtml(html("""{"version":4,"exported":0,"days":[],"fuel":[
            {"at":$noon,"plate":"ABC-123","km":100000,"liters":50.0,"cost":90.0,"price":1.8,"backupId":"a"},
            {"at":${noon + 60},"plate":"ABC-123","km":100100,"liters":50.0,"cost":900.0,"price":18.0,"backupId":"b","currency":"SEK"}]}"""))

        assertEquals(mapOf("a" to "EUR", "b" to "SEK"), store.fillups.associate { it.backupId to it.currency })
    }

    @Test fun `unknown fuel kind, currency or car aborts import before anything is written`() = runTest {
        // Jokaisessa tiedostossa on myös kelvollinen työpäivä ja tankkaus: mitään ei saa kirjoittaa.
        val day = """{"date":${date.toEpochDay()},"segments":[{"start":$noon,"end":${noon + 3600},"kmStart":100,"kmEnd":200,"plate":"ABC-123"}]}"""
        val goodFuel = """{"at":$noon,"plate":"ABC-123","km":100,"liters":50.0,"cost":90.0,"price":1.8}"""
        fun file(extraFuel: String = "", car: String? = null) = html(
            """{"version":4,"exported":0,"days":[$day],"fuel":[$goodFuel,
            {"at":${noon + 60},"plate":"ABC-123","km":150,"liters":5.0,"cost":9.0,"price":1.8$extraFuel}]""" +
                (car?.let { ""","cars":[$it]""" } ?: "") + "}"
        )
        val bad = listOf(
            file(""","kind":"FUEL""""),
            file(""","currency":"euro""""),
            file(""","currency":"XXX""""),
            file(car = """{"plate":"ABC-123","fuel":"ADBLUE"}"""),
            file(car = """{"plate":"ABC-123","currency":"eur"}"""),
            file(car = """{"plate":" "}"""),
        )
        val store = WorkLogTestStore()

        bad.forEach { assertFailsWith<IllegalArgumentException> { store.repository.importFromHtml(it) } }

        assertTrue(store.days.isEmpty())
        assertTrue(store.segments.isEmpty())
        assertTrue(store.fillups.isEmpty())
        assertTrue(store.cars.isEmpty())
        // Sama tiedosto kelvollisena tuodaan kokonaan.
        assertEquals(2, store.repository.importFromHtml(file()).fuelAdded)
    }

    @Test fun `imported car profiles are added only for cars missing on the phone`() = runTest {
        // Käytetyn auton puhelimen rivi voittaa kokonaan: myös "Automaattinen" (null) on käyttäjän valinta.
        val store = WorkLogTestStore()
        store.cars.add(CarEntity("ABC-123", null, null))
        store.fillups.add(fillup(1))
        val content = html("""{"version":4,"exported":0,"days":[],"fuel":[],"cars":[
            {"plate":"abc 123","fuel":"PETROL","currency":"NOK"},{"plate":"XYZ-789","fuel":"GAS"},{"plate":"QQQ-1"}]}""")

        store.repository.importFromHtml(content)

        assertEquals(
            setOf(CarEntity("ABC-123", null, null), CarEntity("XYZ-789", FuelKind.GAS, null), CarEntity("QQQ-1", null, null)),
            store.cars.toSet(),
        )
    }

    @Test fun `backup fills the missing fuel and currency of a car without records on the phone`() = runTest {
        // Uusi puhelin: esittelyn auto ilman kirjauksia, sitten varmuuskopion palautus.
        val store = WorkLogTestStore()
        store.cars.add(CarEntity("ABC-123", FuelKind.ELECTRIC, null))
        store.cars.add(CarEntity("UUS-1", null, "SEK"))
        val content = html("""{"version":4,"exported":0,"days":[],"fuel":[],"cars":[
            {"plate":"ABC-123","fuel":"PETROL","currency":"NOK"},{"plate":"UUS-1","fuel":"GAS","currency":"EUR"}]}""")

        store.repository.importFromHtml(content)

        // Puhelimen omat tiedot säilyvät; puuttuvat tulevat varmuuskopiosta.
        assertEquals(setOf(CarEntity("ABC-123", FuelKind.ELECTRIC, "NOK"), CarEntity("UUS-1", FuelKind.GAS, "SEK")), store.cars.toSet())
    }

    @Test fun `restore after onboarding fills the car although the backup brings its records`() = runTest {
        // Oikea palautus: varmuuskopiossa on myös auton kirjaukset, puhelimessa vain esittelyn auto.
        val source = WorkLogTestStore()
        source.cars.add(CarEntity("ABC-123", FuelKind.ELECTRIC, "NOK"))
        source.fillups.add(fillup(1).copy(kind = FuelKind.ELECTRIC, currency = "NOK"))
        val phone = WorkLogTestStore()
        phone.cars.add(CarEntity("ABC-123", FuelKind.ELECTRIC, null))

        assertEquals(1, phone.repository.importFromHtml(source.repository.exportHtml(noon)).fuelAdded)

        assertEquals(listOf(CarEntity("ABC-123", FuelKind.ELECTRIC, "NOK")), phone.cars)
    }

    @Test fun `car with only work days on the phone keeps its automatic profile`() = runTest {
        val phone = WorkLogTestStore()
        phone.cars.add(CarEntity("ABC-123", null, null))
        phone.segments.add(WorkSegmentEntity(1, 1, 1_000, 2_000, 100, 200, "ABC-123"))

        phone.repository.importFromHtml(html("""{"version":4,"exported":0,"days":[],"fuel":[],"cars":[{"plate":"ABC-123","fuel":"PETROL","currency":"NOK"}]}"""))

        assertEquals(listOf(CarEntity("ABC-123", null, null)), phone.cars)
    }

    @Test fun `fully automatic car profile survives a backup roundtrip`() = runTest {
        // Kokonaan automaattinen profiili on käyttäjän valinta: palautus tuo sen, jottei myöhempi vanha
        // varmuuskopio voi asettaa autolle omaa polttoainetta tai valuuttaa.
        val source = WorkLogTestStore()
        source.cars.add(CarEntity("ABC-123", null, null))
        val destination = WorkLogTestStore()

        destination.repository.importFromHtml(source.repository.exportHtml(noon))

        assertEquals(listOf(CarEntity("ABC-123", null, null)), destination.cars)
    }

    @Test fun `v4 after a v2 restore stored in another currency does not duplicate fuel`() = runTest {
        val v2 = html("""{"version":2,"exported":0,"days":[],"fuel":[
            {"at":$noon,"plate":"ABC-123","km":100000,"liters":50.0,"cost":90.0,"price":1.8}]}""")
        val phone = WorkLogTestStore()
        phone.repository.importFromHtml(v2)
        phone.fillups[0] = phone.fillups[0].copy(currency = "SEK")
        val other = WorkLogTestStore()
        other.fillups.add(fillup(1))

        assertEquals(1, phone.repository.importFromHtml(other.repository.exportHtml(noon)).fuelSkipped)
        assertEquals(1, phone.fillups.size)
    }

    @Test fun `old backup does not duplicate a row the user marked partial or AdBlue`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.add(fillup(1).copy(fullTank = false))
        store.fillups.add(fillup(2, km = 100_000, at = noon + 60).copy(liters = 5.0, totalCost = 4.5, pricePerLiter = 0.9, kind = FuelKind.ADBLUE))
        // Vanhat v1/v2-viennit eivät kirjoittaneet versiota (oletusarvo jäi pois): tunnistus riveittäin.
        val v2 = html("""{"exported":0,"days":[],"fuel":[
            {"at":$noon,"plate":"ABC-123","km":100000,"liters":50.0,"cost":90.0,"price":1.8},
            {"at":${noon + 60},"plate":"ABC-123","km":100000,"liters":5.0,"cost":4.5,"price":0.9}]}""")

        assertEquals(2, store.repository.importFromHtml(v2).fuelSkipped)
        assertEquals(2, store.fillups.size)
    }

    @Test fun `edited fuel backup does not duplicate or overwrite existing record or poison later entry`() = runTest {
        val store = WorkLogTestStore()
        val original = fillup(1)
        store.fillups.add(original)
        val source = WorkLogTestStore()
        source.fillups.add(original.copy(odometerKm = 100_500, epochSec = noon + 1))
        val separate = fillup(2, 100_500, noon + 3600)
        source.fillups.add(separate)
        val result = store.repository.importFromHtml(source.repository.exportHtml(noon))
        assertEquals(1, result.fuelSkipped)
        assertEquals(1, result.fuelAdded)
        assertEquals(original, store.fillups.first())
        assertEquals(separate.backupId, store.fillups.last().backupId)
    }

    @Test fun `version two fuel at same noon and liters retains both readings`() = runTest {
        val store = WorkLogTestStore()
        val content = html("""{"version":2,"exported":0,"days":[],"fuel":[
            {"at":$noon,"plate":"abc 123","km":100000,"liters":50.0,"cost":90.0,"price":1.8},
            {"at":$noon,"plate":"abc 123","km":100500,"liters":50.0,"cost":90.0,"price":1.8}]}""")
        assertEquals(2, store.repository.importFromHtml(content).fuelAdded)
        assertEquals(2, store.repository.importFromHtml(content).fuelSkipped)
        assertEquals(listOf(100_000L, 100_500L), store.fillups.map { it.odometerKm })
    }

    @Test fun `version one imports optional readings without fuel or plate`() = runTest {
        val store = WorkLogTestStore()
        val content = html("""{"version":1,"exported":0,"days":[{"date":${date.toEpochDay()},"segments":[
            {"start":$noon,"end":${noon + 3600},"kmStart":100000,"kmEnd":100100}]}]}""")
        assertEquals(1, store.repository.importFromHtml(content).added)
        assertEquals(1, store.repository.importFromHtml(content).skipped)
        assertEquals(100, workDayTotals(store.segments)!!.totalKm)
        assertTrue(store.fillups.isEmpty())
        assertNull(store.segments.single().licensePlate)
    }

    @Test fun `same timestamp work segments keep their kilometres through backup`() = runTest {
        val source = WorkLogTestStore()
        source.days.add(RouteDayEntity(id = 4, dateEpochDay = date.toEpochDay()))
        source.segments.add(WorkSegmentEntity(7, 4, noon, noon + 1, 100_000, 100_200, "ABC-123"))
        source.segments.add(WorkSegmentEntity(8, 4, noon, noon + 2, 100_000, 100_200, "XYZ-789"))
        val store = WorkLogTestStore()
        val report = source.repository.exportHtml(noon)
        assertEquals(2, store.repository.importFromHtml(report).added)
        assertEquals(400, workDayTotals(store.segments)!!.totalKm)
        assertEquals(2, store.repository.importFromHtml(report).skipped)
    }

    @Test fun `legacy repeated plate completion never overwrites completed plate`() = runTest {
        val store = WorkLogTestStore()
        store.days.add(RouteDayEntity(id = 1, dateEpochDay = date.toEpochDay()))
        store.segments.add(WorkSegmentEntity(1, 1, noon, noon + 3600, 100, 200))
        val content = html("""{"version":2,"exported":0,"days":[{"date":${date.toEpochDay()},"segments":[
            {"start":$noon,"kmStart":100,"kmEnd":200,"plate":"ABC-123"},
            {"start":$noon,"kmStart":100,"kmEnd":200,"plate":"ABC-123"}]}]}""")
        assertEquals(2, store.repository.importFromHtml(content).skipped)
        assertEquals("ABC-123", store.segments.single().licensePlate)
    }

    @Test fun `fuel only month and day appear in report with no invented work time`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.add(fillup(1))
        val report = store.repository.exportHtml(noon)
        val months = report.substringAfter("<div class=\"panel\" id=\"p-months\">").substringBefore("<div class=\"panel\" id=\"p-years\">")
        val days = report.substringAfter("id=\"dayList\"").substringBefore("</ul>")
        assertTrue(months.contains("Syyskuu${nb}2026"))
        assertTrue(months.contains("0${nb}työpäivää"))
        assertTrue(months.contains("Tankattu <b class=\"nw\">50,0${nb}l</b>"))
        assertTrue(days.contains("Pe${nb}25.9.2026"))
        assertTrue(days.contains("ei työkirjausta"))
        assertFalse(days.contains("0${nb}km"))
    }

    @Test fun `HTML escapes plates in all tables and embedded JSON remains importable`() = runTest {
        val store = WorkLogTestStore()
        val malicious = "</ScRiPt><SvG/OnLoAd=&#97;&#108;&#101;&#114;&#116;(1)>&\""
        val plate = normalizePlate(malicious)!!
        store.fillups.add(fillup(1).copy(licensePlate = plate))
        store.days.add(RouteDayEntity(id = 1, dateEpochDay = date.toEpochDay()))
        store.segments.add(WorkSegmentEntity(1, 1, noon, noon + 3600, 100, 200, plate))
        val report = store.repository.exportHtml(noon)
        // Rekisterinumero ei tuo raporttiin yhtään skriptiä tai sen loppua: määrät ovat samat kuin tavallisella autolla.
        val baseline = WorkLogTestStore().apply {
            fillups.add(fillup(1))
            days.add(RouteDayEntity(id = 1, dateEpochDay = date.toEpochDay()))
            segments.add(WorkSegmentEntity(1, 1, noon, noon + 3600, 100, 200, "ABC-123"))
        }.repository.exportHtml(noon)
        val scripts = { html: String ->
            Regex("<script", RegexOption.IGNORE_CASE).findAll(html).count() to
                Regex("</script>", RegexOption.IGNORE_CASE).findAll(html).count()
        }
        assertEquals(scripts(baseline), scripts(report))
        assertFalse(report.contains("<SVG/ONLOAD", ignoreCase = true))
        assertTrue(report.contains("&lt;SVG/ONLOAD=&amp;#97;"))
        assertTrue(report.contains("\\u003c/SCRIPT>"))
        val restored = WorkLogTestStore()
        restored.repository.importFromHtml(report)
        assertEquals(plate, restored.fillups.single().licensePlate)
        assertEquals(plate, restored.segments.single().licensePlate)
    }

    @Test fun `invalid fuel aborts before importing valid work days`() = runTest {
        val store = WorkLogTestStore()
        val content = html("""{"version":2,"exported":0,"days":[{"date":${date.toEpochDay()},"segments":[{"start":$noon}]}],
            "fuel":[{"at":$noon,"plate":"ABC-123","km":100,"liters":-1.0,"cost":90.0,"price":1.8}]}""")
        assertFailsWith<IllegalArgumentException> { store.repository.importFromHtml(content) }
        assertTrue(store.days.isEmpty())
        assertTrue(store.segments.isEmpty())
        assertTrue(store.fillups.isEmpty())
    }

    @Test fun `import result carries the kinds of imported and skipped fuel records`() = runTest {
        // Tuonnin ilmoitus puhuu latauksista tai täytöistä varmuuskopion kirjausten mukaan.
        val source = WorkLogTestStore()
        source.fillups.addAll(listOf(fillup(1).copy(kind = FuelKind.GAS), fillup(2, 100_500).copy(kind = FuelKind.ELECTRIC)))
        val report = source.repository.exportHtml(noon)
        val destination = WorkLogTestStore()

        val imported = destination.repository.importFromHtml(report)
        val again = destination.repository.importFromHtml(report)

        assertEquals(2, imported.fuelAdded)
        assertEquals(setOf(FuelKind.GAS, FuelKind.ELECTRIC), imported.fuelKinds)
        assertEquals(2, again.fuelSkipped)
        assertEquals(setOf(FuelKind.GAS, FuelKind.ELECTRIC), again.fuelKinds)
        // Vanhan varmuuskopion kirjaukset ovat dieseliä; ilman kirjauksia lajeja ei ole.
        val legacy = html("""{"version":2,"exported":0,"days":[],"fuel":[{"at":$noon,"plate":"ABC-123","km":100,"liters":50.0,"cost":90.0,"price":1.8}]}""")
        assertEquals(setOf(FuelKind.DIESEL), WorkLogTestStore().repository.importFromHtml(legacy).fuelKinds)
        assertEquals(emptySet(), WorkLogTestStore().repository.importFromHtml(html("""{"version":1,"exported":0,"days":[]}""")).fuelKinds)
    }

    @Test fun `rejected backup tells the reason in the user's language`() = runTest {
        val store = WorkLogTestStore()
        suspend fun reason(file: String) = assertFailsWith<BackupRejectedException> {
            store.repository.importFromHtml(file)
        }.messageRes

        assertEquals(R.string.import_not_backup, reason("<html>ei varmuuskopio</html>"))
        assertEquals(R.string.import_unreadable, reason(html("""{"version":"x"}""")))
        assertEquals(R.string.import_version, reason(html("""{"version":99,"exported":0,"days":[]}""")))
        fun fill(kind: String) = html(
            """{"version":4,"exported":0,"days":[],"fuel":[{"at":$noon,"plate":"ABC-123","km":100,"liters":-1.0,"cost":90.0,"price":1.8,"kind":"$kind"}]}"""
        )
        assertEquals(R.string.import_bad_charge, reason(fill("ELECTRIC")))
        assertEquals(R.string.import_bad_refill, reason(fill("GAS")))
        assertEquals(R.string.import_bad_fillup, reason(fill("DIESEL")))
        assertEquals(R.string.import_bad_any_fuel, reason(fill("FUEL")))
    }

    @Test fun `invalid fuel message uses the word of the invalid row's kind`() = runTest {
        // Sähköautoa ladataan ja kaasuautoa täytetään myös virheilmoituksessa.
        val store = WorkLogTestStore()
        fun file(kind: String?) = html(
            """{"version":4,"exported":0,"days":[],"fuel":[{"at":$noon,"plate":"ABC-123","km":100,"liters":-1.0,"cost":90.0,"price":1.8""" +
                (kind?.let { ""","kind":"$it"""" } ?: "") + "}]}"
        )
        suspend fun message(kind: String?) = assertFailsWith<IllegalArgumentException> { store.repository.importFromHtml(file(kind)) }.message

        assertEquals("Varmuuskopiossa on virheellinen lataus. Tietoja ei tuotu.", message("ELECTRIC"))
        assertEquals("Varmuuskopiossa on virheellinen täyttö. Tietoja ei tuotu.", message("GAS"))
        assertEquals("Varmuuskopiossa on virheellinen tankkaus. Tietoja ei tuotu.", message("PETROL"))
        assertEquals("Varmuuskopiossa on virheellinen tankkaus. Tietoja ei tuotu.", message("ADBLUE"))
        // Vanha varmuuskopio ilman lajia on dieseltankkaus.
        assertEquals("Varmuuskopiossa on virheellinen tankkaus. Tietoja ei tuotu.", message(null))
        // Lajia ei voi lukea: neutraali muoto.
        assertEquals("Varmuuskopiossa on virheellinen tankkaus-, lataus- tai täyttökirjaus. Tietoja ei tuotu.", message("FUEL"))
        assertTrue(store.fillups.isEmpty())
    }

    @Test fun `gap never subtracts readings of different cars and records the matching car`() = runTest {
        val store = WorkLogTestStore()
        store.days.add(RouteDayEntity(id = 1, dateEpochDay = date.minusDays(2).toEpochDay()))
        store.days.add(RouteDayEntity(id = 2, dateEpochDay = date.minusDays(1).toEpochDay()))
        store.segments.add(WorkSegmentEntity(1, 1, noon - 2 * 86400, noon - 2 * 86400 + 3600, 100, 200, "ABC-123"))
        assertNull(store.repository.gapSuggestion(100_000, date.toEpochDay(), "XYZ-789"))
        val gap = store.repository.gapSuggestion(300, date.toEpochDay(), "abc123")!!
        assertEquals(100, gap.diffKm)
        store.repository.recordGap(gap.perDay, gap.previousKm, gap.licensePlate)
        assertEquals("ABC-123", store.segments.last().licensePlate)
        assertEquals(300, store.segments.last().odometerEndKm)
    }

    @Test fun `v3 backup after v2 restore does not duplicate segments or fuel`() = runTest {
        val source = WorkLogTestStore()
        source.days.add(RouteDayEntity(id = 4, dateEpochDay = date.toEpochDay()))
        source.segments.add(WorkSegmentEntity(7, 4, noon, noon + 3600, 100_000, 100_200, "ABC-123"))
        source.fillups.add(fillup(20))
        val v3 = source.repository.exportHtml(noon)
        // Sama sisältö ilman tunnisteita = vanhan version tiedosto.
        val v2 = v3.replace(Regex(",\"backupId\":\"[^\"]*\""), "").replace("\"version\":3", "\"version\":2")
        assertFalse(v2.contains("backupId"))
        val store = WorkLogTestStore()
        val first = store.repository.importFromHtml(v2)
        assertEquals(1, first.added)
        assertEquals(1, first.fuelAdded)
        val second = store.repository.importFromHtml(v3)
        assertEquals(0, second.added)
        assertEquals(0, second.fuelAdded)
        assertEquals(1, store.segments.size)
        assertEquals(1, store.fillups.size)
    }

    @Test fun `v2 segment with corrected plate is still a duplicate`() = runTest {
        val store = WorkLogTestStore()
        store.days.add(RouteDayEntity(id = 4, dateEpochDay = date.toEpochDay()))
        store.segments.add(WorkSegmentEntity(7, 4, noon, noon + 3600, 100_000, 100_200, "ABC-123"))
        val content = html("""{"version":2,"exported":0,"days":[{"date":${date.toEpochDay()},"segments":[
            {"start":$noon,"end":${noon + 3600},"kmStart":100000,"kmEnd":100200,"plate":"ABC-132"}]}]}""")
        assertEquals(1, store.repository.importFromHtml(content).skipped)
        assertEquals("ABC-123", store.segments.single().licensePlate)
        assertEquals(1, store.segments.size)
    }
}
