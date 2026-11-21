package app.eventborg.watch

import app.eventborg.data.fold

const val IG_PKG = "com.instagram.android"
const val FB_PKG = "com.facebook.katana"

// A story owner we listen for: a listed source (key = source id) or a watched profile (key = "w:<net>:<handle>").
class Target(val key: String, val name: String, val url: String, val sourceId: String?, raw: List<String>) {
    val needles: List<String> = raw.map { fold(it.trim().trimStart('@')) }.filter { it.length >= 4 }.distinct()
}

class GroupTarget(val key: String, val name: String, val url: String) {
    val needle = fold(name.trim())
}

private fun boundary(s: String, i: Int) = i < 0 || i >= s.length || !(s[i].isLetterOrDigit() || s[i] == '_')

// Word-boundary search; "mejeriet" doesn't match inside "mejeriet.sonderborg".
fun containsWord(hay: String, needle: String): Boolean {
    if (needle.isEmpty()) return false
    var i = hay.indexOf(needle)
    while (i >= 0) {
        val end = i + needle.length
        val dotted = end + 1 < hay.length && hay[end] == '.' && hay[end + 1].isLetterOrDigit()
        if (boundary(hay, i - 1) && boundary(hay, end) && !dotted) return true
        i = hay.indexOf(needle, i + 1)
    }
    return false
}

private val STORY_WORD = Regex("(?<![\\p{L}])(story|stories|storys|storyen|historie|historier|historien)(?![\\p{L}])")
private val NOT_THEIRS = Regex("(?<![\\p{L}])(your|din|dit|deine|deiner|ihre|ihrer)\\s+(story|stories|historie)")

fun isStoryText(folded: String) = STORY_WORD.containsMatchIn(folded) && !NOT_THEIRS.containsMatchIn(folded)

private fun <T> longest(hay: String, items: List<T>, needles: (T) -> List<String>): T? =
    items.mapNotNull { t -> needles(t).filter { containsWord(hay, it) }.maxOfOrNull { it.length }?.let { t to it } }
        .maxByOrNull { it.second }?.first

// "<handle> added to their story", "<Page> har tilføjet til sin historie" → the target it names, else null.
fun matchStory(title: String, text: String, targets: List<Target>): Target? {
    val hay = fold("$title\n$text")
    if (!isStoryText(hay)) return null
    return longest(hay, targets) { it.needles }
}

fun matchGroup(title: String, text: String, groups: List<GroupTarget>): GroupTarget? {
    val hay = fold("$title\n$text")
    return longest(hay, groups.filter { it.needle.length >= 4 }) { listOf(it.needle) }
}

private val POSTER = Regex("^([^:\\n\\d]{1,60}):\\s")

// Keeps the post text and drops "<Name> posted in <Group>: " / "<Name>: " prefixes.
fun postSnippet(text: String, group: String): String {
    var s = text.trim()
    val g = if (group.isNotBlank()) s.indexOf(group, ignoreCase = true) else -1
    if (g >= 0) {
        s = s.substring(g + group.length)
    } else {
        POSTER.find(s)?.let { m -> if (m.groupValues[1].trim().split(Regex("\\s+")).size <= 5) s = s.substring(m.range.last + 1) }
    }
    return s.trim().trimStart(':', '-', '–', '"', '“', '„', '»', ' ').trimEnd('"', '”', '“', '«', ' ').trim()
}

// --- possible-event heuristic (text is folded first, so patterns use folded spellings: lordag, abent, faelles) ---

private const val L = "(?<![\\p{L}\\p{N}])"
private const val R = "(?![\\p{L}\\p{N}])"
private const val MONTH = "jan(?:uar|uary)?|feb(?:ruar|ruary)?|mar(?:ts|ch|z)?|apr(?:il)?|maj|mai|may|jun[ei]?|jul[iy]?|aug(?:ust)?|sep(?:t|tember)?|o[ck]t(?:ober)?|nov(?:ember)?|de[cz](?:ember)?"
private const val DAY = "(?:3[01]|[12]\\d|0?[1-9])"
private const val HOUR = "(?:2[0-3]|[01]?\\d)"

private val DATE = Regex(
    "$L(?:$DAY\\.?\\s?(?:$MONTH)\\.?$R|(?:$MONTH)\\.?\\s$DAY(?:st|nd|rd|th)?$R|$DAY[./-](?:1[0-2]|0?[1-9])(?:[./-](?:20)?\\d{2})?$R|20\\d{2}-(?:1[0-2]|0[1-9])-$DAY$R)",
)
private val WEEKDAY = Regex(
    "$L(?:mandag|tirsdag|onsdag|torsdag|fredag|lordag|sondag|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
        "montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)",
)
private val RELATIVE = Regex(
    "$L(?:i dag|i aften|i morgen|i weekenden|i nat|today|tonight|tomorrow|this weekend|next week|naeste uge|heute|morgen abend|" +
        "nachste woche|am wochenende)$R",
)
private val TIME = Regex("$L(?:kl\\.?\\s?$HOUR(?:[.:][0-5]\\d)?|$HOUR[:.][0-5]\\d|(?:1[0-2]|0?[1-9])\\s?(?:am|pm)|$HOUR(?:[.:][0-5]\\d)?\\s?uhr)$R")
private val PRICE = Regex(
    "$L(?:\\d+(?:[.,]\\d+)?\\s?(?:kr|dkk|eur|euro)$R|\\d+(?:[.,]\\d+)?\\s?€|(?:kr|dkk)\\.?\\s?\\d+|\\d+\\s?,-|gratis$R|" +
        "free entry|free admission|fri entre|fri adgang|eintritt)",
)
private val KEYWORD = Regex(
    "$L(?:events?|arrangement\\p{L}*|koncert\\p{L}*|concerts?|konzert\\p{L}*|fest|festen|party|partys|festival\\p{L}*|" +
        "billet\\p{L}*|tickets?|veranstaltung\\p{L}*|workshop\\p{L}*|foredrag\\p{L}*|lecture|quiz\\p{L}*|banko\\p{L}*|bingo|" +
        "loppemarked\\p{L}*|julemarked\\p{L}*|kraemmermarked\\p{L}*|markedsdag|flea market|flohmarkt|livemusik|live musik|" +
        "live music|stand ?up|teater\\p{L}*|theater|theatre|forestilling\\p{L}*|udstilling\\p{L}*|exhibition|ausstellung|" +
        "fernisering|vernissage|abent hus|open house|tilmelding|tilmeld\\p{L}*|sign up|signup|anmeldung|turnering\\p{L}*|" +
        "tournament|gudstjeneste\\p{L}*|fredagsbar|friday bar|faellesspisning|meetup|byfest|dj|karaoke|disco|premiere|" +
        "reception|gig|jam session|vinsmagning|olsmagning|tasting|guidet tur|guided tour|rundvisning|fuhrung)$R",
)

fun looksLikeEvent(text: String): Boolean {
    val t = fold(text)
    val date = DATE.containsMatchIn(t)
    val day = WEEKDAY.containsMatchIn(t) || RELATIVE.containsMatchIn(t)
    val time = TIME.containsMatchIn(t)
    val price = PRICE.containsMatchIn(t)
    val keyword = KEYWORD.containsMatchIn(t)
    val score = (if (date) 2 else 0) + (if (day) 1 else 0) + (if (time) 1 else 0) + (if (price) 1 else 0) + (if (keyword) 2 else 0)
    return ((date || day) && score >= 3) || (keyword && (time || price))
}
