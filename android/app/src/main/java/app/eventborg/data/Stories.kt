package app.eventborg.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

const val STORY_COOLDOWN = 3 * 3600_000L
private const val DAY_MS = 24 * 3600_000L

// key: a listed source id, or "w:<net>:<handle>" for a profile only this phone watches.
@Serializable
data class StoryItem(val key: String, val name: String, val url: String, val ts: Long, val seen: Boolean = false)

@Serializable
private data class StoriesFile(
    val items: List<StoryItem> = emptyList(),
    val alerted: Map<String, Long> = emptyMap(),
    val relayed: Map<String, Long> = emptyMap(),
)

// Story circles of the last 24 h (from GET /stories, pushes and local detections) and the alert/relay cooldowns.
class Stories(ctx: Context) {
    private val file = File(ctx.noBackupFilesDir, "stories.json")
    private var f = runCatching { JsonFmt.decodeFromString(StoriesFile.serializer(), file.readText()) }.getOrDefault(StoriesFile())
    private val _items = MutableStateFlow(f.items)
    val items: StateFlow<List<StoryItem>> = _items

    private fun commit(next: StoriesFile, now: Long = System.currentTimeMillis()) {
        f = next.copy(
            items = next.items.filter { now - it.ts < DAY_MS }.sortedByDescending { it.ts },
            alerted = next.alerted.filterValues { now - it < STORY_COOLDOWN },
            relayed = next.relayed.filterValues { now - it < STORY_COOLDOWN },
        )
        _items.value = f.items
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonFmt.encodeToString(StoriesFile.serializer(), f))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun merged(items: List<StoryItem>, n: StoryItem): List<StoryItem> {
        val old = items.firstOrNull { it.key == n.key }
        val next = if (old != null && old.ts >= n.ts - 60_000) old.copy(ts = maxOf(old.ts, n.ts)) else n
        return items.filter { it.key != n.key } + next
    }

    @Synchronized
    fun put(key: String, name: String, url: String, ts: Long = System.currentTimeMillis()) =
        commit(f.copy(items = merged(f.items, StoryItem(key, name, url, ts))))

    @Synchronized
    fun remote(list: List<RemoteStory>) {
        var items = f.items
        for (r in list) if (r.url != null) items = merged(items, StoryItem(r.sourceId, r.name, r.url, r.ts))
        if (items != f.items) commit(f.copy(items = items))
    }

    @Synchronized
    fun seen(key: String) = commit(f.copy(items = f.items.map { if (it.key == key) it.copy(seen = true) else it }))

    @Synchronized
    fun markAlerted(key: String, now: Long = System.currentTimeMillis()) = commit(f.copy(alerted = f.alerted + (key to now)), now)

    @Synchronized
    fun alertedRecently(key: String, now: Long = System.currentTimeMillis()) = f.alerted[key]?.let { now - it < STORY_COOLDOWN } == true

    // Relay a local sighting at most once per cooldown, and not when the server already pushed one.
    @Synchronized
    fun relayDue(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = f.relayed[key]
        if (last != null && now - last < STORY_COOLDOWN) return false
        commit(f.copy(relayed = f.relayed + (key to now)), now)
        return true
    }

    @Synchronized
    fun relayed(key: String, ts: Long) = commit(f.copy(relayed = f.relayed + (key to ts)))
}
