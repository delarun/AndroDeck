package org.evileat.deck

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class PlayerState(
    val status: String,
    val player: String,
    val artist: String,
    val title: String,
    val album: String,
    val art: String?,
    val length: Float,
    val position: Float,
) {
    val playing get() = status == "Playing"
}

data class DeckState(
    val connected: Boolean = false,
    val player: PlayerState? = null,
    val volume: Float = 0f,
    val muted: Boolean = false,
    val micMuted: Boolean = false,
    val adb: Boolean = false,
)

data class AppEntry(val id: String, val name: String, val icon: String?, val launch: String)

/** Расписание от QuickSchedule — уже готовое к показу: время, дни и сроки приходят строками */
data class Schedule(val days: List<Day>, val tasks: List<TaskItem>, val jira: List<JiraItem>, val error: String)

data class Day(val title: String, val meetings: List<MeetingItem>)

data class MeetingItem(
    val start: String,
    val end: String,
    val subject: String,
    val where: String,
    val state: String,  // past | now | soon | ""
    val badge: String,
    val cancelled: Boolean,
    val join: String,   // подпись кнопки «Войти»: Teams, Zoom… Пусто — входить некуда
    val link: String,   // ключ ссылки: сама ссылка остаётся на компьютере, открывает её deckd
)

data class TaskItem(
    val id: String,  // пусто — отмечать с телефона нельзя
    val title: String,
    val done: Boolean,
    val origin: String,
    val note: String,
    val due: String,
    val overdue: Boolean,
    val link: String,
)

data class JiraItem(
    val key: String,
    val title: String,
    val status: String,
    val due: String,
    val overdue: Boolean,
    val link: String,
)

/** Уведомление с компьютера. until — до какого момента (elapsedRealtime) держать на экране */
data class Note(
    val id: String,
    val app: String,
    val title: String,
    val body: String,
    val action: String,  // подпись кнопки; пусто — кнопки нет
    val urgent: Boolean,
    val until: Long,
)

class DeckClient(baseUrl: String, token: String) {
    @Volatile var baseUrl = baseUrl; private set
    @Volatile var token = token; private set

    val state = MutableStateFlow(DeckState())
    /** быстрые приложения из config.yaml */
    val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    /** все приложения с .desktop */
    val allApps = MutableStateFlow<List<AppEntry>>(emptyList())
    /** null — расписание никто не присылает */
    val schedule = MutableStateFlow<Schedule?>(null)
    /** уведомления, которые сейчас на экране */
    val notes = MutableStateFlow<List<Note>>(emptyList())
    private val seenNotes = ArrayDeque<String>()  // после переподключения сервер повторяет ещё живые — второй раз не показываем
    /** Кто покажет уведомление, пока Deck не на экране: (уведомление, сколько мс держать) -> взял ли на себя */
    @Volatile var background: ((Note, Long) -> Boolean)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    /** куда ходим сейчас: baseUrl или adb-туннель */
    @Volatile private var active = baseUrl

    /** 127.0.0.1 с портом сервера — туда нас пробрасывает `adb reverse` (deckd, adb: true) */
    private fun adbUrl() = "http://127.0.0.1:" + (baseUrl.toHttpUrlOrNull()?.port ?: 8765)

    fun url(path: String): String {
        val sep = if ('?' in path) '&' else '?'
        return active.trimEnd('/') + path + sep + "t=" + URLEncoder.encode(token, "UTF-8")
    }

    /** "/art/xxx" -> полный URL с токеном; http(s) отдаём как есть */
    fun resolve(u: String?): String? = when {
        u.isNullOrEmpty() -> null
        u.startsWith("/") -> url(u)
        else -> u
    }

    fun start() {
        val old = loop
        loop = scope.launch {
            old?.cancelAndJoin()
            while (isActive) {
                // сначала adb: без туннеля localhost отказывает мгновенно
                for (base in listOf(adbUrl(), baseUrl).distinct()) {
                    ensureActive()
                    active = base
                    runCatching { session() }
                    state.value = state.value.copy(connected = false)
                }
                delay(2000)
            }
        }
    }

    fun reconfigure(url: String, token: String) {
        baseUrl = url
        this.token = token
        apps.value = emptyList()
        allApps.value = emptyList()
        schedule.value = null
        notes.value = emptyList()
        start()
    }

