package app.eventborg.data

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

val ZONE: ZoneId = ZoneId.of("Europe/Copenhagen")

class Ev(val e: Event, val start: ZonedDateTime, val end: ZonedDateTime, val key: String) {
    val id get() = e.id
    val long = ChronoUnit.DAYS.between(start.toLocalDate(), end.minusSeconds(1).toLocalDate()) > 3
    val cancelled get() = e.st == "cancelled"
}

private fun parseTime(s: String?): ZonedDateTime? =
    s?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZONE) }.getOrNull() }

fun toEv(e: Event): Ev? {
    val start = parseTime(e.s) ?: return null
    val raw = parseTime(e.e)
    val end = when {
        e.ad -> if (raw != null && raw.toLocalTime() != LocalTime.MIDNIGHT) raw
        else (raw ?: start).toLocalDate().plusDays(1).atStartOfDay(ZONE)
        raw == null || !raw.isAfter(start) -> start.plusHours(2)
        else -> raw
    }
    val text = listOfNotNull(e.t, e.v?.n, e.v?.town, e.org, e.tags.joinToString(" "), e.desc).joinToString(" ")
    return Ev(e, start, end, fold(text))
}

// Lowercase, strip accents and fold Danish spellings so "ø/oe", "æ/ae" and "å/aa" all meet.
fun fold(s: String): String {
    val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
    val sb = StringBuilder(n.length)
    for (ch in n) {
        when {
            Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
            ch == 'ø' -> sb.append('o')
            ch == 'æ' -> sb.append("ae")
            ch == 'ß' -> sb.append("ss")
            else -> sb.append(ch)
        }
    }
    return sb.toString().replace("aa", "a").replace("oe", "o")
}

fun matchesQuery(ev: Ev, tokens: List<String>, catNames: Map<String, String>): Boolean =
    tokens.all { tok -> ev.key.contains(tok) || catNames[ev.e.c]?.contains(tok) == true }

fun queryTokens(q: String): List<String> = fold(q).split(' ', ',', '.').filter { it.isNotBlank() }

@Serializable
data class Filter(
    val cats: Set<String> = emptySet(),
    val date: String = DATE_ANY,
    val from: Long? = null,
    val to: Long? = null,
    val free: Boolean = false,
    val town: String? = null,
    val source: String? = null,
    val fav: Boolean = false,
) {
    val active: Int
        get() = listOf(cats.isNotEmpty(), date != DATE_ANY, free, town != null, source != null, fav).count { it }
}

const val DATE_ANY = "any"
const val DATE_NOW = "now"
const val DATE_TODAY = "today"
const val DATE_TONIGHT = "tonight"
const val DATE_TOMORROW = "tomorrow"
const val DATE_WEEKEND = "weekend"
const val DATE_WEEK = "week"
const val DATE_CUSTOM = "custom"

private fun LocalDate.at0() = atStartOfDay(ZONE)

fun dateRange(f: Filter, now: ZonedDateTime): Pair<ZonedDateTime, ZonedDateTime>? {
    val today = now.toLocalDate()
    return when (f.date) {
        DATE_NOW -> now to now.plusSeconds(1)
        DATE_TODAY -> now to today.plusDays(1).at0()
        DATE_TONIGHT -> maxOf(now, today.atTime(17, 0).atZone(ZONE)) to today.plusDays(1).atTime(4, 0).atZone(ZONE)
        DATE_TOMORROW -> today.plusDays(1).at0() to today.plusDays(2).at0()
        DATE_WEEKEND -> {
            val sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            maxOf(now, sunday.minusDays(2).atTime(17, 0).atZone(ZONE)) to sunday.plusDays(1).at0()
        }
        DATE_WEEK -> now to today.plusDays(7).at0()
        DATE_CUSTOM -> {
            val a = f.from?.let { LocalDate.ofEpochDay(it) } ?: return null
            val b = f.to?.let { LocalDate.ofEpochDay(it) } ?: a
            maxOf(now, a.at0()) to b.plusDays(1).at0()
        }
        else -> null
    }
}

class Faves(val saved: Set<String>, val cats: Set<String>, val sources: Set<String>) {
    fun has(ev: Ev) = ev.id in saved || ev.e.c in cats || ev.e.src.any { it.id in sources }
}

fun matchesFilter(ev: Ev, f: Filter, faves: Faves, range: Pair<ZonedDateTime, ZonedDateTime>?): Boolean {
    if (f.cats.isNotEmpty() && ev.e.c !in f.cats) return false
    if (f.free && ev.e.p?.free != true) return false
    if (f.town != null && ev.e.v?.town != f.town) return false
    if (f.source != null && ev.e.src.none { it.id == f.source }) return false
    if (f.fav && !faves.has(ev)) return false
    if (range != null) {
        if (ev.long && f.date in setOf(DATE_NOW, DATE_TONIGHT)) return false
        if (!(ev.start < range.second && ev.end > range.first)) return false
    }
    return true
}

enum class Section { TODAY, TOMORROW, WEEK, WEEKEND, NEXT_WEEK, LATER, ONGOING }

fun sectionOf(ev: Ev, today: LocalDate): Section {
    val d = ev.start.toLocalDate()
    val sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    return when {
        d.isBefore(today) -> if (ev.long) Section.ONGOING else Section.TODAY
        d == today -> Section.TODAY
        d == today.plusDays(1) -> Section.TOMORROW
        !d.isAfter(sunday) -> if (d.isBefore(sunday.minusDays(1))) Section.WEEK else Section.WEEKEND
        !d.isAfter(sunday.plusDays(7)) -> Section.NEXT_WEEK
        else -> Section.LATER
    }
}

fun sectioned(events: List<Ev>, today: LocalDate): List<Pair<Section, List<Ev>>> =
    events.groupBy { sectionOf(it, today) }.toSortedMap().map { it.key to it.value }
