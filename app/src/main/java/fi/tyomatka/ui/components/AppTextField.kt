package fi.tyomatka.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import fi.tyomatka.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Kentän värit: pois käytöstä kenttä ei himmene, vaan teksti, nimi ja ohje ovat `onSurfaceVariant`
 * `surfaceContainerHighest`-pohjalla (≥ 7:1) ja reuna `outlineVariant`, joten lukittu kenttä erottuu ja on luettava.
 */
object AppTextFieldDefaults {
    @Composable
    fun colors(): TextFieldColors {
        val scheme = MaterialTheme.colorScheme
        return OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = scheme.outline,
            disabledTextColor = scheme.onSurfaceVariant,
            disabledLabelColor = scheme.onSurfaceVariant,
            disabledSupportingTextColor = scheme.onSurfaceVariant,
            disabledPlaceholderColor = scheme.onSurfaceVariant,
            disabledLeadingIconColor = scheme.onSurfaceVariant,
            disabledTrailingIconColor = scheme.onSurfaceVariant,
            disabledBorderColor = scheme.outlineVariant,
            disabledContainerColor = scheme.surfaceContainerHighest,
        )
    }
}

private val FocusedBorder = 2.5.dp
private val UnfocusedBorder = 1.5.dp

/**
 * Sovelluksen tekstikenttä: ääriviivakenttä, jonka reuna on 1,5 dp `outline` ja aktiivisena 2,5 dp `primary`, joten
 * kentän rajan ja aktiivisuuden erottaa muustakin kuin väristä. Parametrit ovat samat kuin `OutlinedTextField`issä,
 * joita sovellus käyttää; nimi ja ohjerivit tulevat teeman 14 sp:n tyyleistä.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = AppTextFieldDefaults.colors(),
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val errorText = stringResource(R.string.field_error)
    val labelTop = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2 }
    val focused by source.collectIsFocusedAsState()
    val textColor = textStyle.color.takeOrElse {
        when {
            !enabled -> colors.disabledTextColor
            isError -> colors.errorTextColor
            focused -> colors.focusedTextColor
            else -> colors.unfocusedTextColor
        }
    }
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            // Nimellinen kenttä luetaan yhtenä, ja kohoavalle nimelle jätetään tilaa reunan yläpuolelle kuten OutlinedTextFieldissä.
            modifier = modifier
                .then(if (label != null) Modifier.semantics(mergeDescendants = true) {}.padding(top = labelTop) else Modifier)
                // Virhetila ruudunlukijalle kuten OutlinedTextFieldissä (ei pelkkä väri).
                .then(if (isError) Modifier.semantics { error(errorText) } else Modifier)
                .defaultMinSize(
                minWidth = OutlinedTextFieldDefaults.MinWidth,
                minHeight = OutlinedTextFieldDefaults.MinHeight,
            ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.merge(TextStyle(color = textColor)),
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = source,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = source,
                    isError = isError,
                    label = label,
                    placeholder = placeholder,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    supportingText = supportingText,
                    colors = colors,
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = isError,
                            interactionSource = source,
                            colors = colors,
                            shape = shape,
                            focusedBorderThickness = FocusedBorder,
                            unfocusedBorderThickness = UnfocusedBorder,
                        )
                    },
                )
            },
        )
    }
}
