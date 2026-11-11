package fi.tyomatka.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.tyomatka.core.nav.RouteChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteChoiceStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dataStore(scope: CoroutineScope) = PreferenceDataStoreFactory.create(scope = scope) {
        tmp.newFolder().resolve("prefs.preferences_pb")
    }

    @Test
    fun `oletus on nopein ja lyhin sailyy saman paivan`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = dataStore(scope)
        val days = MutableStateFlow(20_403L)
        val store = RouteChoiceStore(dataStore, days)

        assertEquals(RouteChoice.NORMAL, store.choice.first())
        store.set(RouteChoice.SHORTEST)
        assertEquals(RouteChoice.SHORTEST, store.choice.first())
        assertEquals(RouteChoice.SHORTEST, RouteChoiceStore(dataStore, days).choice.first())
        scope.cancel()
    }

    @Test
    fun `lyhin palaa nopeimmaksi paivan vaihtuessa`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val days = MutableStateFlow(20_403L)
        val store = RouteChoiceStore(dataStore(scope), days)

        store.set(RouteChoice.SHORTEST)
        days.value = 20_404L
        assertEquals(RouteChoice.NORMAL, store.choice.first())
        scope.cancel()
    }

    @Test
    fun `auki oleva nakyma saa nopeimman keskiyolla`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val days = MutableStateFlow(20_403L)
        val store = RouteChoiceStore(dataStore(scope), days)
        val seen = java.util.Collections.synchronizedList(mutableListOf<RouteChoice>())
        val collector = scope.launch { store.choice.collect { seen += it } }
        suspend fun awaitLast(expected: RouteChoice) = withTimeout(5_000) {
            while (seen.lastOrNull() != expected) delay(10)
        }

        awaitLast(RouteChoice.NORMAL)
        store.set(RouteChoice.SHORTEST)
        awaitLast(RouteChoice.SHORTEST)
        days.value = 20_404L
        awaitLast(RouteChoice.NORMAL)
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `ilman valintapaivaa tallennettu lyhin on nopein`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = dataStore(scope)
        dataStore.edit { it[stringPreferencesKey("route_choice")] = "shortest" }

        assertEquals(RouteChoice.NORMAL, RouteChoiceStore(dataStore, MutableStateFlow(20_403L)).choice.first())
        scope.cancel()
    }

    @Test
    fun `tuntematon tallennettu arvo on nopein`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = dataStore(scope)
        dataStore.edit { it[stringPreferencesKey("route_choice")] = "tuntematon" }

        assertEquals(RouteChoice.NORMAL, RouteChoiceStore(dataStore, MutableStateFlow(20_403L)).choice.first())
        scope.cancel()
    }
}
