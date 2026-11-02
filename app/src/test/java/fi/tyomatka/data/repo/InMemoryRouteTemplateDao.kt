package fi.tyomatka.data.repo

import fi.tyomatka.data.db.RouteTemplateDao
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.TemplateListItem
import fi.tyomatka.data.db.TemplateStopEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Muistinvarainen RouteTemplateDao testistoreille: pohjat ja niiden stopit annetuissa listoissa. */
internal class InMemoryRouteTemplateDao(
    private val templates: MutableList<RouteTemplateEntity>,
    private val templateStops: MutableList<TemplateStopEntity>,
) : RouteTemplateDao {
    override suspend fun insert(template: RouteTemplateEntity): Long {
        val id = (templates.maxOfOrNull { it.id } ?: 0) + 1
        templates.add(template.copy(id = id))
        return id
    }
    override suspend fun insertStops(stops: List<TemplateStopEntity>) {
        stops.forEach { s ->
            val id = (templateStops.maxOfOrNull { it.id } ?: 0) + 1
            templateStops.add(s.copy(id = id))
        }
    }
    override fun observeAll(): Flow<List<TemplateListItem>> =
        flowOf(templates.sortedBy { it.name.lowercase() }.map { t -> TemplateListItem(t, templateStops.count { it.templateId == t.id }) })
    override suspend fun all() = templates.sortedBy { it.name.lowercase() }
    override suspend fun byId(id: Long) = templates.firstOrNull { it.id == id }
    override fun observeById(id: Long) = flowOf(templates.firstOrNull { it.id == id })
    override suspend fun byName(name: String) = templates.firstOrNull { it.name == name }
    override suspend fun stopsFor(templateId: Long) = templateStops.filter { it.templateId == templateId }.sortedBy { it.orderIndex }
    override fun observeStops(templateId: Long) = flowOf(templateStops.filter { it.templateId == templateId }.sortedBy { it.orderIndex })
    override suspend fun deleteStops(templateId: Long) { templateStops.removeAll { it.templateId == templateId } }
    override suspend fun rename(id: Long, name: String, now: Long) {
        val at = templates.indexOfFirst { it.id == id }
        if (at >= 0) templates[at] = templates[at].copy(name = name, updatedEpochSec = now)
    }
    override suspend fun touch(id: Long, now: Long) {
        val at = templates.indexOfFirst { it.id == id }
        if (at >= 0) templates[at] = templates[at].copy(updatedEpochSec = now)
    }
    override suspend fun setLastOpened(id: Long, now: Long) {
        val at = templates.indexOfFirst { it.id == id }
        if (at >= 0) templates[at] = templates[at].copy(lastOpenedEpochSec = now)
    }
    override suspend fun delete(id: Long) {
        templates.removeAll { it.id == id }
        templateStops.removeAll { it.templateId == id }
    }
}
