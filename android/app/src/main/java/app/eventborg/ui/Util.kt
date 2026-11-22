package app.eventborg.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.CalendarContract
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.eventborg.R
import app.eventborg.data.Ev
import app.eventborg.data.Event
import app.eventborg.data.Section
import app.eventborg.data.ZONE
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Lang {
    private const val KEY = "lang"
    private fun sp(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',').substringBefore('-')
        } else {
            sp(ctx).getString(KEY, "") ?: ""
        }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            sp(activity).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = sp(base).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val loc = Locale.forLanguageTag(tag)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return base.createConfigurationContext(cfg)
    }
}

@Composable
fun uiLocale(): Locale = LocalConfiguration.current.locales[0]

fun isDanish(locale: Locale) = locale.language == "da"

@Composable
fun rememberNow(): ZonedDateTime {
    var now by remember { mutableStateOf(ZonedDateTime.now(ZONE).truncatedTo(ChronoUnit.MINUTES)) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = ZonedDateTime.now(ZONE).truncatedTo(ChronoUnit.MINUTES)
                delay(60_000 - System.currentTimeMillis() % 60_000)
            }
        }
    }
    return now
}

// --- formatting ---------------------------------------------------------------------------------------------

private fun dayFmt(locale: Locale) = DateTimeFormatter.ofPattern(if (isDanish(locale)) "EEE d. MMM" else "EEE d MMM", locale)
private fun longDayFmt(locale: Locale) = DateTimeFormatter.ofPattern(if (isDanish(locale)) "EEEE d. MMMM yyyy" else "EEEE d MMMM yyyy", locale)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

fun timeText(ctx: Context, ev: Ev): String {
    if (ev.e.ad) return ctx.getString(R.string.all_day)
    val sameDay = ev.e.e != null && ev.end.toLocalDate() == ev.start.toLocalDate()
    return ev.start.format(TIME) + if (sameDay) "–" + ev.end.format(TIME) else ""
}

fun cardWhen(ctx: Context, ev: Ev, section: Section?, locale: Locale): String = when (section) {
    Section.TODAY, Section.TOMORROW -> if (ev.start.toLocalDate() < ZonedDateTime.now(ZONE).toLocalDate()) {
        ctx.getString(R.string.until, ev.end.minusSeconds(1).format(dayFmt(locale)))
    } else {
        timeText(ctx, ev)
    }
    Section.ONGOING -> ctx.getString(R.string.until, ev.end.minusSeconds(1).format(dayFmt(locale)))
    else -> ev.start.format(dayFmt(locale)).replaceFirstChar { it.titlecase(locale) } + " · " + timeText(ctx, ev)
}

fun detailWhen(ctx: Context, ev: Ev, locale: Locale): String {
    val day = ev.start.format(longDayFmt(locale)).replaceFirstChar { it.titlecase(locale) }
    val lastDay = ev.end.minusSeconds(1).toLocalDate()
    if (ev.e.e != null && lastDay != ev.start.toLocalDate()) {
        val endDay = ev.end.minusSeconds(1).format(longDayFmt(locale))
        return if (ev.e.ad) "$day – $endDay" else "$day ${ev.start.format(TIME)} – $endDay ${ev.end.format(TIME)}"
    }
    return "$day · ${timeText(ctx, ev)}"
}

fun priceText(ctx: Context, e: Event): String? {
    val p = e.p ?: return null
    if (p.free == true) return ctx.getString(R.string.free)
    val min = p.min?.let { "%,.0f".format(Locale.ROOT, it).replace(',', '.') }
    val max = p.max?.takeIf { it != p.min }?.let { "%,.0f".format(Locale.ROOT, it).replace(',', '.') }
    return when {
        min != null && max != null -> ctx.getString(R.string.price_range, min, max)
        min != null -> ctx.getString(R.string.price_from, min)
        else -> null
    }
}

fun venueText(e: Event): String? {
    val v = e.v ?: return null
    val town = v.town?.takeIf { t -> v.n?.contains(t, ignoreCase = true) != true }
    return listOfNotNull(v.n, town).joinToString(", ").ifEmpty { null }
}

fun ago(ctx: Context, t: ZonedDateTime?, now: ZonedDateTime = ZonedDateTime.now(ZONE)): String? {
    t ?: return null
    val m = Duration.between(t, now).toMinutes().coerceAtLeast(0)
    return when {
        m < 2 -> ctx.getString(R.string.just_now)
        m < 60 -> ctx.getString(R.string.min_ago, m)
        m < 48 * 60 -> ctx.getString(R.string.h_ago, m / 60)
        else -> ctx.getString(R.string.d_ago, m / (24 * 60))
    }
}

