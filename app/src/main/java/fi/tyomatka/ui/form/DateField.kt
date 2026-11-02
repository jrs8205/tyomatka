package fi.tyomatka.ui.form

import android.content.res.Configuration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.appLocale
import fi.tyomatka.ui.history.shortDay

private const val DAY_MILLIS = 86_400_000L

/** Vain luettava päiväkenttä, jonka napautus avaa kalenterin (ei tulevaisuuteen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    epochDay: Long,
    onChange: (Long) -> Unit,
    label: String,
    maxEpochDay: Long,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    // readOnly-kenttä nielee klikkauksen; avataan vapautuksesta.
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            if (it is PressInteraction.Release) {
                Tap.now()
                showPicker = true
            }
        }
    }
    OutlinedTextField(
        value = shortDay(epochDay),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        // Nappi avaa kalenterin myös näppäimistöllä ja ruudunlukijalla; kentän napautus toimii sormella.
        trailingIcon = {
            IconButton(onClick = Tap { showPicker = true }) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.fuel_date_label))
            }
        },
        interactionSource = interaction,
        modifier = modifier,
    )
    if (showPicker) {
        AppDateLocale {
            // DatePicker laskee UTC-millisekunneilla; epochDay * vuorokausi osuu keskiyöhön UTC.
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = epochDay * DAY_MILLIS,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis <= maxEpochDay * DAY_MILLIS
                },
            )
            DatePickerDialog(
                onDismissRequest = { showPicker = false },
                confirmButton = {
                    TextButton(
                        onClick = Tap {
                            pickerState.selectedDateMillis?.let { onChange(it / DAY_MILLIS) }
                            showPicker = false
                        }
                    ) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = Tap { showPicker = false }) { Text(stringResource(R.string.cancel)) }
                },
            ) { DatePicker(state = pickerState) }
        }
    }
}

/**
 * Järjestelmän kalenterikomponentit näytön kielellä: maanantai aloittaa viikon ja päivämäärä kirjoitetaan
 * päivä–kuukausi-järjestyksessä myös englanniksi (en-GB), vaikka puhelimen kieli olisi jokin muu.
 */
@Composable
fun AppDateLocale(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val finnishConfiguration = remember(configuration) {
        Configuration(configuration).apply { setLocale(appLocale()) }
    }
    val finnishContext = remember(context, finnishConfiguration) {
        context.createConfigurationContext(finnishConfiguration)
    }
    CompositionLocalProvider(
        LocalConfiguration provides finnishConfiguration,
        LocalContext provides finnishContext,
        content = content,
    )
}
