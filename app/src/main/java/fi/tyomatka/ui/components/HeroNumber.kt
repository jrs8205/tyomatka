package fi.tyomatka.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Pääluku: iso luku ja pienempi yksikkö samalla rivillä. Luku pienenee vain sen verran, että se mahtuu riville
 * ([TextAutoSize]); välit ovat sitovia, joten luku ja yksikkö eivät koskaan jakaudu.
 */
@Composable
fun HeroNumber(
    value: String,
    unit: String?,
    color: Color,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 60.sp,
    minSize: TextUnit = 28.sp,
    textAlign: TextAlign = TextAlign.Center,
) {
    val text = buildAnnotatedString {
        append(value.replace(' ', '\u00A0'))
        if (unit != null) {
            append('\u00A0')
            withStyle(SpanStyle(fontSize = 0.45.em, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)) { append(unit) }
        }
    }
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize),
        modifier = modifier,
    )
}
