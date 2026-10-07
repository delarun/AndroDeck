package org.evileat.deck

import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardColor = Color(0xFF1B1C1F)
private val CardShape = RoundedCornerShape(28.dp)
private val Faint = Color.White.copy(alpha = 0.08f)
private val Alert = Color(0xFFFF6B6B)
private val Gap = 12.dp
private val AllAppsIcon = 56.dp
private val Good = Color(0xFF7EE787)
private val Soon = Color(0xFFFFD166)

private enum class Page { Dashboard, Schedule, Apps }

@Composable
fun DeckApp(client: DeckClient, onSave: (String, String) -> Unit) {
    val state by client.state.collectAsState()
    val apps by client.apps.collectAsState()
    val allApps by client.allApps.collectAsState()
    val schedule by client.schedule.collectAsState()
    val notes by client.notes.collectAsState()
    var page by remember { mutableStateOf(Page.Dashboard) }
    var settings by remember { mutableStateOf(false) }

    BackHandler(page != Page.Dashboard) { page = Page.Dashboard }

    // иконки всех приложений заранее кладём в кэш, чтобы список открывался уже с картинками
    val ctx = LocalContext.current
    val iconPx = with(LocalDensity.current) { AllAppsIcon.roundToPx() }
    LaunchedEffect(allApps, state.connected) {
        if (state.connected) allApps.forEach { app ->
            val url = client.resolve(app.icon) ?: return@forEach
            ctx.imageLoader.enqueue(
                ImageRequest.Builder(ctx).data(url).size(iconPx).precision(Precision.INEXACT).build()
            )
        }
    }

    Box(Modifier.fillMaxSize().threeFingerTap { settings = true }) {
        Row(Modifier.fillMaxSize()) {
            Rail(
                state, page,
                onMic = { client.toggleMic() },
                onPage = { p ->  // повторное нажатие — обратно на дашборд
                    page = if (page == p) Page.Dashboard else p
                    if (page == Page.Apps) client.refreshAllApps()
                },
            )
            Crossfade(page, Modifier.weight(1f).fillMaxHeight(), label = "page") { p ->
                when (p) {
                    Page.Dashboard -> Dashboard(state, apps, client)
                    Page.Schedule -> ScheduleScreen(schedule, client)
                    Page.Apps -> AllApps(allApps, client) { page = Page.Dashboard }
                }
            }
        }
        NoteBanner(notes.lastOrNull(), client, Modifier.align(Alignment.TopCenter))
    }

    if (settings) {
        SettingsDialog(
            client.baseUrl, client.token,
            status = when {
                !state.connected -> "Нет связи"
                state.adb -> "Подключено по ADB"
                else -> "Подключено по сети"
            },
            onDismiss = { settings = false },
            onSave = { u, t -> settings = false; onSave(u, t) },
        )
    }
}

/** Тап тремя пальцами где угодно; до кнопок под пальцами он не доходит */
private fun Modifier.threeFingerTap(onTap: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        var fingers = 0
        do {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            fingers = max(fingers, e.changes.count { it.pressed })
            if (fingers >= 3) e.changes.forEach { it.consume() }
        } while (e.changes.any { it.pressed })
        if (fingers == 3) onTap()
    }
}

// ---------------- Бар слева ----------------

@Composable
private fun Rail(state: DeckState, page: Page, onMic: () -> Unit, onPage: (Page) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxHeight().width(76.dp).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Clock()
        if (!state.connected) {
            Icon(Icons.Default.CloudOff, null, Modifier.padding(top = 10.dp).size(20.dp), tint = Alert)
        }
        Spacer(Modifier.weight(1f))
        RailButton(
            if (state.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
            container = if (state.micMuted) Alert else Faint,
            content = if (state.micMuted) Color.Black else Color.White,
        ) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onMic()
        }
        Spacer(Modifier.height(Gap))
        RailButton(if (page == Page.Schedule) Icons.Default.Home else Icons.Default.Event) { onPage(Page.Schedule) }
        Spacer(Modifier.height(Gap))
        RailButton(if (page == Page.Apps) Icons.Default.Home else Icons.Default.Apps) { onPage(Page.Apps) }
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    container: Color = Faint,
    content: Color = Color.White,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
    ) {
        Icon(icon, null, Modifier.size(26.dp))
    }
}

