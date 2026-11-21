package app.eventborg.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "prefs")

const val NOTIFY_ALL = "all"
const val NOTIFY_FAV = "fav"
const val NOTIFY_OFF = "off"

// Per-source notification types; a source's switch is on unless "<type>:<id>" is in srcOff.
const val N_EVENTS = "e"
const val N_STORIES = "s"
const val N_POSTS = "p"

data class UserPrefs(
    val loaded: Boolean = false,
    val saved: Set<String> = emptySet(),
    val favCats: Set<String> = emptySet(),
    val favSrcs: Set<String> = emptySet(),
    val filter: Filter = Filter(),
    val notify: String = NOTIFY_ALL,
    val stories: String = NOTIFY_FAV,
    val studentsFirst: Boolean = true,
    val listen: Boolean = false,
    val hideOriginal: Boolean = true,
    val shareStories: Boolean = false,
    val notifAsked: Boolean = false,
    val srcOff: Set<String> = emptySet(),
    val checkPosts: Boolean = true,
) {
    val faves get() = Faves(saved, favCats, favSrcs)

    fun on(type: String, key: String) = "$type:$key" !in srcOff
}

private fun Set<String>.toggle(x: String) = if (x in this) this - x else this + x

class Prefs(ctx: Context, private val scope: CoroutineScope) {
    private val ds = ctx.store

    val state: StateFlow<UserPrefs> = ds.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            UserPrefs(
                loaded = true,
                saved = p[SAVED].orEmpty(),
                favCats = p[FAV_CATS].orEmpty(),
                favSrcs = p[FAV_SRCS].orEmpty(),
                filter = p[FILTER]?.let { runCatching { JsonFmt.decodeFromString(Filter.serializer(), it) }.getOrNull() } ?: Filter(),
                notify = p[NOTIFY] ?: NOTIFY_ALL,
                stories = p[STORIES] ?: NOTIFY_FAV,
                studentsFirst = p[STUDENTS] ?: true,
                listen = p[LISTEN] ?: false,
                hideOriginal = p[HIDE] ?: true,
                shareStories = p[SHARE] ?: false,
                notifAsked = p[ASKED] ?: false,
                srcOff = p[SRC_OFF].orEmpty(),
                checkPosts = p[CHECK_POSTS] ?: true,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, UserPrefs())

    private fun edit(block: (MutablePreferences) -> Unit) {
        scope.launch { runCatching { ds.edit { block(it) } } }
    }

    fun toggleSaved(id: String) = edit { it[SAVED] = it[SAVED].orEmpty().toggle(id) }
    fun toggleCat(c: String) = edit { it[FAV_CATS] = it[FAV_CATS].orEmpty().toggle(c) }
    fun toggleSource(id: String) = edit { it[FAV_SRCS] = it[FAV_SRCS].orEmpty().toggle(id) }
    fun setFilter(f: Filter) = edit { it[FILTER] = JsonFmt.encodeToString(Filter.serializer(), f) }
    fun setNotify(v: String) = edit { it[NOTIFY] = v }
    fun setStories(v: String) = edit { it[STORIES] = v }
    fun setStudentsFirst(v: Boolean) = edit { it[STUDENTS] = v }
    fun setListen(v: Boolean) = edit { it[LISTEN] = v }
    fun setHideOriginal(v: Boolean) = edit { it[HIDE] = v }
    fun setShareStories(v: Boolean) = edit { it[SHARE] = v }
    fun setNotifAsked() = edit { it[ASKED] = true }
    fun setSourceOn(type: String, key: String, on: Boolean) = edit {
        it[SRC_OFF] = if (on) it[SRC_OFF].orEmpty() - "$type:$key" else it[SRC_OFF].orEmpty() + "$type:$key"
    }
    fun setCheckPosts(v: Boolean) = edit { it[CHECK_POSTS] = v }
    suspend fun save(id: String) {
        ds.edit { it[SAVED] = it[SAVED].orEmpty() + id }
    }

    suspend fun subscribed(): Set<String> = ds.data.first()[TOPICS].orEmpty()
    suspend fun setSubscribed(topics: Set<String>) {
        ds.edit { it[TOPICS] = topics }
    }

    private companion object {
        val SAVED = stringSetPreferencesKey("saved")
        val FAV_CATS = stringSetPreferencesKey("fav_cats")
        val FAV_SRCS = stringSetPreferencesKey("fav_srcs")
        val FILTER = stringPreferencesKey("filter")
        val NOTIFY = stringPreferencesKey("notify")
        val STORIES = stringPreferencesKey("stories")
        val TOPICS = stringSetPreferencesKey("topics")
        val STUDENTS = booleanPreferencesKey("students_first")
        val LISTEN = booleanPreferencesKey("listen")
        val HIDE = booleanPreferencesKey("hide_original")
        val SHARE = booleanPreferencesKey("share_stories")
        val ASKED = booleanPreferencesKey("notif_asked")
        val SRC_OFF = stringSetPreferencesKey("src_off")
        val CHECK_POSTS = booleanPreferencesKey("check_posts")
    }
}
