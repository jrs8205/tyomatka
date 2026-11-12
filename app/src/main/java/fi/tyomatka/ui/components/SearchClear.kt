package fi.tyomatka.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import fi.tyomatka.R
import fi.tyomatka.ui.Tap

/** Hakukentän tyhjennysnappi näkyy vain, kun kentässä on tekstiä ja kenttää saa muokata. */
fun showSearchClear(text: String, enabled: Boolean = true): Boolean = enabled && text.isNotEmpty()

/**
 * Hakukentän loppuun rasti, joka tyhjentää haun; null, kun tyhjennettävää ei ole. Rasti katoaa tyhjennyksessä, joten
 * kohdistus siirretään kenttään ([focus]): näppäimistö ja ruudunlukija jatkavat siitä.
 */
fun searchClearIcon(
    text: String,
    focus: FocusRequester,
    enabled: Boolean = true,
    onClear: () -> Unit,
): (@Composable () -> Unit)? =
    if (!showSearchClear(text, enabled)) {
        null
    } else {
        {
            IconButton(
                onClick = Tap {
                    onClear()
                    focus.requestFocus()
                },
            ) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_clear))
            }
        }
    }
