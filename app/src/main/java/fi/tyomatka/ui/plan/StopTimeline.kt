package fi.tyomatka.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.model.DeadlineStatus
import fi.tyomatka.core.model.StopEta
import fi.tyomatka.core.plan.ServiceStops
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.boundParts
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatMinutesOfDay
import fi.tyomatka.ui.history.unbreakableParts

/** Aikajanan pallo: jäljellä olevan stopin numero, käydyn ✓ tai ohitetun "–". */
sealed interface StopNode {
    data class Number(val n: Int) : StopNode
    data object Done : StopNode
    data object Skipped : StopNode
}

/** Numero vain jäljellä olevalla stopilla; käyty saa ✓:n, muu numeroton (ohitettu) "–":n kuten ennen. */
fun stopNode(number: Int?, status: StopStatus): StopNode = when {
    number != null -> StopNode.Number(number)
    status == StopStatus.DONE -> StopNode.Done
    else -> StopNode.Skipped
}

/** Saapumisajan sävy: myöhässä ja tiukka saavat oman värin, ikonin ja ruudunlukijatekstin. */
enum class EtaTone { NORMAL, TIGHT, LATE }

fun etaTone(status: DeadlineStatus?): EtaTone = when (status) {
    DeadlineStatus.LATE -> EtaTone.LATE
    DeadlineStatus.TIGHT -> EtaTone.TIGHT
    else -> EtaTone.NORMAL
}

/** Käydyn tai ohitetun stopin jälkeinen viiva on himmeä (`outline`), jäljellä olevien `primary`. */
fun railMuted(status: StopStatus): Boolean = status == StopStatus.DONE || status == StopStatus.SKIPPED

/** Saapumisaika nimen viereen vain, jos nimen ja osoitteen pisin sana mahtuu jäljelle jäävään tilaan kokonaisena. */
fun etaBesideNames(widestWordPx: Int, availablePx: Int, etaPx: Int, gapPx: Int): Boolean =
    widestWordPx <= availablePx - etaPx - gapPx

/** Nimen, osoitteen ja huoltoasemamerkin osat, joiden pisin sana ratkaisee saapumisajan paikan. */
enum class NamePart { NAME, ADDRESS, BADGE }

fun nameParts(label: String, address: String, serviceBadge: String?): List<Pair<String, NamePart>> = buildList {
    add(label to NamePart.NAME)
    if (address.isNotBlank()) add(address to NamePart.ADDRESS)
    serviceBadge?.let { add(it to NamePart.BADGE) }
}

/** Asettamattoman chipin ruudunlukijateksti on nykyinen pitkä teksti; asetetun lukee näkyvä arvo. */
fun chipDescription(set: Boolean, long: String): String? = if (set) null else long

/** Saapumisajan väri: myöhässä `error`, tiukka `tertiary`, muuten `onSurface`. */
fun etaColor(tone: EtaTone, scheme: ColorScheme): Color = when (tone) {
    EtaTone.LATE -> scheme.error
    EtaTone.TIGHT -> scheme.tertiary
    EtaTone.NORMAL -> scheme.onSurface
}

private val NodeSize = 34.dp
private val NodeTop = 10.dp
private val RailGap = 4.dp
private val ItemGap = 10.dp

/**
 * Stoppi aikajanalla: vasemmalla pallo ja viiva seuraavaan stoppiin (ei viimeisen jälkeen), oikealla kortti. Nimen ja
 * osoitteen napautus avaa muokkauksen; chipit Aikaraja ja Kesto sekä poisto toimivat kaikilla stopeilla.
 */
