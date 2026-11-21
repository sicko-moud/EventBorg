package app.eventborg.watch

import android.content.Context
import app.eventborg.data.JsonFmt
import app.eventborg.data.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

private const val WEEK_MS = 7 * 24 * 3600_000L

// src: the listed source this profile belongs to (then story alerts use the source id and its handles/names).
@Serializable
data class Profile(
    val net: String,
    val handle: String? = null,
    val name: String,
    val url: String,
    val src: String? = null,
    val caught: Boolean = false,
) {
    val key get() = src ?: "w:$net:${handle ?: url}"
    val storyUrl get() = if (net == IG && handle != null) "https://www.instagram.com/stories/$handle/" else url
}

@Serializable
data class Group(val id: String, val name: String, val url: String) {
    val key get() = "g:$id"
}

@Serializable
data class GroupPost(val id: String, val group: String, val groupName: String, val url: String, val text: String, val ts: Long, val pic: Boolean = false)

@Serializable
data class WatchState(
    val profiles: List<Profile> = emptyList(),
    val groups: List<Group> = emptyList(),
    val muted: Set<String> = emptySet(),
    val posts: List<GroupPost> = emptyList(),
) {
    val watchedSources: Set<String> get() = profiles.mapNotNull { it.src }.toSet()
}

val STORY_KINDS = setOf("venue", "organizer", "business")

fun storyEligible(s: Source) = s.kind in STORY_KINDS && (s.ig != null || s.fb != null)

fun sourceStoryUrl(s: Source, fromFacebook: Boolean = false): String? = when {
    fromFacebook && s.fb != null -> s.fb
    s.ig != null -> "https://www.instagram.com/stories/${s.ig.trimStart('@')}/"
    else -> s.fb
}

fun profileFor(s: Source) = Profile(
    net = if (s.ig != null) IG else FB,
    handle = s.ig?.trimStart('@')?.lowercase(),
    name = s.n,
    url = s.ig?.let { "https://www.instagram.com/${it.trimStart('@')}/" } ?: s.fb!!,
    src = s.id,
)

fun storyTargets(w: WatchState, sources: List<Source>): List<Target> {
    val watched = w.profiles.filter { it.src != null }.associateBy { it.src }
    val listed = sources.filter(::storyEligible)
    val ids = listed.map { it.id }.toSet()
    return listed.map { s ->
        Target(s.id, s.n, sourceStoryUrl(s)!!, s.id, listOfNotNull(s.ig) + s.names.ifEmpty { listOf(s.n) } + listOfNotNull(watched[s.id]?.name))
    } + w.profiles.filter { it.src == null || it.src !in ids }.map { p ->
        Target(p.key, p.name, p.storyUrl, null, listOfNotNull(p.handle?.takeIf { h -> h.any { !it.isDigit() } }, p.name))
    }
}

fun groupTargets(w: WatchState) = w.groups.filter { it.key !in w.muted }.map { GroupTarget(it.key, it.name, it.url) }

// Watch list and group posts: on this phone only (noBackupFilesDir, never sent anywhere).
class WatchStore(ctx: Context) {
    private val file = File(ctx.noBackupFilesDir, "watch.json")
    val picDir = File(ctx.noBackupFilesDir, "gp")
    private val _state = MutableStateFlow(
        runCatching { JsonFmt.decodeFromString(WatchState.serializer(), file.readText()) }.getOrDefault(WatchState()),
    )
    val state: StateFlow<WatchState> = _state

    @Synchronized
    private fun update(block: (WatchState) -> WatchState) {
        val next = block(_state.value)
        if (next == _state.value) return
        _state.value = next
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonFmt.encodeToString(WatchState.serializer(), next))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun pic(id: String) = File(picDir, "$id.jpg")

    fun addProfile(p: Profile) = update { s -> s.copy(profiles = s.profiles.filter { it.key != p.key } + p) }
    fun removeProfile(key: String) = update { s -> s.copy(profiles = s.profiles.filter { it.key != key }, muted = s.muted - key) }
    fun caught(key: String) = update { s -> s.copy(profiles = s.profiles.map { if (it.key == key && !it.caught) it.copy(caught = true) else it }) }

    fun addGroup(g: Group) = update { s -> s.copy(groups = s.groups.filter { it.id != g.id } + g) }
    fun removeGroup(id: String) = update { s ->
        s.posts.filter { it.group == id }.forEach { pic(it.id).delete() }
        s.copy(groups = s.groups.filter { it.id != id }, muted = s.muted - "g:$id", posts = s.posts.filter { it.group != id })
    }

    fun setMuted(key: String, on: Boolean) = update { s -> s.copy(muted = if (on) s.muted + key else s.muted - key) }

    fun addPost(p: GroupPost, jpeg: ByteArray?) {
        val saved = jpeg != null && runCatching {
            picDir.mkdirs()
            pic(p.id).writeBytes(jpeg)
        }.isSuccess
        update { s -> s.copy(posts = (listOf(p.copy(pic = saved)) + s.posts.filter { it.id != p.id }).take(100)) }
        prune()
    }

    fun removePost(id: String) {
        pic(id).delete()
        update { s -> s.copy(posts = s.posts.filter { it.id != id }) }
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        val keep = _state.value.posts.filter { now - it.ts < WEEK_MS }
        update { it.copy(posts = keep) }
        val ids = keep.map { "${it.id}.jpg" }.toSet()
        picDir.listFiles()?.forEach { if (it.name !in ids) it.delete() }
    }
}
