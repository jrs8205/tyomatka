package fi.tyomatka.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsStoreTest {
    @Test
    fun `oletukset ovat nykyinen käytös`() = runBlocking {
        val settings = AppSettingsStore(MemoryDataStore()).settings.first()

        assertNull(settings.voiceName)
        assertTrue(settings.guidanceVibration)
        assertFalse(settings.hideBottomBarDriving)
        assertTrue(settings.dimInDark)
        assertTrue(settings.tapVibration)
    }

    @Test
    fun `muutokset säilyvät eivätkä hukkaa toisiaan`() = runBlocking {
        val store = AppSettingsStore(MemoryDataStore())

        store.update { it.copy(voiceName = "fi-1", guidanceVibration = false) }
        store.update { it.copy(hideBottomBarDriving = true, dimInDark = false, tapVibration = false) }

        assertEquals(
            AppSettings(voiceName = "fi-1", guidanceVibration = false, hideBottomBarDriving = true, dimInDark = false, tapVibration = false),
            store.settings.first(),
        )
    }

    @Test
    fun `äänen voi palauttaa oletukseen`() = runBlocking {
        val store = AppSettingsStore(MemoryDataStore())

        store.update { it.copy(voiceName = "fi-1") }
        store.update { it.copy(voiceName = null) }

        assertNull(store.settings.first().voiceName)
    }
}
