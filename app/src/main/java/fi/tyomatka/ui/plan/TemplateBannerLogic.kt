package fi.tyomatka.ui.plan

import fi.tyomatka.core.plan.ServiceStops
import fi.tyomatka.core.plan.TemplateChange
import fi.tyomatka.core.plan.TemplateDiff
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.db.TemplateStopEntity
import fi.tyomatka.data.repo.toTemplateData

/** Suunnittelu-välilehden muutosrivi: avattu pohja eroaa päivän asiakasstopeista. */
data class TemplateBanner(
    val templateId: Long,
    val templateName: String,
    val change: TemplateChange,
    /** Päivän asiakasstoppien avain; Myöhemmin tallentaa sen kuittaukseksi. */
    val ackKey: String,
    /** Hylkää muutokset vain, kun päivän ajo ei ole alkanut (ei ARRIVED-, DONE- eikä SKIPPED-stoppeja). */
    val canDiscard: Boolean,
)

object TemplateBannerLogic {
    /**
     * Rivi näytetään vain, kun päivä on avattu pohjasta, pohja on yhä olemassa, ajo ei ole käynnissä, asiakasstopit
     * eroavat pohjasta eikä juuri tätä eroa ole kuitattu Myöhemmin-napilla.
     */
    fun banner(
        day: RouteDayEntity,
        dayStops: List<StopEntity>,
        template: RouteTemplateEntity?,
        templateStops: List<TemplateStopEntity>,
        driving: Boolean,
    ): TemplateBanner? {
        val templateId = day.templateId ?: return null
        if (template == null || template.id != templateId || driving) return null
        val customer = dayStops.filterNot { ServiceStops.isServiceStop(it.placeId) }.sortedBy { it.orderIndex }
        val change = TemplateDiff.compare(templateStops.sortedBy { it.orderIndex }.map { it.toTemplateData() }, customer.map { it.toTemplateData() })
        if (!change.isChanged) return null
        val ackKey = TemplateDiff.ackKey(customer.map { it.toTemplateData() })
        if (ackKey == day.templateAck) return null
        val started = dayStops.any { it.status == StopStatus.ARRIVED || it.status == StopStatus.DONE || it.status == StopStatus.SKIPPED }
        return TemplateBanner(templateId, template.name, change, ackKey, canDiscard = !started)
    }
}
