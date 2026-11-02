package fi.tyomatka.di

import fi.tyomatka.BuildConfig
import fi.tyomatka.core.api.Defaults
import fi.tyomatka.data.repo.ServerKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerDefaultsTest {
    @Test
    fun `jokaisella palvelimella on oletus`() {
        assertEquals(ServerKind.entries.toSet(), defaultServerMap().keys)
    }

    @Test
    fun `kohdepalvelimen oletus tulee kehitysasetuksesta tai oletuksesta`() {
        assertEquals(BuildConfig.POI_BASE_URL.ifBlank { Defaults.POI_URL }, defaultServers().poi)
    }

    @Test
    fun `reititys ja kartta ovat oletuspalvelimilla`() {
        assertEquals(Defaults.VALHALLA_URL, defaultServers().valhalla)
        assertEquals(Defaults.OSRM_URL, defaultServers().osrm)
        assertEquals(Defaults.TILE_STYLE_LIGHT, defaultServers().mapStyle)
        assertEquals(Defaults.PHOTON_URL, defaultServers().photon)
    }
}
