package app.eventborg.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.eventborg.MainActivity
import app.eventborg.R
import app.eventborg.data.Ev
import app.eventborg.ui.Lang
import app.eventborg.ui.calendarIntent
import app.eventborg.ui.cardWhen
import app.eventborg.ui.catColor
import app.eventborg.ui.catEmoji
import app.eventborg.ui.catLabel
import app.eventborg.ui.venueText
import app.eventborg.watch.GroupPost

const val CH_EVENTS = "events"
const val CH_STORIES = "stories"
const val CH_GROUPS = "groups"

const val ACT_EVENT = "app.eventborg.EVENT"
const val ACT_CAT = "app.eventborg.CATEGORY"
const val ACT_DRAFT_POST = "app.eventborg.DRAFT_POST"
const val ACT_POSTS = "app.eventborg.GROUP_POSTS"
const val ACT_STORY = "app.eventborg.STORY"
const val ACT_POST = "app.eventborg.OPEN_POST"
const val ACT_SAVE = "app.eventborg.SAVE"
const val ACT_MUTE = "app.eventborg.MUTE"
const val X_ID = "id"
const val X_KEY = "key"
const val X_URL = "url"
const val X_NID = "nid"

private const val PI = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
private const val DAY_MS = 24 * 3600_000L

class Notifier(private val app: Context) {
    private val nm = NotificationManagerCompat.from(app)

    private fun res(): Context = Lang.wrap(app)

    fun channels() {
        val c = res()
        fun ch(id: String, name: Int, desc: Int) = NotificationChannelCompat.Builder(id, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(c.getString(name)).setDescription(c.getString(desc)).build()
        nm.createNotificationChannelsCompat(
            listOf(
                ch(CH_EVENTS, R.string.ch_events, R.string.ch_events_desc),
                ch(CH_STORIES, R.string.ch_stories, R.string.ch_stories_desc),
                ch(CH_GROUPS, R.string.ch_groups, R.string.ch_groups_desc),
            ),
        )
    }

    val enabled get() = nm.areNotificationsEnabled()

    fun channelOn(id: String) = enabled && nm.getNotificationChannelCompat(id)?.importance != NotificationManagerCompat.IMPORTANCE_NONE

    @SuppressLint("MissingPermission")
    private fun post(id: Int, channel: String, n: Notification): Boolean {
        if (!channelOn(channel)) return false
        return runCatching { nm.notify(id, n) }.isSuccess
    }

    fun cancel(id: Int) = nm.cancel(id)

    private fun builder(channel: String) = NotificationCompat.Builder(app, channel)
        .setSmallIcon(R.drawable.ic_stat)
        .setColor(app.getColor(R.color.launcher_bg))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)

    private fun main(action: String, rc: Int, extras: Intent.() -> Unit) = PendingIntent.getActivity(
        app, rc, Intent(app, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras), PI,
    )

    private fun catIcon(c: String): Bitmap {
        val px = (64 * app.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = catColor(c, false).toArgb() })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px * 0.5f
            textAlign = Paint.Align.CENTER
        }
        cv.drawText(catEmoji(c), px / 2f, px / 2f - (tp.fontMetrics.ascent + tp.fontMetrics.descent) / 2, tp)
        return bmp
    }

    private fun whenWhere(c: Context, ev: Ev) =
        listOfNotNull(cardWhen(c, ev, null, c.resources.configuration.locales[0]), venueText(ev.e)).joinToString(" · ")

    fun event(ev: Ev, saved: Boolean): Boolean {
        channels()
        val c = res()
        val e = ev.e
        val nid = "e:${e.id}".hashCode()
        val line = whenWhere(c, ev)
        val b = builder(CH_EVENTS)
            .setLargeIcon(catIcon(e.c))
            .setContentTitle(e.t)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(main(ACT_EVENT, nid) { putExtra(X_ID, e.id) })
        if (!saved) {
            val save = Intent(app, Actions::class.java).setAction(ACT_SAVE).putExtra(X_ID, e.id)
            b.addAction(0, c.getString(R.string.save_short), PendingIntent.getBroadcast(app, nid, save, PI))
        }
        b.addAction(0, c.getString(R.string.add_calendar), PendingIntent.getActivity(app, nid, calendarIntent(ev), PI))
        return post(nid, CH_EVENTS, b.build())
    }

    fun eventGroup(cat: String, count: Int, items: List<Ev>): Boolean {
        channels()
        val c = res()
        val nid = "c:$cat".hashCode()
        val title = c.resources.getQuantityString(R.plurals.n_new_events, count, count, c.getString(catLabel(cat)))
        val style = NotificationCompat.InboxStyle()
        items.take(6).forEach { style.addLine(it.e.t + " · " + whenWhere(c, it)) }
        val b = builder(CH_EVENTS)
            .setLargeIcon(catIcon(cat))
            .setContentTitle(title)
            .setContentText(items.joinToString(", ") { it.e.t })
            .setStyle(style)
            .setNumber(count)
            .setContentIntent(main(ACT_CAT, nid) { putExtra(X_ID, cat) })
        return post(nid, CH_EVENTS, b.build())
    }

    fun storyId(key: String) = "s:$key".hashCode()

    fun story(key: String, name: String, url: String): Boolean {
        channels()
        val c = res()
        val nid = storyId(key)
        val open = Intent(app, ActionActivity::class.java).setAction(ACT_STORY).putExtra(X_KEY, key).putExtra(X_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mute = Intent(app, Actions::class.java).setAction(ACT_MUTE).putExtra(X_KEY, key).putExtra(X_NID, nid)
        val b = builder(CH_STORIES)
            .setContentTitle(c.getString(R.string.story_title, name))
            .setContentText(c.getString(R.string.story_text))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setTimeoutAfter(DAY_MS)
            .setContentIntent(PendingIntent.getActivity(app, nid, open, PI))
            .addAction(0, c.getString(R.string.mute_source), PendingIntent.getBroadcast(app, nid, mute, PI))
        return post(nid, CH_STORIES, b.build())
    }

    fun groupPost(p: GroupPost, pic: Bitmap?): Boolean {
        channels()
        val c = res()
        val nid = "g:${p.id}".hashCode()
        val open = PendingIntent.getActivity(
            app, nid, Intent(app, ActionActivity::class.java).setAction(ACT_POST).putExtra(X_ID, p.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PI,
        )
        val b = builder(CH_GROUPS)
            .setContentTitle(c.getString(R.string.gp_title, p.groupName))
            .setContentText(p.text)
            .setStyle(
                if (pic != null) NotificationCompat.BigPictureStyle().bigPicture(pic).setSummaryText(p.text)
                else NotificationCompat.BigTextStyle().bigText(p.text),
            )
            .setContentIntent(open)
            .addAction(0, c.getString(R.string.open), open)
            .addAction(0, c.getString(R.string.add_as_event), main(ACT_DRAFT_POST, nid) { putExtra(X_ID, p.id) })
        if (pic != null) b.setLargeIcon(pic)
        return post(nid, CH_GROUPS, b.build())
    }
}
