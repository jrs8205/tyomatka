package fi.tyomatka.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Chipin näkyvä korkeus; kosketusalue on vähintään 48 dp (Material). */
val ChipHeight = 40.dp

/** Chipin ääriviiva: `outline` (≥ 3:1), ei koristeellista `outlineVariant`ia. */
val ChipBorderWidth = 1.5.dp

/**
 * Valintachip: valittu on täytetty (`secondaryContainer`) ja siinä on ✓, joten valinnan erottaa muustakin kuin
 * väristä; valitsematon on ääriviivallinen. [showCheck] `false` jättää ✓:n pois, kun valinta näkyy jo muuten.
 */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showCheck: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        enabled = enabled,
        modifier = modifier.heightIn(min = ChipHeight),
        leadingIcon = if (selected && showCheck) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = scheme.onSurface,
            iconColor = scheme.onSurface,
            selectedContainerColor = scheme.secondaryContainer,
            selectedLabelColor = scheme.onSecondaryContainer,
            selectedLeadingIconColor = scheme.onSecondaryContainer,
            disabledLabelColor = scheme.onSurfaceVariant,
            disabledSelectedContainerColor = scheme.surfaceContainerHighest,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = scheme.outline,
            selectedBorderColor = Color.Transparent,
            disabledBorderColor = scheme.outlineVariant,
            borderWidth = ChipBorderWidth,
            selectedBorderWidth = 0.dp,
        ),
    )
}