    private suspend fun session() {
        val done = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(Request.Builder().url(url("/ws")).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                state.value = state.value.copy(connected = true, adb = active == adbUrl())
                scope.launch { runCatching { apps.value = fetchApps("/apps") } }
                refreshAllApps()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = JSONObject(text)
                    when (o.optString("event")) {
                        "schedule" -> schedule.value = o.optJSONObject("data")?.let(::parseSchedule)
                        "notify" -> addNote(o)
                        else -> state.value = parse(o)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(Unit)
            }
        })
        try {
            done.await()
        } finally {
            ws.cancel()
        }
    }

    private fun parse(o: JSONObject): DeckState {
        val p = o.optJSONObject("player")?.let {
            PlayerState(
                status = it.optString("status"),
                player = it.optString("name"),
                artist = it.optString("artist"),
                title = it.optString("title"),
                album = it.optString("album"),
                art = it.optString("art").ifEmpty { null },
                length = it.optDouble("length", 0.0).toFloat(),
                position = it.optDouble("position", 0.0).toFloat(),
            )
        }
        return DeckState(
            connected = true,
            player = p,
            volume = o.optDouble("volume", 0.0).toFloat(),
            muted = o.optBoolean("muted"),
            micMuted = o.optBoolean("mic_muted"),
            adb = active == adbUrl(),
        )
    }

    private fun JSONObject.items(name: String): List<JSONObject> =
        optJSONArray(name)?.let { a -> (0 until a.length()).map(a::getJSONObject) } ?: emptyList()

    private fun parseSchedule(o: JSONObject) = Schedule(
        days = o.items("days").map { d ->
            Day(d.optString("title"), d.items("meetings").map {
                MeetingItem(
                    start = it.optString("start"),
                    end = it.optString("end"),
                    subject = it.optString("subject"),
                    where = it.optString("where"),
                    state = it.optString("state"),
                    badge = it.optString("badge"),
                    cancelled = it.optBoolean("cancelled"),
                    join = it.optString("join"),
                    link = it.optString("link"),
                )
            })
        },
        tasks = o.items("tasks").map {
            TaskItem(
                id = it.optString("id"),
                title = it.optString("title"),
                done = it.optBoolean("done"),
                origin = it.optString("origin"),
                note = it.optString("note"),
                due = it.optString("due"),
                overdue = it.optBoolean("overdue"),
                link = it.optString("link"),
            )
        },
        jira = o.items("jira").map {
            JiraItem(
                key = it.optString("key"),
                title = it.optString("title"),
                status = it.optString("status"),
                due = it.optString("due"),
                overdue = it.optBoolean("overdue"),
                link = it.optString("link"),
            )
        },
        error = o.optString("error"),
    )

    private fun addNote(o: JSONObject) {
        val id = o.getString("id")
        synchronized(seenNotes) {
            if (id in seenNotes) return
            seenNotes.addLast(id)
            if (seenNotes.size > 100) seenNotes.removeFirst()
        }
        val ttl = (o.optDouble("ttl", 30.0) * 1000).toLong()
        val note = Note(
            id, o.optString("app"), o.optString("title"), o.optString("body"),
            o.optString("action"), o.optBoolean("urgent"), SystemClock.elapsedRealtime() + ttl,
        )
        if (background?.invoke(note, ttl) == true) return
        notes.update { it + note }
    }

    /** убрать уведомление — и заодно те, чьё время вышло, пока их заслоняло более новое */
    fun dismiss(id: String) = notes.update { list ->
        val now = SystemClock.elapsedRealtime()
        list.filterNot { it.id == id || it.until <= now }
    }

    /** кнопка на уведомлении: ссылку откроет компьютер */
    fun openNote(id: String) {
        post("/notify/" + URLEncoder.encode(id, "UTF-8") + "/open")
        dismiss(id)
    }

    /** отметить дело: галочка меняется сразу, само дело — в QuickSchedule, он пришлёт новый снимок */
    fun setTaskDone(id: String, done: Boolean) {
        schedule.update { s -> s?.copy(tasks = s.tasks.map { if (it.id == id) it.copy(done = done) else it }) }
        post("/schedule/task/" + URLEncoder.encode(id, "UTF-8") + "?done=$done")
    }

    /** «Войти» в созвон, сообщение в Telegram, задача Jira — тоже на компьютере */
    fun openLink(key: String) = post("/schedule/open/" + URLEncoder.encode(key, "UTF-8"))

    private fun fetchApps(path: String): List<AppEntry> =
        http.newCall(Request.Builder().url(url(path)).build()).execute().use { r ->
            check(r.isSuccessful)
            val arr = JSONArray(r.body!!.string())
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                val id = a.getString("id")
                AppEntry(
                    id, a.getString("name"), a.optString("icon").ifEmpty { null },
                    a.optString("launch").ifEmpty { "/launch/" + URLEncoder.encode(id, "UTF-8") },
                )
            }
        }

    fun refreshAllApps() = scope.launch { runCatching { allApps.value = fetchApps("/desktop") } }

    private fun post(path: String) = scope.launch {
        runCatching {
            http.newCall(
                Request.Builder().url(url(path)).post(ByteArray(0).toRequestBody()).build()
            ).execute().close()
        }
    }

    fun playPause() = post("/player/play-pause")
    fun next() = post("/player/next")
    fun previous() = post("/player/previous")
    fun seek(sec: Float) = post("/player/seek?pos=$sec")
    fun setVolume(v: Float) = post("/volume?v=$v")
    fun toggleMute() = post("/mute")
    fun toggleMic() = post("/mic-mute")
    fun launch(app: AppEntry) = post(app.launch)
}
