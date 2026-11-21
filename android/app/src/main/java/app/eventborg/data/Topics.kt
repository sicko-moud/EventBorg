package app.eventborg.data

import app.eventborg.BuildConfig
import app.eventborg.watch.WatchState
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

// Watched listed sources count as favourites for story alerts; muted ones get no topic.
fun wantedTopics(p: UserPrefs, w: WatchState = WatchState()): Set<String> = buildSet {
    when (p.notify) {
        NOTIFY_ALL -> add("all")
        NOTIFY_FAV -> {
            p.favCats.forEach { add("c_$it") }
            p.favSrcs.forEach { add("s_$it") }
        }
    }
    when (p.stories) {
        NOTIFY_ALL -> add("st_all")
        NOTIFY_FAV -> (p.favSrcs + w.watchedSources - w.muted).forEach { add("st_$it") }
    }
}

private suspend fun Task<Void>.ok(): Boolean = suspendCancellableCoroutine { c ->
    addOnCompleteListener { c.resume(it.isSuccessful) }
}

// Keeps FCM topic subscriptions equal to the settings; failed calls are retried on the next change or app start.
class Topics(private val prefs: Prefs, private val watch: StateFlow<WatchState>, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    @Volatile private var wanted: Set<String>? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (!BuildConfig.FIREBASE) return
        scope.launch {
            combine(prefs.state.filter { it.loaded }, watch) { p, w -> wantedTopics(p, w) }
                .distinctUntilChanged().debounce(1500).collect {
                    wanted = it
                    sync(it)
                }
        }
    }

    // A new FCM token has no subscriptions (fresh install, restore from backup): forget what we think we have.
    fun reset() {
        if (!BuildConfig.FIREBASE) return
        scope.launch {
            prefs.setSubscribed(emptySet())
            wanted?.let { sync(it) }
        }
    }

    private suspend fun sync(want: Set<String>) = mutex.withLock {
        val fm = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return@withLock
        var have = prefs.subscribed()
        for (t in have - want) if (fm.unsubscribeFromTopic(t).ok()) have = have - t
        for (t in want - have) if (fm.subscribeToTopic(t).ok()) have = have + t
        prefs.setSubscribed(have)
    }
}