@Composable
private fun Clock() {
    val time by produceState("") {
        while (true) {
            // формат создаём каждый раз: подхватывает смену часового пояса
            value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Text(time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

// ---------------- Дашборд: плеер + быстрые приложения ----------------

@Composable
private fun Dashboard(state: DeckState, apps: List<AppEntry>, client: DeckClient) {
    Row(
        Modifier.fillMaxSize().padding(top = Gap, end = Gap, bottom = Gap),
        horizontalArrangement = Arrangement.spacedBy(Gap),
    ) {
        PlayerCard(state, client, Modifier.weight(1.4f).fillMaxHeight())
        QuickApps(apps, client, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun PlayerCard(state: DeckState, client: DeckClient, modifier: Modifier) {
    val p = state.player
    val art = client.resolve(p?.art)

    Box(modifier.clip(CardShape).background(CardColor)) {
        if (art != null) {
            // крошечная картинка, растянутая на карточку: размытие и там, где Modifier.blur не работает (< Android 12)
            val ctx = LocalContext.current
            val tiny = remember(art) { ImageRequest.Builder(ctx).data(art).size(48).build() }
            AsyncImage(
                tiny, null, Modifier.matchParentSize().blur(40.dp),
                contentScale = ContentScale.Crop, alpha = 0.3f,
            )
        }
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(Faint),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.MusicNote, null, Modifier.size(36.dp).alpha(0.3f))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        p?.title?.ifEmpty { null } ?: "Ничего не играет",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        p?.artist ?: "",
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.7f),
                    )
                    Text(
                        listOfNotNull(p?.album?.ifEmpty { null }, p?.player?.ifEmpty { null }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.4f),
                    )
                }
            }

            Progress(p, client)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BigButton(Icons.Default.SkipPrevious, 52.dp) { client.previous() }
                FilledIconButton(
                    onClick = { client.playPause() },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White, contentColor = Color.Black,
                    ),
                ) {
                    Icon(
                        if (p?.playing == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null, Modifier.size(36.dp),
                    )
                }
                BigButton(Icons.Default.SkipNext, 52.dp) { client.next() }
            }

            Volume(state, client)
        }
    }
}

@Composable
private fun Progress(p: PlayerState?, client: DeckClient) {
    val len = max(p?.length ?: 0f, 1f)
    var drag by remember { mutableStateOf<Float?>(null) }
    val pos = (drag ?: p?.position ?: 0f).coerceIn(0f, len)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(fmt(pos), style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.6f))
        ThinSlider(
            value = pos,
            onChange = { drag = it },
            onDone = { drag?.let { client.seek(it) }; drag = null },
            range = 0f..len,
            modifier = Modifier.weight(1f),
            enabled = (p?.length ?: 0f) > 0f,
        )
        Text(fmt(p?.length ?: 0f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.6f))
    }
}

@Composable
private fun Volume(state: DeckState, client: DeckClient) {
    var drag by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { client.toggleMute() }) {
            Icon(
                if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                null, tint = if (state.muted) Alert else Color.White,
            )
        }
        ThinSlider(
            value = drag ?: state.volume,
            onChange = { drag = it },
            onDone = { drag?.let { client.setVolume(it) }; drag = null },
            range = 0f..1f,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = SliderDefaults.colors(
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.18f),
        disabledActiveTrackColor = Color.White.copy(alpha = 0.18f),
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.18f),
    )
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onDone,
        valueRange = range,
        enabled = enabled,
        modifier = modifier,
        colors = colors,
        thumb = { Box(Modifier.size(14.dp).alpha(if (enabled) 1f else 0f).background(Color.White, CircleShape)) },
        track = {
            SliderDefaults.Track(
                it, Modifier.height(4.dp), enabled = enabled, colors = colors,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp,
            )
        },
    )
}

@Composable
private fun BigButton(icon: ImageVector, size: Dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(icon, null, Modifier.size(size * 0.7f))
    }
}

