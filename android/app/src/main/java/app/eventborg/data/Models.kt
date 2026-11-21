package app.eventborg.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JsonFmt = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class Feed(val gen: String? = null, val events: List<Event> = emptyList())

@Serializable
data class Event(
    val id: String,
    val t: String,
    val s: String,
    val e: String? = null,
    val ad: Boolean = false,
    val v: Venue? = null,
    val c: String = "other",
    val tags: List<String> = emptyList(),
    val p: Price? = null,
    val url: String? = null,
    val src: List<Src> = emptyList(),
    val org: String? = null,
    val img: String? = null,
    val sum: Sum? = null,
    val desc: String? = null,
    val lang: String? = null,
    val age: String? = null,
    val st: String = "ok",
    val upd: String? = null,
)

@Serializable
data class Venue(
    val n: String? = null,
    val addr: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val town: String? = null,
)

@Serializable
data class Price(val free: Boolean? = null, val min: Double? = null, val max: Double? = null)

@Serializable
data class Src(val id: String, val kind: String = "web", val url: String? = null)

@Serializable
data class Sum(val en: String? = null, val da: String? = null)

@Serializable
data class SourcesFile(val gen: String? = null, val sources: List<Source> = emptyList())

@Serializable
data class Source(
    val id: String,
    val n: String,
    val kind: String = "venue",
    val type: String? = null,
    val url: String? = null,
    val town: String? = null,
    val ig: String? = null,
    val fb: String? = null,
    val names: List<String> = emptyList(),
    val st: String? = null,
    val ok: String? = null,
    val cnt: Int = 0,
)

val CATEGORIES = listOf(
    "music", "nightlife", "theatre_culture", "exhibitions", "food_drink", "sports", "family_kids", "students",
    "talks_learning", "markets", "outdoor_nature", "festivals", "community", "business", "other",
)
