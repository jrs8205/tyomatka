package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.tyomatka.core.learn.HistoryLearner
import fi.tyomatka.data.db.HistoryDao
import fi.tyomatka.data.db.LegRecordEntity
import fi.tyomatka.data.db.ServiceRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryRepositoryTest {

    private val dao = FakeHistoryDao()

    private fun TestScope.repository(storage: MemoryPreferencesStorage = MemoryPreferencesStorage()): HistoryRepository {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(storage = storage, scope = backgroundScope)
        return HistoryRepository(dao, dataStore, HistoryLearner())
    }

    @Test
    fun `legi opettaa navigoinnin ja suunnitelman kertoimet omista ennusteistaan`() = runTest {
        val repo = repository()

        repo.recordLeg(dayId = 1, toStopId = 7, navPredictedSec = 600, planPredictedSec = 500, actualSec = 720, nowEpochSec = 100)

        val stats = repo.currentStats()
        assertEquals(1.2, stats.navSpeedFactor, 0.001)
        assertEquals(1.44, stats.speedFactor, 0.001)
        assertEquals(listOf(600L), dao.legs.map { it.predictedSec }, "kantaan navigoinnin ennuste")
    }

    @Test
    fun `ilman suunnitelman ennustetta suunnitelman kerroin ei muutu`() = runTest {
        val repo = repository()

        repo.recordLeg(dayId = 1, toStopId = 7, navPredictedSec = 600, planPredictedSec = null, actualSec = 720, nowEpochSec = 100)

        val stats = repo.currentStats()
        assertEquals(1.2, stats.navSpeedFactor, 0.001)
        assertEquals(1.0, stats.speedFactor, 0.0)
        assertEquals(0, stats.legSamples)
    }

    @Test
    fun `googlen ennusteista opittu kerroin nollataan kerran mutta palveluaika sailyy`() = runTest {
        val storage = MemoryPreferencesStorage().apply {
            value = mutablePreferencesOf(
                doublePreferencesKey("speed_factor") to 1.8,
                intPreferencesKey("leg_samples") to 12,
                longPreferencesKey("avg_service_sec") to 240L,
                intPreferencesKey("service_samples") to 30,
            )
        }
        val repo = repository(storage)

        val stats = repo.currentStats()

        assertEquals(1.0, stats.speedFactor, 0.0)
        assertEquals(1.0, stats.navSpeedFactor, 0.0)
        assertEquals(0, stats.legSamples)
        assertEquals(240L, stats.avgServiceSec)
        assertEquals(30, stats.serviceSamples)
        assertEquals("osm-1", storage.value[stringPreferencesKey("learning_source")])
    }

    @Test
    fun `nollauksen jalkeen opittu kerroin sailyy`() = runTest {
        val storage = MemoryPreferencesStorage().apply {
            value = mutablePreferencesOf(
                doublePreferencesKey("speed_factor") to 1.3,
                doublePreferencesKey("nav_speed_factor") to 1.1,
                stringPreferencesKey("learning_source") to "osm-1",
            )
        }
        val repo = repository(storage)

        val stats = repo.currentStats()

        assertEquals(1.3, stats.speedFactor, 0.0)
        assertEquals(1.1, stats.navSpeedFactor, 0.0)
    }

    @Test
    fun `nollauksen kirjoitusvirhe ei esta tilastojen lukua`() = runTest {
        val storage = MemoryPreferencesStorage(writeError = IOException("levy täynnä")).apply {
            value = mutablePreferencesOf(doublePreferencesKey("speed_factor") to 1.8)
        }
        val repo = repository(storage)

        val stats = repo.currentStats()

        assertEquals(1.0, stats.speedFactor, 0.0)
        assertNull(storage.value[stringPreferencesKey("learning_source")], "nollaus jäi tekemättä")
    }

    @Test
    fun `tilastovirta ei nayta vanhaa kerrointa ennen nollausta`() = runTest {
        val storage = MemoryPreferencesStorage().apply {
            value = mutablePreferencesOf(doublePreferencesKey("speed_factor") to 1.8)
        }
        val repo = repository(storage)

        assertEquals(1.0, repo.learnedStats.first().speedFactor, 0.0)
    }
}

private class FakeHistoryDao : HistoryDao {
    val legs = mutableListOf<LegRecordEntity>()
    val services = mutableListOf<ServiceRecordEntity>()

    override suspend fun insertLeg(record: LegRecordEntity) {
        legs += record
    }

    override suspend fun insertService(record: ServiceRecordEntity) {
        services += record
    }

    override suspend fun legsForDay(dayId: Long) = legs.filter { it.routeDayId == dayId }

    override suspend fun servicesForDay(dayId: Long) = services.filter { it.routeDayId == dayId }

    override suspend fun avgServiceSec(): Double? = services.map { it.durationSec }.average().takeIf { services.isNotEmpty() }

    override suspend fun deleteLegsForDay(dayId: Long) {
        legs.removeAll { it.routeDayId == dayId }
    }

    override suspend fun deleteServicesForDay(dayId: Long) {
        services.removeAll { it.routeDayId == dayId }
    }
}
