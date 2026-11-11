package fi.tyomatka.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Nappien värit. Käytöstä poissa oleva nappi ei himmene: se on neutraali harmaa pilleri, jonka teksti on luettava
 * (`onSurfaceVariant` `surfaceContainerHighest`-pohjalla ≥ 7:1); täyttö erottuu myös ikkunan pohjasta
 * (`surfaceContainerHigh`). Käytössä oleva vastaava nappi on värillinen, joten ero
 * näkyy täytöstä eikä vain tekstin väristä. Tonaalinen nappi on käytössä jo neutraali täyttö, joten se on poissa
 * käytöstä ilman täyttöä ([border] `false`).
 */
object AppButtonDefaults {

    @Composable
    fun filledColors(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    @Composable
    fun tonalColors(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    @Composable
    fun outlinedColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    @Composable
    fun textColors(): ButtonColors = ButtonDefaults.textButtonColors(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    /** Vaarallinen vahvistus (tyhjennä, poista): täytetty virheväri, teksti ikkunan pohjavärillä. */
    @Composable
    fun dangerColors(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    /** Ilmoituksen (snackbar) toiminto käänteisellä pohjalla: `inversePrimary`, kuten Materialin oma toimintonappi. */
    @Composable
    fun snackbarActionColors(): ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.inversePrimary,
    )

    /** Ääriviiva 1,5 dp: käytössä `outline` (≥ 4,5:1), poissa käytöstä `outlineVariant`. */
    @Composable
    fun border(enabled: Boolean): BorderStroke = BorderStroke(
        1.5.dp,
        if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
    )
}
