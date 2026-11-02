package fi.tyomatka.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `esittely on tekemättä kunnes se merkitään tehdyksi`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFolder().resolve("prefs.preferences_pb")
        }
        val store = OnboardingStore(dataStore)

        assertFalse(store.isDone.first())
        store.markDone()
        assertTrue(store.isDone.first())
        scope.cancel()
    }
}
