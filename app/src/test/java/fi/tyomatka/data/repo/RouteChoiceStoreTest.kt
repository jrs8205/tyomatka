package fi.tyomatka.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.tyomatka.core.nav.RouteChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteChoiceStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `oletus on nopein ja valinta säilyy`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFolder().resolve("prefs.preferences_pb")
        }
        val store = RouteChoiceStore(dataStore)

        assertEquals(RouteChoice.NORMAL, store.choice.first())
        store.set(RouteChoice.SHORTEST)
        assertEquals(RouteChoice.SHORTEST, store.choice.first())
        assertEquals(RouteChoice.SHORTEST, RouteChoiceStore(dataStore).choice.first())
        scope.cancel()
    }

    @Test
    fun `tuntematon tallennettu arvo on nopein`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFolder().resolve("prefs.preferences_pb")
        }
        dataStore.edit { it[stringPreferencesKey("route_choice")] = "tuntematon" }

        assertEquals(RouteChoice.NORMAL, RouteChoiceStore(dataStore).choice.first())
        scope.cancel()
    }
}
