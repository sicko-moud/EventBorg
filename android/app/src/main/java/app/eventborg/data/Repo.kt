package app.eventborg.data

import android.content.Context
import app.eventborg.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZonedDateTime

class Data(
    val events: List<Ev> = emptyList(),
    val sources: List<Source> = emptyList(),
    val gen: ZonedDateTime? = null,
) {
    val byId: Map<String, Ev> by lazy { events.associateBy { it.id } }
    val srcById: Map<String, Source> by lazy { sources.associateBy { it.id } }
}

enum class Problem { OFFLINE, SERVER }

data class Status(val ready: Boolean = false, val refreshing: Boolean = false, val problem: Problem? = null)

class Repo(
    ctx: Context,
    private val http: () -> OkHttpClient,
    private val scope: CoroutineScope,
    private val alongside: () -> Unit = {},
) {
    private val dir: File = ctx.noBackupFilesDir
    private val _data = MutableStateFlow(Data())
    private val _status = MutableStateFlow(Status())
    val data: StateFlow<Data> = _data
    val status: StateFlow<Status> = _status
    private var job: Job? = null
    private var loadJob: Job? = null
    private val lock = Any()
    private var lastOk = 0L
    @Volatile private var foreground = false

    // Reads the cache only: the network is used once the app is on screen (onStart), never for a push or listener start.
    fun load() {
        loadJob = scope.launch {
            withContext(Dispatchers.IO) { runCatching { publish(read(EVENTS), read(SOURCES)) } }
            _status.update { it.copy(ready = true) }
        }
    }

    // An event that arrived by push shows up at once and stays until a newer events.json replaces it.
    fun insertPushed(e: Event) = synchronized(lock) {
        val list = readPushed().filter { it.ev.id != e.id } + Pushed(System.currentTimeMillis(), e)
        writePushed(list.takeLast(50))
        runCatching { publish(read(EVENTS), read(SOURCES)) }
        Unit
    }

    private fun readPushed(): List<Pushed> =
        read(PUSHED)?.let { runCatching { JsonFmt.decodeFromString(PUSHED_LIST, it) }.getOrNull() }.orEmpty()

    private fun writePushed(list: List<Pushed>) {
        runCatching { File(dir, PUSHED).writeText(JsonFmt.encodeToString(PUSHED_LIST, list)) }
    }

    // A job that is only waiting to retry gets replaced; one that is downloading is left alone.
    fun refresh() {
        if (job?.isActive == true && _status.value.refreshing) return
        job?.cancel()
        _status.update { it.copy(refreshing = true) }
        job = scope.launch {
            loadJob?.join()
            fetch()
        }
    }

    fun onStart() {
        foreground = true
        if (System.currentTimeMillis() - lastOk > 5 * 60_000) refresh()
    }

    fun onStop() {
        foreground = false
    }

    // While the app is on screen, an offline result is retried a few times (network often returns seconds later).
    private suspend fun fetch() {
        for (wait in RETRY_MS) {
            if (fetchOnce() != Problem.OFFLINE || !foreground) return
            delay(wait)
            if (!foreground) return
        }
        fetchOnce()
    }

    private suspend fun fetchOnce(): Problem? {
        _status.update { it.copy(refreshing = true) }
        val problem = withContext(Dispatchers.IO) {
            try {
                val (e, s) = coroutineScope {
                    launch { runCatching { alongside() } }
                    val ev = async { download(EVENTS) }
                    val src = async { download(SOURCES) }
                    ev.await() to src.await()
                }
                if (e != null || s != null) publish(e ?: read(EVENTS), s ?: read(SOURCES))
                lastOk = System.currentTimeMillis()
                null
            } catch (c: CancellationException) {
                throw c
            } catch (_: IOException) {
                Problem.OFFLINE
            } catch (_: Exception) {
                File(dir, "$EVENTS.etag").delete()
                File(dir, "$SOURCES.etag").delete()
                Problem.SERVER
            }
        }
        _status.update { it.copy(ready = true, refreshing = false, problem = problem) }
        return problem
    }

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    // Returns the new body, or null when the server says our copy is current (304).
    private fun download(name: String): String? {
        val file = File(dir, name)
        val tag = File(dir, "$name.etag")
        val req = Request.Builder().url(BuildConfig.DATA_URL + name).apply {
            if (file.exists() && tag.exists()) header("If-None-Match", tag.readText())
        }.build()
        http().newCall(req).execute().use { r ->
            if (r.code == 304) return null
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body.string()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            r.header("ETag")?.let { tag.writeText(it) } ?: tag.delete()
            return body
        }
    }

    private fun publish(events: String?, sources: String?) = synchronized(lock) {
        val now = ZonedDateTime.now(ZONE)
        val feed = events?.let { JsonFmt.decodeFromString(Feed.serializer(), it) }
        val src = sources?.let { JsonFmt.decodeFromString(SourcesFile.serializer(), it) }
        val gen = feed?.gen?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZONE) }.getOrNull() }
        val genMs = gen?.toInstant()?.toEpochMilli() ?: 0L
        val pushed = readPushed()
        val live = pushed.filter { it.at > genMs }
        if (live.size != pushed.size) writePushed(live)
        val listed = feed?.events.orEmpty()
        val ids = listed.map { it.id }.toSet()
        val all = listed + live.map { it.ev }.filter { it.id !in ids }
        val evs = all.distinctBy { it.id }.mapNotNull(::toEv).filter { it.end.isAfter(now) }.sortedBy { it.start }
        _data.value = Data(events = evs, sources = src?.sources.orEmpty().sortedBy { it.n.lowercase() }, gen = gen)
    }

    @Serializable
    private class Pushed(val at: Long, val ev: Event)

    private companion object {
        const val EVENTS = "events.json"
        const val SOURCES = "sources.json"
        const val PUSHED = "pushed.json"
        val PUSHED_LIST = ListSerializer(Pushed.serializer())
        val RETRY_MS = longArrayOf(3_000, 10_000, 30_000)
    }
}
