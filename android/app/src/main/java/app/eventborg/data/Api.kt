package app.eventborg.data

import app.eventborg.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Sent { OK, LIMITED, INVALID, FAILED }

enum class Why { LIMITED, BLOCKED_URL, UNREADABLE_URL, BUSY, OFFLINE, FAILED }

sealed interface Extract {
    data class Ok(val draft: Draft, val tok: String?) : Extract
    data class NotEvent(val reason: String?) : Extract
    data class Failed(val why: Why) : Extract
}

class Api(private val http: () -> OkHttpClient) {
    private val slow by lazy { http().newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }

    private fun post(path: String, body: JsonObject, client: OkHttpClient = http()) = client.newCall(
        Request.Builder().url(BuildConfig.WORKER_URL + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
    ).execute()

    private fun sent(code: Int, ok: Boolean) = when {
        ok -> Sent.OK
        code == 429 -> Sent.LIMITED
        code == 400 -> Sent.INVALID
        else -> Sent.FAILED
    }

    suspend fun suggest(name: String, link: String, note: String): Sent = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("name", name.trim())
            put("url", link.trim())
            put("note", note.trim())
        }
        runCatching { post("/suggest", body).use { sent(it.code, it.isSuccessful) } }.getOrDefault(Sent.FAILED)
    }

    // img: base64 JPEG (already shrunk to ≤1280 px).
    suspend fun extract(text: String, img: String?): Extract = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            if (text.isNotBlank()) put("text", text.take(4000))
            if (img != null) {
                put("img", img)
                put("mime", "image/jpeg")
            }
        }
        try {
            post("/extract", body, slow).use { r ->
                val res = runCatching { JsonFmt.decodeFromString(Extracted.serializer(), r.body.string()) }.getOrNull()
                when {
                    r.isSuccessful && res?.is_event == true && res.draft != null -> Extract.Ok(res.draft, res.tok)
                    r.isSuccessful && res != null -> Extract.NotEvent(res.reason)
                    r.code == 429 -> Extract.Failed(Why.LIMITED)
                    res?.err == "blocked_url" -> Extract.Failed(Why.BLOCKED_URL)
                    res?.err == "unreadable_url" -> Extract.Failed(Why.UNREADABLE_URL)
                    r.code == 503 -> Extract.Failed(Why.BUSY)
                    else -> Extract.Failed(Why.FAILED)
                }
            }
        } catch (_: IOException) {
            Extract.Failed(Why.OFFLINE)
        }
    }

    suspend fun submit(draft: Draft, tok: String?): Sent = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("draft", JsonFmt.encodeToJsonElement(Draft.serializer(), draft))
            if (tok != null) put("tok", tok)
        }
        runCatching { post("/submit", body).use { sent(it.code, it.isSuccessful) } }.getOrDefault(Sent.FAILED)
    }

    suspend fun storySeen(sourceId: String, ts: Long): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("sourceId", sourceId)
            put("ts", ts)
        }
        runCatching { post("/story-seen", body).use { it.isSuccessful } }.getOrDefault(false)
    }

    // A listed account's new post seen in a notification: true = the server found an event (it goes through the inbox
    // rules and arrives as a normal push), false = not an event, null = no answer.
    suspend fun postSeen(sourceId: String, ts: Long, text: String, img: String?): Boolean? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("sourceId", sourceId)
            put("ts", ts)
            put("text", text.take(1000))
            if (img != null) put("img", img)
        }
        runCatching {
            post("/post-seen", body, slow).use { r ->
                if (!r.isSuccessful) null else JsonFmt.decodeFromString(PostVerdict.serializer(), r.body.string()).takeIf { it.ok }?.is_event
            }
        }.getOrNull()
    }

    suspend fun channels(): Channels? = withContext(Dispatchers.IO) {
        runCatching {
            http().newCall(Request.Builder().url(BuildConfig.WORKER_URL + "/channels").build()).execute().use {
                if (it.isSuccessful) JsonFmt.decodeFromString(Channels.serializer(), it.body.string()) else null
            }
        }.getOrNull()
    }

    fun stories(): List<RemoteStory>? = runCatching {
        http().newCall(Request.Builder().url(BuildConfig.WORKER_URL + "/stories").build()).execute().use {
            if (it.isSuccessful) JsonFmt.decodeFromString(StoriesResp.serializer(), it.body.string()).stories else null
        }
    }.getOrNull()
}
