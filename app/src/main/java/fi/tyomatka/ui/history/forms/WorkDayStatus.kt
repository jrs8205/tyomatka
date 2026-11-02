package fi.tyomatka.ui.history.forms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import fi.tyomatka.data.db.WorkSegmentEntity
import kotlinx.coroutines.delay

/** Päivän työaika segmenttien summana: tauot eivät kuulu työaikaan, eikä päättynyt päivä kasva illalla. */
data class WorkDayStatus(
    val startedAtEpochSec: Long?,
    val endedAtEpochSec: Long?,
    val workedSec: Long,
    val ongoing: Boolean,
)

fun workDayStatus(segments: List<WorkSegmentEntity>, nowEpochSec: Long): WorkDayStatus {
    if (segments.isEmpty()) return WorkDayStatus(null, null, 0, ongoing = false)
    val ongoing = segments.any { it.endEpochSec == null }
    val worked = segments.sumOf { ((it.endEpochSec ?: nowEpochSec) - it.startEpochSec).coerceAtLeast(0) }
    return WorkDayStatus(
        startedAtEpochSec = segments.minOf { it.startEpochSec },
        endedAtEpochSec = if (ongoing) null else segments.mapNotNull { it.endEpochSec }.maxOrNull(),
        workedSec = worked,
        ongoing = ongoing,
    )
}

/** Nykyhetki sekunteina, päivittyy minuutin välein, jotta käynnissä olevan päivän kesto juoksee. */
@Composable
fun rememberNowEpochSec(): Long {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    return now
}
