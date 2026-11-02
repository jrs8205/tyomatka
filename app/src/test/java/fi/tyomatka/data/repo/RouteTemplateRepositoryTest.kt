package fi.tyomatka.data.repo

import fi.tyomatka.core.plan.TemplateDiff
import fi.tyomatka.data.db.MatrixCacheEntity
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.db.TemplateStopEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tallennettujen reittien kirjoitukset. */
class RouteTemplateRepositoryTest {
    private val dayId = 1L

    private fun store(): RouteTemplateTestStore = RouteTemplateTestStore().apply {
        days.add(RouteDayEntity(id = dayId, dateEpochDay = 20_750))
    }

    private fun RouteTemplateTestStore.stop(
        order: Int,
        label: String,
        address: String = "$label 1, Vantaa",
        placeId: String? = null,
        status: StopStatus = StopStatus.PENDING,
        deadline: Int? = null,
        service: Long? = null,
        day: Long = dayId,
    ): StopEntity {
        val id = (stops.maxOfOrNull { it.id } ?: 0) + 1
        val entity = StopEntity(
            id = id, routeDayId = day, label = label, address = address, placeId = placeId,
            lat = 60.0 + order, lng = 25.0 + order, orderIndex = order, status = status,
            deadlineMinutesOfDay = deadline, serviceSec = service,
            arrivedAtEpochSec = if (status == StopStatus.DONE) 500 else null,
        )
        stops.add(entity)
        return entity
    }

    private fun RouteTemplateTestStore.template(name: String, vararg labels: String): Long {
        val id = (templates.maxOfOrNull { it.id } ?: 0) + 1
        templates.add(RouteTemplateEntity(id = id, name = name, createdEpochSec = 10, updatedEpochSec = 10))
        labels.forEachIndexed { i, label ->
            templateStops.add(
                TemplateStopEntity(
                    id = (templateStops.maxOfOrNull { it.id } ?: 0) + 1, templateId = id, orderIndex = i,
                    label = label, address = "$label 1, Vantaa", lat = 60.0 + i, lng = 25.0 + i,
                    deadlineMinutesOfDay = if (i == 0) 900 else null, serviceSec = if (i == 1) 600L else null,
                ),
            )
        }
        return id
    }

    @Test
    fun `saveNew tallentaa asiakasstopit jarjestyksessa ilman huoltostoppeja ja viittaa paivan pohjaan`() = runTest {
        val s = store()
        s.stop(0, "Örum", deadline = 900)
        s.stop(1, "Varasto", service = 600)
        s.stop(2, "Neste Truck", placeId = "service:osm:n1")
        s.stop(3, "Kerava")

        val id = s.repository.saveNew(dayId, "  Vantaa   kierros ")

        assertEquals("Vantaa kierros", s.templates.single().name)
        assertEquals(1_000, s.templates.single().createdEpochSec)
        assertEquals(listOf("Örum", "Varasto", "Kerava"), s.templateDao.stopsFor(id).map { it.label })
        assertEquals(listOf(0, 1, 2), s.templateDao.stopsFor(id).map { it.orderIndex })
        assertEquals(900, s.templateDao.stopsFor(id)[0].deadlineMinutesOfDay)
        assertEquals(600L, s.templateDao.stopsFor(id)[1].serviceSec)
        assertEquals(id, s.days.single().templateId)
        assertNull(s.days.single().templateAck)
    }

    @Test
    fun `tyhja nimi ei kelpaa`() = runTest {
        val s = store()
        s.stop(0, "Örum")
        assertFailsWith<IllegalArgumentException> { s.repository.saveNew(dayId, "   ") }
        assertEquals(0, s.templates.size)
    }

    @Test
    fun `overwrite korvaa pohjan stopit ja paivittaa muutosajan`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Vanha A", "Vanha B")
        s.stop(0, "Uusi A")
        s.stop(1, "Neste Truck", placeId = "service:osm:n1")
        s.dayDao.setTemplateAck(dayId, "vanha")

