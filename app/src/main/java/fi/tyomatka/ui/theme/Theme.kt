package fi.tyomatka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Kiinteät värit, jotka täyttävät WCAG AAA -kontrastin (teksti ≥ 7:1, reunat ja
 * muut käyttöliittymän osat ≥ 4,5:1) sekä vaaleassa että tummassa teemassa.
 * Pintasävyt (surfaceContainer*, inverse*) on kiinnitetty, jotta kontrasti ei
 * riipu kirjaston laskemista oletuksista; parit tarkistaa ThemeContrastTest.
 * Värillisillä korteilla (primaryContainer) teksti on aina onPrimaryContainer.
 * Laitteen dynaamista väriä ei käytetä, koska se ei takaa kontrastia kirkkaassa
 * päivänvalossa.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF0B4A9E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF004D43),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFEFE6),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF7A3900),
    onTertiary = Color.White,
    error = Color(0xFF8C1D18),
    onError = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFF3C3C3C),
    outline = Color(0xFF5C5F66),
    outlineVariant = Color(0xFFC9CDD5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F9),
    surfaceContainer = Color(0xFFEDF0F5),
    surfaceContainerHigh = Color(0xFFE8ECF2),
    surfaceContainerHighest = Color(0xFFDCE1E9),
    inverseSurface = Color(0xFF2A2C31),
    inverseOnSurface = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFF9FC4FF),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC4FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF104A8E),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF4FD8C6),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB2F1E6),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF4A2800),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFFA0A0A8),
    outlineVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF1C1D21),
    surfaceContainer = Color(0xFF222428),
    surfaceContainerHigh = Color(0xFF2A2C31),
    surfaceContainerHighest = Color(0xFF33363C),
    inverseSurface = Color(0xFFF3F5F9),
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = Color(0xFF0B4A9E),
)

/**
 * Muodot Material 3:n rooleittain: extraSmall 14 dp (kentät, ilmoitukset, valikot), small pilleri (chipit; kellonvalitsin
 * ohittaa tämän omalla muodollaan), medium 22 dp (kortit), large 24 dp (kelluvat napit) ja extraLarge 28 dp (ikkunat
 * ja alapaneelit). Tavalliset napit ovat aina pillereitä teemasta riippumatta.
 */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(percent = 50),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Kirkkaassa valossa luettava mitoitus: pienin leipäteksti 14 sp, oletus 16 sp, rivikorkeus väljä. */
internal val AppTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
        labelMedium = base.labelMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
        labelSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
        titleMedium = base.titleMedium.copy(fontSize = 18.sp, lineHeight = 25.sp),
    )
}

@Composable
fun TyomatkaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Tummassa teemassa Tänään-kortin täytetty nappi on valkoinen: sininen kortti erottaa sen parhaiten. */
internal val TodayFilledDark = Color(0xFFFFFFFF)
internal val OnTodayFilledDark = Color(0xFF001B3F)
