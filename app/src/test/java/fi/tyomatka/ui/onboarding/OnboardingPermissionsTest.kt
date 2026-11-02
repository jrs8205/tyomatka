package fi.tyomatka.ui.onboarding

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingPermissionsTest {

    @Test
    fun `api 26 pyytää vain sijainnin`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            OnboardingPermissions.toRequest(sdkInt = 26, granted = emptySet()),
        )
    }

    @Test
    fun `api 33 pyytää sijainnin ja ilmoitukset`() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            OnboardingPermissions.toRequest(sdkInt = 33, granted = emptySet()),
        )
    }

    @Test
    fun `karkea sijainti riittää sijaintikortin myöntämiseen`() {
        val card = OnboardingPermissions.cards.first { it.key == PermissionCardKey.LOCATION }
        assertTrue(OnboardingPermissions.granted(card, 33, setOf(Manifest.permission.ACCESS_COARSE_LOCATION)))
        assertFalse(OnboardingPermissions.granted(card, 33, emptySet()))
    }

    @Test
    fun `kortti alle minSdk-tason on aina myönnetty`() {
        val notifications = OnboardingPermissions.cards.first { it.key == PermissionCardKey.NOTIFICATIONS }
        assertTrue(OnboardingPermissions.granted(notifications, 32, emptySet()))
    }

    @Test
    fun `kaikki myönnetty antaa tyhjän pyyntölistan`() {
        val all = OnboardingPermissions.cards.flatMap { it.permissions }.toSet()
        assertEquals(emptyList(), OnboardingPermissions.toRequest(36, all))
    }

    @Test
    fun `applicable jättää pois kortit joita api-taso ei tunne`() {
        assertEquals(
            listOf(PermissionCardKey.LOCATION),
            OnboardingPermissions.applicable(30).map { it.key },
        )
    }
}
