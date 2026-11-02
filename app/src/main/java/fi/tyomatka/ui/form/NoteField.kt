package fi.tyomatka.ui.form

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import fi.tyomatka.R
import fi.tyomatka.data.NOTE_MAX_LENGTH

/**
 * Lisätiedot-kenttä: monirivinen vapaa teksti, enintään [NOTE_MAX_LENGTH] merkkiä (määrittely 20.10.). Sama kenttä
 * tankkauksessa, työpäivän aloituksessa ja lopetuksessa sekä korjauslomakkeessa; [hint] kertoo, mitä tähän kirjoitetaan.
 */
@Composable
fun NoteField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(NOTE_MAX_LENGTH)) },
        label = { Text(stringResource(R.string.note_field_label)) },
        supportingText = { Text(hint) },
        minLines = 2,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
    )
}
