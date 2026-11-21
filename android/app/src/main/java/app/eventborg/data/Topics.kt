package app.eventborg.data

import app.eventborg.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun wantedTopics(p: UserPrefs): Set<String> = buildSet {
    when (p.notify) {
        NOTIFY_ALL -> add("all")
        NOTIFY_FAV -> {
            p.favCats.forEach { add("c_$it") }
            p.favSrcs.forEach { add("s_$it") }
        }
    }
    when (p.stories) {
        NOTIFY_ALL -> add("st_all")
        NOTIFY_FAV -> p.favSrcs.forEach { add("st_$it") }
    }
}

private suspend fun Task<Void>.ok(): Boolean = suspendCancellableCoroutine { c ->
    addOnCompleteListener { c.resume(it.isSuccessful) }
}

// Keeps FCM topic subscriptions equal to the settings; failed calls are retried on the next change or app start.
class Topics(private val prefs: Prefs, private val scope: CoroutineScope) {
    @OptIn(FlowPreview::class)
    fun start() {
        if (!BuildConfig.FIREBASE) return
        scope.launch {
            prefs.state.filter { it.loaded }.map(::wantedTopics).distinctUntilChanged().debounce(1500).collect { sync(it) }
        }
    }

    private suspend fun sync(want: Set<String>) {
        val fm = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        var have = prefs.subscribed()
        for (t in have - want) if (fm.unsubscribeFromTopic(t).ok()) have = have - t
        for (t in want - have) if (fm.subscribeToTopic(t).ok()) have = have + t
        prefs.setSubscribed(have)
    }
}
