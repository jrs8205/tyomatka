package fi.tyomatka.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap

/**
 * Päiväkortin Lisätiedot-merkki kuvamerkin tapaan: teksti itse ei näy kortissa, vaan merkki kertoo,
 * että päivällä tai kirjauksella on lisätietoa. [onClick] avaa korjauslomakkeen päivän merkistä; tankkausrivillä
 * null, koska koko rivi avaa lomakkeen.
 */
@Composable
fun NoteMarker(onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val openLabel = stringResource(R.string.note_marker_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.then(
            if (onClick != null) Modifier.heightIn(min = 48.dp).clickable(onClickLabel = openLabel, onClick = Tap(onClick)) else Modifier
        ),
    ) {
        Icon(
            Icons.Outlined.Notes,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.note_marker),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
