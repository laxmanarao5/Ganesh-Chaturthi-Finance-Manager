package com.vinayaka.finance.data.sheets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Minimal JSON-over-HTTPS client for Google APIs: bearer token, one token refresh on 401, retries on 429/5xx. */
class GoogleHttp(
    private val accessToken: suspend (forceRefresh: Boolean) -> String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun call(method: String, url: String, body: JsonElement? = null): JsonObject =
        withContext(Dispatchers.IO) {
            var refreshed = false
            var attempt = 0
            while (true) {
                attempt++
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer " + accessToken(refreshed))
                    .method(method, body?.let { json.encodeToString(JsonElement.serializer(), it).toRequestBody(jsonType) })
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body.string()
                    val code = resp.code
                    when {
                        resp.isSuccessful -> return@withContext if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
                        code == 401 && !refreshed -> refreshed = true
                        (code == 429 || code >= 500) && attempt < 4 -> delay(1000L * attempt)
                        else -> throw SheetsApiException(code, errorMessage(text) ?: "Google returned HTTP $code")
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    private fun errorMessage(text: String): String? = runCatching {
        json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
