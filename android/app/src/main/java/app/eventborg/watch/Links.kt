package app.eventborg.watch

import java.net.URI
import java.net.URLDecoder

sealed interface Link

// handle: Instagram username, Facebook page slug or numeric id; null for opaque /share/ links.
data class ProfileLink(val net: String, val handle: String?, val url: String, val name: String?) : Link

data class GroupLink(val id: String, val url: String, val name: String?) : Link

const val IG = "ig"
const val FB = "fb"

private val URL_RX = Regex("""(?i)\b(?:https?://)?(?:[a-z0-9-]+\.)*(?:instagram\.com|instagr\.am|facebook\.com|fb\.com|fb\.me)(?:/[^\s<>"']*)?""")
private val IG_HANDLE = Regex("^[A-Za-z0-9._]{1,30}$")
private val FB_SLUG = Regex("^[A-Za-z0-9.\\-]{2,80}$")
private val DIGITS = Regex("^\\d{5,20}$")

private val IG_RESERVED = setOf(
    "p", "reel", "reels", "tv", "explore", "accounts", "direct", "s", "about", "legal", "developer", "web",
    "challenge", "emails", "ar", "stories", "_n", "_u", "share", "privacy", "help", "api", "oauth",
)

private val FB_RESERVED = setOf(
    "events", "watch", "marketplace", "gaming", "story.php", "permalink.php", "photo.php", "photo", "photos", "posts",
    "login", "login.php", "help", "policies", "privacy", "hashtag", "reel", "reels", "stories", "messages",
    "notifications", "friends", "settings", "home.php", "sharer", "sharer.php", "dialog", "plugins", "l.php",
    "video.php", "videos", "search", "business", "ads", "legal", "groups", "share", "pages", "people", "profile.php",
    "media", "bookmarks", "saved", "memories", "fundraisers", "live", "pg", "recover", "checkpoint", "terms",
)

private fun words(slug: String) =
    URLDecoder.decode(slug, "UTF-8").replace('-', ' ').replace('_', ' ').trim().takeIf { it.isNotEmpty() && !DIGITS.matches(it) }

// Instagram's profile share text reads like "Name (@handle) • Instagram ..." in several languages.
private fun igName(text: String, handle: String): String? {
    val m = Regex("""([^\n()•|]{2,60}?)\s*\(@${Regex.escape(handle)}\)""", RegexOption.IGNORE_CASE).find(text) ?: return null
    val name = m.groupValues[1].trim()
        .replace(Regex("""^(?i)(check out|look at|see|se|tjek|schau dir|sieh dir)\s+"""), "")
        .trim(' ', ':', '"', '\'', '“', '”')
    return name.takeIf { it.length >= 2 && !it.contains("http") }
}

fun parseLink(text: String): Link? {
    val raw = URL_RX.find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';') ?: return null
    val uri = runCatching { URI(if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw") }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val segs = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
    val query = (uri.rawQuery ?: "").split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    if (host.endsWith("instagram.com") || host.endsWith("instagr.am")) {
        val handle = when {
            segs.isEmpty() -> return null
            (segs[0] == "stories" || segs[0] == "_u") && segs.size >= 2 && segs[1] != "highlights" -> segs[1]
            segs[0].lowercase() in IG_RESERVED -> return null
            else -> segs[0]
        }.lowercase()
        if (!IG_HANDLE.matches(handle)) return null
        return ProfileLink(IG, handle, "https://www.instagram.com/$handle/", igName(text, handle))
    }

    if (segs.isEmpty()) return null
    val first = segs[0].lowercase()
    return when {
        first == "groups" && segs.size >= 2 && FB_SLUG.matches(segs[1]) ->
            GroupLink(segs[1].lowercase(), "https://www.facebook.com/groups/${segs[1]}/", null)
        first == "share" && segs.size >= 3 && segs[1] == "g" ->
            GroupLink("share/g/${segs[2]}", "https://www.facebook.com/share/g/${segs[2]}/", null)
        first == "share" && segs.size >= 2 && segs[1] !in setOf("p", "r", "v", "g") ->
            ProfileLink(FB, null, "https://www.facebook.com/share/${segs[1]}/", null)
        first == "profile.php" -> query["id"]?.takeIf { DIGITS.matches(it) }?.let {
            ProfileLink(FB, it, "https://www.facebook.com/profile.php?id=$it", null)
        }
        (first == "people" || first == "pages") && segs.size >= 3 -> segs.drop(2).firstOrNull { DIGITS.matches(it) }?.let {
            ProfileLink(FB, it, "https://www.facebook.com/profile.php?id=$it", words(segs[1]))
        }
        first == "pg" && segs.size >= 2 && FB_SLUG.matches(segs[1]) ->
            ProfileLink(FB, segs[1].lowercase(), "https://www.facebook.com/${segs[1].lowercase()}", null)
        first in FB_RESERVED -> null
        FB_SLUG.matches(segs[0]) -> {
            val slug = segs[0].lowercase()
            ProfileLink(FB, slug, "https://www.facebook.com/$slug", null)
        }
        else -> null
    }
}

// A share that is mostly a profile/group link (plus a short "Check out X (@x)" line) goes to the watch list.
fun isWatchShare(text: String): Boolean = parseLink(text) != null && URL_RX.replace(text, "").trim().length <= 100
