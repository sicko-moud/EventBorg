package app.eventborg.notify

import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import app.eventborg.Graph
import app.eventborg.data.Event
import app.eventborg.data.JsonFmt
import app.eventborg.data.NOTIFY_OFF
import app.eventborg.data.toEv
import app.eventborg.graph
import app.eventborg.watch.FB_PKG
import app.eventborg.watch.IG_PKG
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer

class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) = Push(applicationContext.graph).handle(message.data)

    override fun onNewToken(token: String) = applicationContext.graph.topics.reset()
}

// FCM data messages: "event" (one new event), "group" (several in one category), "story" (relayed story alert).
class Push(private val g: Graph) {
    fun handle(d: Map<String, String>) {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(8000) {
                val p = g.prefs.state.first { it.loaded }
                when (d["type"]) {
                    "event" -> {
                        val e = d["ev"]?.let { runCatching { JsonFmt.decodeFromString(Event.serializer(), it) }.getOrNull() } ?: return@withTimeoutOrNull
                        g.repo.insertPushed(e)
                        val ev = toEv(e)
                        if (p.notify != NOTIFY_OFF && ev != null) g.notifier.event(ev, e.id in p.saved)
                    }
                    "group" -> {
                        if (p.notify == NOTIFY_OFF) return@withTimeoutOrNull
                        val evs = d["evs"]?.let { runCatching { JsonFmt.decodeFromString(ListSerializer(Event.serializer()), it) }.getOrNull() }
                            .orEmpty().mapNotNull(::toEv)
                        val n = d["n"]?.toIntOrNull() ?: evs.size
                        if (evs.isNotEmpty()) g.notifier.eventGroup(d["c"] ?: "other", n, evs)
                    }
                    "story" -> {
                        val id = d["sourceId"] ?: return@withTimeoutOrNull
                        val name = d["name"] ?: id
                        val url = d["url"] ?: return@withTimeoutOrNull
                        val ts = d["ts"]?.toLongOrNull() ?: System.currentTimeMillis()
                        g.stories.put(id, name, url, ts)
                        g.stories.relayed(id, ts)
                        if (p.stories == NOTIFY_OFF || id in g.watch.state.value.muted || g.stories.alertedRecently(id)) return@withTimeoutOrNull
                        if (g.notifier.story(id, name, url)) g.stories.markAlerted(id)
                    }
                }
            }
        }
    }
}

// Opt-in only: the component ships disabled and is enabled when the user accepts the disclosure.
class StoryListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg != IG_PKG && pkg != FB_PKG) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val key = sbn.key
        applicationContext.graph.detector.onPosted(
            Posted(pkg, key, title, text, { picture(ex) }, n.contentIntent) { runCatching { cancelNotification(key) } },
        )
    }

    @Suppress("DEPRECATION")
    private fun picture(ex: Bundle): Bitmap? {
        val direct = runCatching {
            if (Build.VERSION.SDK_INT >= 33) ex.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            else ex.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }.getOrNull()
        if (direct != null || Build.VERSION.SDK_INT < 31) return direct
        return runCatching { (ex.getParcelable(Notification.EXTRA_PICTURE_ICON) as? Icon)?.loadDrawable(this)?.toBitmap() }.getOrNull()
    }
}

object Listen {
    fun component(ctx: Context) = ComponentName(ctx, StoryListener::class.java)

    fun granted(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun setEnabled(ctx: Context, on: Boolean) {
        val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val pm = ctx.packageManager
        if (pm.getComponentEnabledSetting(component(ctx)) != want) {
            pm.setComponentEnabledSetting(component(ctx), want, PackageManager.DONT_KILL_APP)
        }
    }

    fun openSettings(ctx: Context) {
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())
        } else {
            null
        }
        for (i in listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            try {
                ctx.startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    fun openAppInfo(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    fun openAppNotifications(ctx: Context, pkg: String) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
    }.isSuccess
}