@Composable
fun StopTimelineItem(
    stop: StopEntity,
    number: Int?,
    eta: StopEta?,
    isLast: Boolean,
    onEditClick: () -> Unit,
    onDelete: () -> Unit,
    onDeadlineClick: () -> Unit,
    onServiceClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val railColor = if (railMuted(stop.status)) scheme.outline else scheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (isLast) return@drawBehind
                // Viiva pallon alta seuraavan stopin pallon yläpuolelle: alkion alaväli on mukana koossa, ja viiva
                // jatkuu seuraavan alkion ylävälin yli.
                val x = NodeSize.toPx() / 2
                val top = (NodeTop + NodeSize + RailGap).toPx()
                val bottom = size.height + (NodeTop - RailGap).toPx()
                val width = 3.dp.toPx()
                drawRoundRect(
                    color = railColor,
                    topLeft = Offset(x - width / 2, top),
                    size = Size(width, (bottom - top).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(width / 2),
                )
            }
            .padding(bottom = ItemGap),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StopNodeView(stopNode(number, stop.status), Modifier.padding(top = NodeTop))
        StopCard(stop, eta, onEditClick, onDelete, onDeadlineClick, onServiceClick, Modifier.weight(1f))
    }
}

@Composable
private fun StopNodeView(node: StopNode, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val filled = node is StopNode.Number
    // Pallo levenee pilleriksi, jos numero ei mahdu (100 stoppia tai iso fontti).
    Box(
        modifier
            .defaultMinSize(minWidth = NodeSize, minHeight = NodeSize)
            .background(if (filled) scheme.primary else scheme.surfaceContainerHigh, RoundedCornerShape(percent = 50))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (node) {
            is StopNode.Number -> Text(
                node.n.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimary,
                maxLines = 1,
                softWrap = false,
            )
            StopNode.Done -> Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.plan_stop_done), tint = scheme.onSurface)
            StopNode.Skipped -> {
                val skipped = stringResource(R.string.plan_stop_skipped)
                Text(
                    "–",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = skipped },
                )
            }
        }
    }
}

@Composable
private fun StopCard(
    stop: StopEntity,
    eta: StopEta?,
    onEditClick: () -> Unit,
    onDelete: () -> Unit,
    onDeadlineClick: () -> Unit,
    onServiceClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp, end = 4.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.edit_stop), onClick = Tap(onEditClick))
                    .heightIn(min = 48.dp)
                    .padding(end = 10.dp),
            ) {
                NamesAndEta(stop, eta)
            }
            StopActions(stop, onDelete, onDeadlineClick, onServiceClick)
        }
    }
}

/**
 * Nimi ja osoite rivittyvät eivätkä katkea. Saapumisaika on oikealla, tai osoitteen alla, jos nimen tai osoitteen
 * pisin sana ei mahtuisi sen viereen kokonaisena (iso fontti).
 */
@Composable
private fun NamesAndEta(stop: StopEntity, eta: StopEta?) {
    val scheme = MaterialTheme.colorScheme
    val done = stop.status == StopStatus.DONE
    val nameStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = if (done) FontWeight.Medium else FontWeight.Bold)
    val addressStyle = MaterialTheme.typography.bodyMedium
    val etaStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    val badgeStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val badge = if (ServiceStops.isServiceStop(stop.placeId)) stringResource(R.string.plan_service_station) else null
    val names: @Composable () -> Unit = {
        Column {
            Text(stop.label, style = nameStyle)
            if (stop.address.isNotBlank()) Text(stop.address, style = addressStyle, color = scheme.onSurfaceVariant)
            badge?.let { ServiceBadge(it, badgeStyle) }
        }
    }
    if (eta == null) {
        names()
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val time = formatClock(eta.arrivalEpochSec)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val beside = with(density) {
            fun widest(text: String, style: TextStyle) =
                unbreakableParts(text).maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
            val etaWidth = measurer.measure(time, etaStyle, maxLines = 1, softWrap = false).size.width + (EtaIcon + 4.dp).roundToPx()
            val widestWord = nameParts(stop.label, stop.address, badge).maxOf { (text, part) ->
                when (part) {
                    NamePart.NAME -> widest(text, nameStyle)
                    NamePart.ADDRESS -> widest(text, addressStyle)
                    NamePart.BADGE -> widest(text, badgeStyle) + (BadgeIcon + 4.dp).roundToPx()
                }
            }
            etaBesideNames(
                widestWordPx = widestWord,
                availablePx = constraints.maxWidth,
                etaPx = etaWidth,
                gapPx = 12.dp.roundToPx(),
            )
        }
        if (beside) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { names() }
                EtaLabel(time, etaTone(eta.deadlineStatus), etaStyle)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                names()
                EtaLabel(time, etaTone(eta.deadlineStatus), etaStyle)
            }
        }
    }
}

