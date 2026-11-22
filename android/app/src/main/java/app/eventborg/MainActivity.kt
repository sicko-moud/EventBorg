package app.eventborg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import app.eventborg.data.Filter
import app.eventborg.notify.ACT_CAT
import app.eventborg.notify.ACT_DRAFT_POST
import app.eventborg.notify.ACT_EVENT
import app.eventborg.notify.ACT_POSTS
import app.eventborg.notify.X_ID
import app.eventborg.ui.AppNav
import app.eventborg.ui.DetailR
import app.eventborg.ui.DraftR
import app.eventborg.ui.FeedR
import app.eventborg.ui.GroupPostsR
import app.eventborg.ui.Lang
import app.eventborg.ui.WatchR
import app.eventborg.ui.theme.GraphiteTheme
import app.eventborg.ui.theme.warmFonts
import app.eventborg.watch.isWatchShare
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val links = MutableStateFlow<Any?>(null)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        warmFonts(this)
        // Dark only: light bar icons over the transparent bars, whatever the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route(intent)
        setContent { GraphiteTheme { AppNav(links) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    override fun onStart() {
        super.onStart()
        graph.repo.onStart()
    }

    override fun onStop() {
        graph.repo.onStop()
        super.onStop()
    }

    private fun route(i: Intent?) {
        val target: Any = when (i?.action) {
            ACT_EVENT -> i.getStringExtra(X_ID)?.let { DetailR(it) }
            ACT_CAT -> i.getStringExtra(X_ID)?.let {
                graph.prefs.setFilter(Filter(cats = setOf(it)))
                FeedR
            }
            ACT_DRAFT_POST -> i.getStringExtra(X_ID)?.let { DraftR(post = it) }
            ACT_POSTS -> GroupPostsR
            Intent.ACTION_SEND -> shared(i)
            else -> null
        } ?: return
        links.value = target
    }

    // Shared profile/group links go to the watch list; everything else becomes an event draft.
    private fun shared(i: Intent): Any? {
        val text = (i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getStringExtra(Intent.EXTRA_SUBJECT))?.trim().orEmpty()
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        if (uri == null && text.isEmpty()) return null
        if (uri == null && isWatchShare(text)) return WatchR(link = text)
        return DraftR(text = text.take(4000), img = uri?.toString())
    }
}