private fun fmt(sec: Float): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun QuickApps(apps: List<AppEntry>, client: DeckClient, modifier: Modifier) {
    BoxWithConstraints(modifier.clip(CardShape).background(CardColor).padding(8.dp), Alignment.Center) {
        if (apps.isEmpty()) {
            Text("Нет приложений — проверь apps в config.yaml", Modifier.padding(16.dp).alpha(0.5f), textAlign = TextAlign.Center)
            return@BoxWithConstraints
        }
        val cols = gridColumns(apps.size, maxWidth / maxHeight)
        val rows = (apps.size + cols - 1) / cols
        val icon = (minOf(maxWidth / cols, maxHeight / rows) * 0.5f).coerceAtMost(72.dp)
        Column(Modifier.fillMaxSize()) {
            apps.chunked(cols).forEach { row ->
                Row(Modifier.weight(1f)) {
                    row.forEach { app ->
                        key(app.id) {
                            AppTile(app, client.resolve(app.icon), icon, Modifier.weight(1f).fillMaxHeight()) {
                                client.launch(app)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Сколько колонок, чтобы n плиток заняли всю карточку: плитки ближе к квадрату, пустых ячеек поменьше */
private fun gridColumns(n: Int, aspect: Float): Int = (1..n).minBy { cols ->
    val rows = (n + cols - 1) / cols
    abs(ln(aspect * rows / cols)) + (rows * cols - n).toFloat() / n
}

// ---------------- Расписание: созвоны и дела из QuickSchedule ----------------

@Composable
private fun ScheduleScreen(schedule: Schedule?, client: DeckClient) {
    if (schedule == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "QuickSchedule не на связи\nвпиши [deck] url и token в его config.toml",
                Modifier.alpha(0.5f), textAlign = TextAlign.Center,
            )
        }
        return
    }
    Row(
        Modifier.fillMaxSize().padding(top = Gap, end = Gap, bottom = Gap),
        horizontalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Panel("Созвоны", Modifier.weight(1f).fillMaxHeight()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (schedule.error.isNotEmpty()) item {
                    Text(
                        "⚠ " + schedule.error, style = MaterialTheme.typography.labelMedium, color = Alert,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                schedule.days.forEach { day ->
                    item { SectionTitle(day.title) }
                    if (day.meetings.isEmpty()) item { Hint("Встреч нет") }
                    items(day.meetings) { m -> MeetingRow(m) { client.openLink(m.link) } }
                }
            }
        }
        Panel("Дела", Modifier.weight(1.1f).fillMaxHeight()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (schedule.tasks.isEmpty()) item { Hint("Дел нет") }
                items(schedule.tasks) { t ->
                    ItemRow(
                        t.title, listOf(t.due, t.origin), t.note, t.overdue, t.done,
                        if (t.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        if (t.id.isEmpty()) null else ({ client.setTaskDone(t.id, !t.done) }),
                        t.link,
                    ) { client.openLink(t.link) }
                }
                if (schedule.jira.isNotEmpty()) {
                    item { SectionTitle("Jira") }
                    items(schedule.jira) { j ->
                        ItemRow(j.title, listOf(j.key, j.status, j.due), "", j.overdue, false, null, null, j.link) {
                            client.openLink(j.link)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.clip(CardShape).background(CardColor).padding(horizontal = 14.dp, vertical = 14.dp)) {
        Text(
            title, Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp).alpha(0.5f),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(start = 6.dp, top = 4.dp).alpha(0.4f), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MeetingRow(m: MeetingItem, onJoin: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val live = m.state == "now" || m.state == "soon"
    Row(
        Modifier.fillMaxWidth().alpha(if (m.state == "past" || m.cancelled) 0.4f else 1f)
            .clip(RoundedCornerShape(14.dp)).background(if (m.state == "now") Faint else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(46.dp)) {
            Text(m.start, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (m.end.isNotEmpty()) {
                Text(m.end, style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.5f))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                m.subject, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (m.cancelled) TextDecoration.LineThrough else null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.badge.isNotEmpty()) {
                    Text(
                        m.badge, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = when {
                            m.cancelled -> Alert
                            m.state == "now" -> Good
                            else -> Soon
                        },
                    )
                }
                if (m.where.isNotEmpty()) {
                    Text(
                        m.where, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.5f),
                    )
                }
            }
        }
        if (m.join.isNotEmpty()) {
            // «Войти» открывает созвон на компьютере; у идущего и ближайшего кнопка заметнее
            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onJoin()
                },
                modifier = Modifier.size(42.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (live) Color.White else Faint,
                    contentColor = if (live) Color.Black else Color.White,
                ),
            ) {
                Icon(Icons.Default.Videocam, m.join, Modifier.size(22.dp))
            }
        }
    }
}

/** Дело или задача Jira. Кружок отмечает дело выполненным; есть ссылка (сообщение в Telegram, задача) —
 *  нажатие на строку открывает её на компьютере */
@Composable
private fun ItemRow(
    title: String,
    meta: List<String>,
    note: String,
    overdue: Boolean,
    done: Boolean,
    mark: ImageVector?,
    onMark: (() -> Unit)?,
    link: String,
    onOpen: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().alpha(if (done) 0.4f else 1f).clip(RoundedCornerShape(14.dp))
            .then(
                if (link.isEmpty()) Modifier else Modifier.clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpen()
                }
            )
            .padding(start = if (mark != null) 0.dp else 6.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (mark != null) 4.dp else 10.dp),
    ) {
        if (mark != null) {
            // кружок с запасом под палец; без onMark (deck.edit = false) — просто значок
            Box(
                Modifier.size(34.dp).clip(CircleShape).then(
                    if (onMark == null) Modifier else Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMark()
                    }
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(mark, null, Modifier.size(20.dp).alpha(0.7f))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            val line = meta.filter { it.isNotEmpty() }.joinToString("  ·  ")
            if (line.isNotEmpty()) {
                Text(
                    line, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (overdue) Alert else Color.White.copy(alpha = 0.5f),
                )
            }
            if (note.isNotEmpty()) {
                Text(
                    note, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.5f),
                )
            }
        }
        if (link.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(top = 3.dp).size(16.dp).alpha(0.4f))
        }
    }
}

// ---------------- Уведомления с компьютера ----------------

@Composable
private fun NoteBanner(note: Note?, client: DeckClient, modifier: Modifier) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val last = remember { arrayOfNulls<Note>(1) }  // что рисовать, пока баннер уезжает
    val rung = remember { mutableSetOf<String>() }  // звук — один раз на уведомление, а не при каждом показе
    if (note != null) last[0] = note

    LaunchedEffect(note?.id) {
        val n = note ?: return@LaunchedEffect
        if (rung.add(n.id)) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            runCatching {
                RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.run {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                    play()
                }
            }
        }
        delay((n.until - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        client.dismiss(n.id)
    }

    AnimatedVisibility(
        note != null, modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val n = last[0] ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(24.dp)
        Row(
            Modifier.padding(top = Gap).fillMaxWidth(0.88f).clip(shape).background(Color(0xFF2B2D33))
                .border(2.dp, if (n.urgent) Alert else Color.Transparent, shape)
                .padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Notifications, null, tint = if (n.urgent) Alert else Color.White)
            Column(Modifier.weight(1f)) {
                if (n.app.isNotEmpty()) {
                    Text(n.app, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.5f))
                }
                Text(
                    n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (n.body.isNotEmpty()) {
                    Text(
                        n.body, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.7f),
                    )
                }
            }
            if (n.action.isNotEmpty()) {
                Button(
                    onClick = { client.openNote(n.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Text(n.action, maxLines = 1)
                }
            }
            IconButton(onClick = { client.dismiss(n.id) }) {
                Icon(Icons.Default.Close, null, Modifier.alpha(0.6f))
            }
        }
    }
}

// ---------------- Все приложения (.desktop) ----------------

@Composable
private fun AllApps(apps: List<AppEntry>, client: DeckClient, onLaunched: () -> Unit) {
    if (apps.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нет приложений", Modifier.alpha(0.5f))
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Gap, end = Gap, bottom = Gap),
    ) {
        items(apps, key = { it.id }) { app ->
            AppTile(app, client.resolve(app.icon), AllAppsIcon, Modifier.fillMaxWidth()) {
                client.launch(app)
                onLaunched()
            }
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, icon: String?, iconSize: Dp, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")

    Column(
        modifier.scale(scale).clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = src, indication = LocalIndication.current) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            AsyncImage(icon, app.name, Modifier.size(iconSize))
        } else {
            Box(
                Modifier.size(iconSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    app.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.name, style = MaterialTheme.typography.labelLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.alpha(0.85f),
        )
    }
}

// ---------------- Настройки (тап тремя пальцами) ----------------

@Composable
fun SettingsDialog(
    url: String,
    token: String,
    status: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var u by remember { mutableStateOf(url) }
    var t by remember { mutableStateOf(token) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сервер deckd") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.6f))
                OutlinedTextField(
                    u, { u = it }, label = { Text("URL") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(t, { t = it }, label = { Text("Token") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(u.trim(), t.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