        s.repository.overwrite(id, dayId)

        assertEquals(listOf("Uusi A"), s.templateDao.stopsFor(id).map { it.label })
        assertEquals(1_000, s.templates.single().updatedEpochSec)
        assertEquals(id, s.days.single().templateId)
        assertNull(s.days.single().templateAck)
    }

    @Test
    fun `open REPLACE korvaa paivan stopit pohjan stopeilla`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum", "Varasto")
        s.stop(0, "Vanha", status = StopStatus.DONE)
        s.matrixCache.add(MatrixCacheEntity(routeDayId = dayId, json = "{}", fetchedAtEpochSec = 0))

        s.repository.open(id, dayId, OpenMode.REPLACE)

        val day = s.stopDao.forDay(dayId)
        assertEquals(listOf("Örum", "Varasto"), day.map { it.label })
        assertEquals(listOf(0, 1), day.map { it.orderIndex })
        assertEquals(listOf(StopStatus.PENDING, StopStatus.PENDING), day.map { it.status })
        assertEquals(listOf(900, null), day.map { it.deadlineMinutesOfDay })
        assertEquals(listOf(null, 600L), day.map { it.serviceSec })
        assertEquals(0, s.matrixCache.size)
        assertEquals(id, s.days.single().templateId)
        assertEquals(1_000, s.templates.single().lastOpenedEpochSec)
    }

    @Test
    fun `open REPLACE tyhjaan paivaan toimii samoin`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum")
        s.repository.open(id, dayId, OpenMode.REPLACE)
        assertEquals(listOf("Örum"), s.stopDao.forDay(dayId).map { it.label })
        assertEquals(id, s.days.single().templateId)
    }

    @Test
    fun `open APPEND lisaa pohjan stopit peraan eika muuta viitetta`() = runTest {
        val s = store()
        val other = s.template("Toinen", "X")
        val id = s.template("Vantaa-kierros", "Örum", "Varasto")
        s.stop(0, "Vanha A")
        s.stop(1, "Vanha B")
        s.dayDao.setTemplate(dayId, other)

        s.repository.open(id, dayId, OpenMode.APPEND)

        assertEquals(listOf("Vanha A", "Vanha B", "Örum", "Varasto"), s.stopDao.forDay(dayId).map { it.label })
        assertEquals(listOf(0, 1, 2, 3), s.stopDao.forDay(dayId).map { it.orderIndex })
        assertEquals(other, s.days.single().templateId)
        assertEquals(1_000, s.templates.first { it.id == id }.lastOpenedEpochSec)
    }

    @Test
    fun `open APPEND ohittaa paivan hoitamattomalla listalla jo olevat osoitteet`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum", "Varasto")
        // Sama osoite ja sijainti kuin pohjan ensimmäisellä stopilla; hoidettu stoppi ei estä lisäystä (kuten addStop).
        s.stop(0, "Örum")
        s.stop(1, "Hoidettu", address = "Varasto 1, Vantaa", status = StopStatus.DONE)

        val skipped = s.repository.open(id, dayId, OpenMode.APPEND)

        assertEquals(1, skipped)
        assertEquals(listOf("Örum", "Hoidettu", "Varasto"), s.stopDao.forDay(dayId).map { it.label })
        assertEquals(listOf(0, 1, 2), s.stopDao.forDay(dayId).map { it.orderIndex })
    }

    @Test
    fun `open odottaa stoppien yhteista kirjoituslukkoa`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum")
        s.lock.mutex.lock()
        val job = launch { s.repository.open(id, dayId, OpenMode.REPLACE) }
        runCurrent()
        assertTrue(s.stopDao.forDay(dayId).isEmpty())

        s.lock.mutex.unlock()
        job.join()

        assertEquals(listOf("Örum"), s.stopDao.forDay(dayId).map { it.label })
    }

    @Test
    fun `open ja discardChanges kirjoittavat stopit transaktion sisalla`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum", "Varasto")
        s.stop(0, "Vanha")

        s.repository.open(id, dayId, OpenMode.REPLACE)
        s.stop(2, "Lisätty")
        s.repository.discardChanges(dayId)

        assertEquals(2, s.transactions)
        assertEquals(0, s.writesOutsideTransaction)
        assertEquals(listOf("Örum", "Varasto"), s.stopDao.forDay(dayId).map { it.label })
    }

    @Test
    fun `open APPEND ilman pohjaa jattaa viitteen tyhjaksi`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum")
        s.stop(0, "Vanha A")
        s.repository.open(id, dayId, OpenMode.APPEND)
        assertNull(s.days.single().templateId)
        assertEquals(2, s.stopDao.forDay(dayId).size)
    }

    @Test
    fun `delete poistaa pohjan ja nollaa paivan viitteen`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum")
        s.dayDao.setTemplate(dayId, id)
        s.dayDao.setTemplateAck(dayId, "abc")

        s.repository.delete(id)

        assertEquals(0, s.templates.size)
        assertNull(s.days.single().templateId)
        assertNull(s.days.single().templateAck)
    }

    @Test
    fun `rename normalisoi nimen`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum")
        s.repository.rename(id, "  Lahti  –  Heinola ")
        assertEquals("Lahti – Heinola", s.templates.single().name)
        assertEquals(1_000, s.templates.single().updatedEpochSec)
        assertFailsWith<IllegalArgumentException> { s.repository.rename(id, " ") }
    }

    @Test
    fun `discardChanges palauttaa asiakasstopit pohjan mukaisiksi ja sailyttaa huoltostopit viimeisina`() = runTest {
        val s = store()
        val id = s.template("Vantaa-kierros", "Örum", "Varasto")
        s.dayDao.setTemplate(dayId, id)
        s.stop(0, "Neste Truck", placeId = "service:osm:n1")
        s.stop(1, "Varasto")
        s.stop(2, "Lisätty")
        s.stop(3, "ABC Keimola", placeId = "service:osm:w2")
        s.dayDao.setTemplateAck(dayId, "abc")
        s.matrixCache.add(MatrixCacheEntity(routeDayId = dayId, json = "{}", fetchedAtEpochSec = 0))

        s.repository.discardChanges(dayId)

        val day = s.stopDao.forDay(dayId)
        assertEquals(listOf("Örum", "Varasto", "Neste Truck", "ABC Keimola"), day.map { it.label })
        assertEquals(listOf(0, 1, 2, 3), day.map { it.orderIndex })
        assertEquals(id, s.days.single().templateId)
        assertNull(s.days.single().templateAck)
        assertEquals(0, s.matrixCache.size)
    }

    @Test
    fun `acknowledge tallentaa kuittausavaimen`() = runTest {
        val s = store()
        val key = TemplateDiff.ackKey(emptyList())
        s.repository.acknowledge(dayId, key)
        assertEquals(key, s.days.single().templateAck)
    }

    @Test
    fun `toTemplateData ottaa vertailukentat`() {
        val stop = StopEntity(id = 5, routeDayId = 1, label = "Örum", address = "Läntinen teollisuuskatu 2", lat = 60.2, lng = 24.6, orderIndex = 0, deadlineMinutesOfDay = 900, serviceSec = 600)
        val data = stop.toTemplateData()
        assertEquals("Örum", data.label)
        assertEquals(900, data.deadlineMinutesOfDay)
        assertEquals(600L, data.serviceSec)
        val template = TemplateStopEntity(templateId = 1, orderIndex = 0, label = "Örum", address = "Läntinen teollisuuskatu 2", lat = 60.2, lng = 24.6, deadlineMinutesOfDay = 900, serviceSec = 600)
        assertEquals(data, template.toTemplateData())
    }
}