private val EtaIcon = 20.dp

/** Saapumisaika värillä; myöhässä ja tiukka saavat ikonin, ja ruudunlukija kertoo tilan sanoin. */
@Composable
private fun EtaLabel(time: String, tone: EtaTone, style: TextStyle) {
    val color = etaColor(tone, MaterialTheme.colorScheme)
    val status = when (tone) {
        EtaTone.LATE -> stringResource(R.string.plan_eta_late)
        EtaTone.TIGHT -> stringResource(R.string.plan_eta_tight)
        EtaTone.NORMAL -> null
    }
    Row(
        Modifier.clearAndSetSemantics { contentDescription = listOfNotNull(time, status).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (tone) {
            EtaTone.LATE -> Icon(Icons.Rounded.Warning, contentDescription = null, tint = color, modifier = Modifier.size(EtaIcon))
            EtaTone.TIGHT -> Icon(Icons.Rounded.Schedule, contentDescription = null, tint = color, modifier = Modifier.size(EtaIcon))
            EtaTone.NORMAL -> Unit
        }
        Text(boundParts(time), style = style, color = color)
    }
}

private val BadgeIcon = 18.dp

@Composable
private fun ServiceBadge(text: String, style: TextStyle) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.LocalGasStation, contentDescription = null, tint = color, modifier = Modifier.size(BadgeIcon))
        Spacer(Modifier.width(4.dp))
        Text(text, style = style, color = color)
    }
}

/**
 * Chipit Aikaraja ja Kesto sekä poisto reunassa. Asetettu arvo näkyy täytettynä ("Viim. 09.00", "15 min");
 * asettamaton lyhyenä, ja ruudunlukija lukee nykyisen pitkän tekstin.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopActions(stop: StopEntity, onDelete: () -> Unit, onDeadlineClick: () -> Unit, onServiceClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val deadline = stop.deadlineMinutesOfDay
            StopChip(
                text = deadline?.let { stringResource(R.string.deadline_at, formatMinutesOfDay(it)) } ?: stringResource(R.string.plan_deadline_short),
                description = chipDescription(set = deadline != null, long = stringResource(R.string.set_deadline)),
                icon = Icons.Rounded.Schedule,
                set = deadline != null,
                onClick = onDeadlineClick,
            )
            val service = stop.serviceSec
            StopChip(
                text = service?.let { "${it / 60} min" } ?: stringResource(R.string.plan_service_short),
                description = chipDescription(set = service != null, long = stringResource(R.string.set_service_time)),
                icon = Icons.Rounded.Timer,
                set = service != null,
                onClick = onServiceClick,
            )
        }
        // Poisto reunassa erillään chipeistä, ettei siihen osu vahingossa.
        IconButton(onClick = Tap(onDelete)) {
            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete_stop), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StopChip(text: String, description: String?, icon: ImageVector, set: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val content = if (set) scheme.onSecondaryContainer else scheme.onSurface
    AssistChip(
        onClick = Tap(onClick),
        label = { Text(boundParts(text), style = MaterialTheme.typography.labelLarge) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (set) scheme.secondaryContainer else Color.Transparent,
            labelColor = content,
            leadingIconContentColor = content,
        ),
        border = if (set) null else BorderStroke(1.5.dp, scheme.outline),
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
    )
}
