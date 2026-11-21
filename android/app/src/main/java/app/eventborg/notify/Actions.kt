package app.eventborg.notify

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import app.eventborg.graph
import app.eventborg.ui.openApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The original Instagram/Facebook tap intents of post and group-post alerts, kept in memory only while the process lives.
object Held {
    private val map = object : LinkedHashMap<String, PendingIntent>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?) = size > 30
    }

    @Synchronized fun put(id: String, pi: PendingIntent) {
        map[id] = pi
    }

    @Synchronized fun get(id: String): PendingIntent? = map[id]
}

// Fires the held Instagram/Facebook intent (opens that exact post); falls back to the given page.
fun openHeld(a: Activity, id: String?, fallback: String?) {
    val pi = id?.let(Held::get)
    if (pi != null) {
        val opts: Bundle? = if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        } else {
            null
        }
        if (runCatching { pi.send(a, 0, null, null, null, null, opts) }.isSuccess) return
    }
    fallback?.let { a.openApp(it) }
}

fun openGroupPost(a: Activity, id: String) = openHeld(a, id, a.graph.watch.state.value.posts.firstOrNull { it.id == id }?.url)

class Actions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val g = ctx.graph
        val done = goAsync()
        g.scope.launch(Dispatchers.IO) {
            try {
                when (intent.action) {
                    ACT_SAVE -> intent.getStringExtra(X_ID)?.let { id ->
                        g.prefs.save(id)
                        withTimeoutOrNull(3000) { g.repo.status.first { it.ready } }
                        g.repo.data.value.byId[id]?.let { g.notifier.event(it, saved = true) }
                    }
                    ACT_MUTE -> intent.getStringExtra(X_KEY)?.let { key ->
                        g.watch.setMuted(key, true)
                        g.notifier.cancel(intent.getIntExtra(X_NID, g.notifier.storyId(key)))
                    }
                }
            } finally {
                done.finish()
            }
        }
    }
}

// Notification taps that open another app: a story (marked seen first) or a held group post.
class ActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = graph
        when (intent?.action) {
            ACT_STORY -> {
                intent.getStringExtra(X_KEY)?.let { k -> g.scope.launch(Dispatchers.IO) { g.stories.seen(k) } }
                intent.getStringExtra(X_URL)?.let { openApp(it) }
            }
            ACT_POST -> intent.getStringExtra(X_ID)?.let { openGroupPost(this, it) }
            ACT_OPEN -> openHeld(this, intent.getStringExtra(X_ID), intent.getStringExtra(X_URL))
        }
        finish()
    }
}
