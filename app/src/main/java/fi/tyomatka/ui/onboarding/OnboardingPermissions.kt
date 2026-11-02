package fi.tyomatka.ui.onboarding

import android.Manifest
import android.os.Build

enum class PermissionCardKey { LOCATION, NOTIFICATIONS }

/** Yksi esittelyn lupakortti: kortti koskee laitetta vain, jos sen API-taso on vähintään [minSdk]. */
data class PermissionCard(val key: PermissionCardKey, val permissions: List<String>, val minSdk: Int)

object OnboardingPermissions {
    val cards: List<PermissionCard> = listOf(
        PermissionCard(
            PermissionCardKey.LOCATION,
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            minSdk = Build.VERSION_CODES.BASE,
        ),
        PermissionCard(
            PermissionCardKey.NOTIFICATIONS,
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            minSdk = Build.VERSION_CODES.TIRAMISU,
        ),
    )

    fun applicable(sdkInt: Int): List<PermissionCard> = cards.filter { sdkInt >= it.minSdk }

    /** Sijaintikortille riittää karkea lupa: tarkka ja karkea pyydetään yhdessä, käyttäjä voi valita vain karkean. */
    fun granted(card: PermissionCard, sdkInt: Int, granted: Set<String>): Boolean =
        sdkInt < card.minSdk || card.permissions.any { it in granted }

    fun toRequest(sdkInt: Int, granted: Set<String>): List<String> =
        applicable(sdkInt).filterNot { granted(it, sdkInt, granted) }.flatMap { it.permissions }
}
