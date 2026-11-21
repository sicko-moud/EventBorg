package app.eventborg.notify

import android.app.PendingIntent
import android.graphics.Bitmap
import app.eventborg.Graph
import app.eventborg.data.Images
import app.eventborg.data.NOTIFY_ALL
import app.eventborg.data.NOTIFY_OFF
import app.eventborg.data.N_POSTS
import app.eventborg.data.N_STORIES
import app.eventborg.data.UserPrefs
import app.eventborg.watch.FB_PKG
import app.eventborg.watch.GroupPost
import app.eventborg.watch.GroupTarget
import app.eventborg.watch.Target
import app.eventborg.watch.WatchState
import app.eventborg.watch.groupTargets
import app.eventborg.watch.looksLikeEvent
import app.eventborg.watch.matchGroup
import app.eventborg.watch.matchPost
import app.eventborg.watch.profileUrl
import app.eventborg.watch.matchStory
import app.eventborg.watch.postSnippet
import app.eventborg.watch.sourceStoryUrl
import app.eventborg.watch.storyTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// One Instagram/Facebook notification, read in memory only; nothing of it is stored unless a watched group's post looks like an event.
class Posted(
    val pkg: String,
    val key: String,
    val title: String,
    val text: String,
    val picture: () -> Bitmap?,
    val tap: PendingIntent?,
    val cancel: () -> Unit,
)

class Detector(private val g: Graph) {
    private val recent = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 50
    }

    fun onPosted(n: Posted) {
        g.scope.launch { runCatching { handle(n) } }
    }

    // Facebook re-posts a notification when its picture arrives; handle each one once.
    private fun duplicate(n: Posted): Boolean = synchronized(recent) {
        val k = n.key + "|" + (n.title + n.text).hashCode()
        val now = System.currentTimeMillis()
        val dup = recent[k]?.let { now - it < 10 * 60_000 } == true
        recent[k] = now
        dup
    }

    private suspend fun handle(n: Posted) {
        val p = g.prefs.state.first { it.loaded }
        if (!p.listen) return
        withTimeoutOrNull(5000) { g.repo.status.first { it.ready } }
        val w = g.watch.state.value
        val sources = g.repo.data.value.sources
        val targets = storyTargets(w, sources)
        matchStory(n.title, n.text, targets)?.let {
            if (!duplicate(n)) story(it, n, p, w)
            return
        }
        if (n.pkg == FB_PKG) matchGroup(n.title, n.text, groupTargets(w))?.let {
            if (!duplicate(n)) group(it, n, p, w)
            return
        }
        matchPost(n.title, n.text, targets)?.let { if (!duplicate(n)) post(it, n, p, w) }
    }

    private suspend fun story(t: Target, n: Posted, p: UserPrefs, w: WatchState) {
        if (t.key in w.muted || !p.on(N_STORIES, t.key)) return
        val src = t.sourceId?.let { id -> g.repo.data.value.srcById[id] }
        val url = src?.let { sourceStoryUrl(it, n.pkg == FB_PKG) } ?: t.url
        val watched = w.profiles.firstOrNull { it.key == t.key }
        val name = watched?.name ?: t.name
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            g.stories.put(t.key, name, url, now)
            if (watched != null) g.watch.caught(t.key)
        }
        if (p.stories != NOTIFY_OFF) {
            val pri = src?.pri == true
            val wanted = watched != null || pri || (src != null && src.id in p.favSrcs) || p.stories == NOTIFY_ALL
            val alert = wanted && withContext(Dispatchers.IO) {
                g.stories.alertedRecently(t.key, now) || g.notifier.story(t.key, name, url, pri).also { if (it) g.stories.markAlerted(t.key, now) }
            }
            // Only hide Instagram's/Facebook's notification when ours stands in for it.
            if (alert && p.hideOriginal) n.cancel()
        }
        if (p.shareStories && src != null && withContext(Dispatchers.IO) { g.stories.relayDue(src.id, now) }) {
            g.api.storySeen(src.id, now)
        }
    }

    // A listed (public) account's post goes to the server, which publishes it through the inbox rules if it is an event;
    // otherwise, or offline, a "New post from" alert. Posts of profiles the user added never leave the phone.
    private suspend fun post(t: Target, n: Posted, p: UserPrefs, w: WatchState) {
        if (t.key in w.muted || !p.on(N_POSTS, t.key)) return
        val src = t.sourceId?.let { id -> g.repo.data.value.srcById[id] }
        val watched = w.profiles.firstOrNull { it.key == t.key }
        val now = System.currentTimeMillis()
        val pic = withContext(Dispatchers.Default) { runCatching { n.picture()?.let { Images.scale(it, 1024) } }.getOrNull() }
        if (src != null && p.checkPosts) {
            val jpeg = pic?.let { withContext(Dispatchers.Default) { Images.base64(Images.jpeg(it, 80)) } }
            if (g.api.postSeen(src.id, now, listOf(n.title, n.text).joinToString("\n").trim(), jpeg) == true) return
        }
        val pri = src?.pri == true
        if (watched == null && !pri && (src == null || src.id !in p.favSrcs)) return
        val id = "p" + now.toString(36)
        n.tap?.let { Held.put(id, it) }
        val url = (if (n.pkg == FB_PKG) src?.fb else null) ?: watched?.url ?: src?.let(::profileUrl) ?: t.url
        val shown = withContext(Dispatchers.IO) { g.notifier.postAlert(t.key, watched?.name ?: t.name, n.text, url, id, pic, pri) }
        if (shown && p.hideOriginal) n.cancel()
    }

    private suspend fun group(gt: GroupTarget, n: Posted, p: UserPrefs, w: WatchState) {
        val group = w.groups.firstOrNull { it.key == gt.key } ?: return
        val snippet = postSnippet(n.text, group.name)
        if (!looksLikeEvent(snippet)) {
            if (p.hideOriginal) n.cancel()
            return
        }
        val now = System.currentTimeMillis()
        val id = now.toString(36) + (n.key.hashCode() and 0xffff).toString(36)
        val pic = withContext(Dispatchers.Default) { runCatching { n.picture()?.let { Images.scale(it, 960) } }.getOrNull() }
        val post = GroupPost(id, group.id, group.name, group.url, snippet.take(1500), now)
        n.tap?.let { Held.put(id, it) }
        val shown = withContext(Dispatchers.IO) {
            g.watch.addPost(post, pic?.let { Images.jpeg(it, 80) })
            g.notifier.groupPost(post, pic)
        }
        if (shown && p.hideOriginal) n.cancel()
    }
}
