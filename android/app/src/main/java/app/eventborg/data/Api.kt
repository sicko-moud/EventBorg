package app.eventborg.data

import app.eventborg.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class Sent { OK, LIMITED, INVALID, FAILED }

class Api(private val http: () -> OkHttpClient) {
    suspend fun suggest(name: String, link: String, note: String): Sent = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("name", name.trim())
            put("url", link.trim())
            put("note", note.trim())
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(BuildConfig.WORKER_URL + "/suggest").post(body).build()
        runCatching {
            http().newCall(req).execute().use {
                when {
                    it.isSuccessful -> Sent.OK
                    it.code == 429 -> Sent.LIMITED
                    it.code == 400 -> Sent.INVALID
                    else -> Sent.FAILED
                }
            }
        }.getOrDefault(Sent.FAILED)
    }
}
