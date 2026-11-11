package fi.tyomatka.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.model.DeadlineStatus
import fi.tyomatka.ui.PART_SEPARATOR
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.boundParts
import fi.tyomatka.ui.components.AppButtonDefaults
import fi.tyomatka.ui.components.HeroNumber
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatDuration

/**
 * Päivän yhteenveto: valmistumisaika pääluvun tyylillä, kesto ja stoppimäärä, jokainen myöhästyvä stoppi omalla
 * rivillään sekä Tallenna reitti ja Tallennetut reitit. Ajon aikana napit eivät ole käytössä, ja syy näkyy. Ilman
 * aikataulua vain stoppimäärä ja napit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanSummary(
    state: PlanUiState,
    templateState: TemplateUiState,
    onSaveTemplate: () -> Unit,
    onShowTemplates: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val stopCount = pluralStringResource(R.plurals.stop_count, state.stops.size, state.stops.size)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp)) {
            val schedule = state.schedule
            if (schedule != null) {
                // Ruudunlukija lukee otsikon ja kellonajan yhdessä.
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(stringResource(R.string.plan_ready_estimate), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    HeroNumber(
                        value = formatClock(schedule.finishEpochSec),
                        unit = null,
                        color = scheme.onSurface,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    listOf(
                        boundParts(stringResource(R.string.plan_duration, formatDuration(schedule.finishEpochSec - schedule.computedAtEpochSec))),
                        boundParts(stopCount),
                    ).joinToString(PART_SEPARATOR),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val late = schedule.stopEtas.filter { it.deadlineStatus == DeadlineStatus.LATE }
                if (late.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp), color = scheme.outlineVariant)
                    late.forEach { eta ->
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = scheme.error, modifier = Modifier.size(20.dp))
                            Text(
                                stringResource(R.string.deadline_late_warning, eta.stop.label, boundParts(formatClock(eta.arrivalEpochSec))),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = scheme.error,
                            )
                        }
                    }
                }
            } else {
                Text(stopCount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.stops.isNotEmpty()) {
                    SummaryButton(stringResource(R.string.template_save), Icons.Rounded.Save, !templateState.driving, onSaveTemplate)
                }
                SummaryButton(
                    stringResource(R.string.template_list, templateState.templates.size),
                    Icons.Rounded.FolderOpen,
                    !templateState.driving,
                    onShowTemplates,
                )
            }
            if (templateState.driving) {
                Text(
                    stringResource(R.string.template_disabled_driving),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Tonaalinen 48 dp:n pilleri; pois käytöstä neutraali eikä himmeä ([AppButtonDefaults]). */
@Composable
private fun SummaryButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = Tap(onClick),
        enabled = enabled,
        colors = AppButtonDefaults.tonalColors(),
        // Pois käytöstä täyttö poistuu ja ääriviiva näyttää napin rajan.
        border = if (enabled) null else AppButtonDefaults.border(enabled = false),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, textAlign = TextAlign.Center)
    }
}
