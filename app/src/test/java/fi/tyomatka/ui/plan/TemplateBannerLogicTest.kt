package fi.tyomatka.ui.plan

import fi.tyomatka.core.plan.TemplateDiff
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.db.TemplateStopEntity
import fi.tyomatka.data.repo.toTemplateData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Muutosrivi näkyy vain, kun avattu pohja ja päivän asiakasstopit eroavat eikä eroa ole kuitattu (määrittely, osa A). */
class TemplateBannerLogicTest {
    private val template = RouteTemplateEntity(id = 7, name = "Vantaa-kierros", createdEpochSec = 1, updatedEpochSec = 1)
    private val templateStops = listOf(
        TemplateStopEntity(id = 1, templateId = 7, orderIndex = 0, label = "Örum", address = "Läntinen teollisuuskatu 2, Espoo", lat = 60.2, lng = 24.6),
        TemplateStopEntity(id = 2, templateId = 7, orderIndex = 1, label = "Varasto", address = "Tikkurilantie 10, Vantaa", lat = 60.3, lng = 25.0),
    )
    private fun day(templateId: Long? = 7, ack: String? = null) = RouteDayEntity(id = 1, dateEpochDay = 20_750, templateId = templateId, templateAck = ack)
    private fun dayStops(vararg extra: StopEntity, status: StopStatus = StopStatus.PENDING): List<StopEntity> =
        templateStops.map {
            StopEntity(id = it.id, routeDayId = 1, label = it.label, address = it.address, lat = it.lat, lng = it.lng, orderIndex = it.orderIndex, status = status)
        } + extra
    private val added = StopEntity(id = 9, routeDayId = 1, label = "Kerava", address = "Esimerkkitie 5, Kerava", lat = 60.4, lng = 25.1, orderIndex = 2)
    private val serviceStop = added.copy(id = 10, label = "Neste Truck", address = "Esimerkkikaari 2, Vantaa", placeId = "service:osm:n1")

    @Test
    fun `ilman pohjaviitetta ei rivia`() {
        assertNull(TemplateBannerLogic.banner(day(templateId = null), dayStops(added), null, emptyList(), driving = false))
    }

    @Test
    fun `poistettu pohja ei nayta rivia`() {
        assertNull(TemplateBannerLogic.banner(day(), dayStops(added), null, emptyList(), driving = false))
    }

    @Test
    fun `ajon aikana ei rivia`() {
        assertNull(TemplateBannerLogic.banner(day(), dayStops(added), template, templateStops, driving = true))
    }

    @Test
    fun `paiva samanlainen kuin pohja ei nayta rivia`() {
        assertNull(TemplateBannerLogic.banner(day(), dayStops(), template, templateStops, driving = false))
    }

    @Test
    fun `lisatty stoppi nayttaa rivin ja sallii hylkaamisen`() {
        val banner = assertNotNull(TemplateBannerLogic.banner(day(), dayStops(added), template, templateStops, driving = false))
        assertEquals(7, banner.templateId)
        assertEquals("Vantaa-kierros", banner.templateName)
        assertEquals(1, banner.change.added)
        assertTrue(banner.canDiscard)
        assertEquals(TemplateDiff.ackKey(dayStops(added).map { it.toTemplateData() }), banner.ackKey)
    }

    @Test
    fun `pelkka huoltostoppi ei nayta rivia`() {
        assertNull(TemplateBannerLogic.banner(day(), dayStops(serviceStop), template, templateStops, driving = false))
    }

    @Test
    fun `kuitattu muutos pysyy piilossa kunnes lista muuttuu taas`() {
        val ack = TemplateDiff.ackKey(dayStops(added).map { it.toTemplateData() })
        assertNull(TemplateBannerLogic.banner(day(ack = ack), dayStops(added), template, templateStops, driving = false))
        val more = dayStops(added, added.copy(id = 11, label = "Järvenpää", address = "Sibeliuksenkatu 1, Järvenpää", lat = 60.47, lng = 25.09, orderIndex = 3))
        assertNotNull(TemplateBannerLogic.banner(day(ack = ack), more, template, templateStops, driving = false))
    }

    @Test
    fun `valmis stoppi estaa hylkaamisen`() {
        val stops = dayStops(added, status = StopStatus.DONE)
        val banner = assertNotNull(TemplateBannerLogic.banner(day(), stops, template, templateStops, driving = false))
        assertFalse(banner.canDiscard)
    }
}