fun summary(e: Event, locale: Locale): String? {
    val s = if (isDanish(locale)) e.sum?.da ?: e.sum?.en else e.sum?.en ?: e.sum?.da
    if (!s.isNullOrBlank()) return s
    val d = e.desc?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (d.length <= 200) return d.ifEmpty { null }
    return d.take(200).substringBeforeLast(' ') + "…"
}

// --- categories, sources ------------------------------------------------------------------------------------

fun catLabel(c: String): Int = when (c) {
    "music" -> R.string.cat_music
    "nightlife" -> R.string.cat_nightlife
    "theatre_culture" -> R.string.cat_theatre_culture
    "exhibitions" -> R.string.cat_exhibitions
    "food_drink" -> R.string.cat_food_drink
    "sports" -> R.string.cat_sports
    "family_kids" -> R.string.cat_family_kids
    "students" -> R.string.cat_students
    "talks_learning" -> R.string.cat_talks_learning
    "markets" -> R.string.cat_markets
    "outdoor_nature" -> R.string.cat_outdoor_nature
    "festivals" -> R.string.cat_festivals
    "community" -> R.string.cat_community
    "business" -> R.string.cat_business
    else -> R.string.cat_other
}

// Category art: the emoji is drawn without colour on the raised tone (Graphite), so it only hints at the category.
private val CAT_ART = mapOf(
    "music" to "🎵", "nightlife" to "🌙", "theatre_culture" to "🎭",
    "exhibitions" to "🖼️", "food_drink" to "🍽️", "sports" to "⚽",
    "family_kids" to "🧸", "students" to "🎓", "talks_learning" to "🎤",
    "markets" to "🛍️", "outdoor_nature" to "🌲", "festivals" to "🎪",
    "community" to "🤝", "business" to "💼",
)

fun catEmoji(c: String) = CAT_ART[c] ?: "✨"

fun kindLabel(kind: String): Int = when (kind) {
    "ig_dm", "ig_post" -> R.string.kind_ig
    "wa" -> R.string.kind_wa
    "fb" -> R.string.kind_fb
    "app" -> R.string.kind_app
    else -> R.string.kind_web
}

fun sourceKindLabel(kind: String): Int = when (kind) {
    "organizer" -> R.string.skind_organizer
    "business" -> R.string.skind_business
    else -> R.string.skind_venue
}

// --- intents ------------------------------------------------------------------------------------------------

fun Context.start(i: Intent) {
    try {
        startActivity(i)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
    }
}

fun Context.openWeb(url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
    } catch (_: ActivityNotFoundException) {
        start(Intent(Intent.ACTION_VIEW, uri))
    }
}

fun Context.openApp(url: String) = start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

fun Context.openMap(e: Event) {
    val v = e.v ?: return
    val label = listOfNotNull(v.n, v.addr, v.town).joinToString(", ")
    val uri = if (v.lat != null && v.lng != null) "geo:${v.lat},${v.lng}?q=${v.lat},${v.lng}(${Uri.encode(v.n ?: label)})"
    else "geo:0,0?q=" + Uri.encode("$label, Denmark")
    start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
}

fun Context.addToCalendar(ev: Ev) = start(calendarIntent(ev))

fun calendarIntent(ev: Ev): Intent {
    val e = ev.e
    return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, e.t)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.start.toInstant().toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.end.toInstant().toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.ad)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, listOfNotNull(e.v?.n, e.v?.addr, e.v?.town).joinToString(", "))
        .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(e.url ?: e.src.firstOrNull()?.url).joinToString("\n"))
}

fun Context.share(ev: Ev, locale: Locale) {
    val e = ev.e
    val text = listOfNotNull(e.t, listOfNotNull(detailWhen(this, ev, locale), venueText(e)).joinToString(" · "), e.url ?: e.src.firstOrNull()?.url)
        .joinToString("\n")
    start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

fun instagramUrl(handle: String) = "https://www.instagram.com/${handle.trimStart('@')}/"

// --- icons not in material-icons-core -----------------------------------------------------------------------

private fun icon(name: String, d: String) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(d), fill = SolidColor(Color.Black)).build()

object Ic {
    val StarBorder by lazy {
        icon("StarBorder", "M22,9.24l-7.19,-0.62L12,2L9.19,8.63L2,9.24l5.46,4.73L5.82,21L12,17.27L18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27l1,-4.28l-3.32,-2.88l4.38,-0.38L12,6.1l1.71,4.04l4.38,0.38l-3.32,2.88l1,4.28L12,15.4z")
    }
    val Filter by lazy { icon("FilterList", "M10,18h4v-2h-4v2zM3,6v2h18L21,6L3,6zM6,13h12v-2L6,11v2z") }
    val OpenInNew by lazy {
        icon("OpenInNew", "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z")
    }
}
