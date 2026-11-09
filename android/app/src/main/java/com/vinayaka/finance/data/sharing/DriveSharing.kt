package com.vinayaka.finance.data.sharing

import com.vinayaka.finance.data.sheets.GoogleHttp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Changes who the Google Sheet is shared with, through the Google Drive v3 permissions API (free).
 * Needs a token with the Drive scope, and the signed-in account must be allowed to share the file
 * (the owner, or an editor if the owner permits editors to share).
 */
class DriveSharing(fileId: String, private val http: GoogleHttp) : SheetSharing {
    private val base = "https://www.googleapis.com/drive/v3/files/$fileId/permissions"

    private fun levelOf(role: String?): ShareLevel? = when (role) {
        "owner" -> ShareLevel.OWNER
        "writer", "organizer", "fileOrganizer" -> ShareLevel.WRITER
        "reader", "commenter" -> ShareLevel.READER
        else -> null
    }

    private fun roleOf(level: ShareLevel) = if (level == ShareLevel.WRITER) "writer" else "reader"

    override suspend fun list(): ShareSnapshot {
        val people = mutableListOf<Share>()
        val open = mutableListOf<OpenAccess>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append(base).append("?supportsAllDrives=true&pageSize=100")
                append("&fields=nextPageToken,permissions(id,type,role,emailAddress,domain)")
                pageToken?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val res: JsonObject = http.call("GET", url)
            (res["permissions"] as? JsonArray)?.forEach { el ->
                val p = el.jsonObject
                val level = levelOf(p["role"]?.jsonPrimitive?.contentOrNull) ?: return@forEach
                when (p["type"]?.jsonPrimitive?.contentOrNull) {
                    "user", "group" -> {
                        val email = p["emailAddress"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        people += Share(p["id"]!!.jsonPrimitive.content, email, level)
                    }
                    "anyone" -> open += OpenAccess("Anyone with the link", level)
                    "domain" -> open += OpenAccess("Everyone at ${p["domain"]?.jsonPrimitive?.contentOrNull ?: "your organisation"}", level)
                }
            }
            pageToken = res["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (pageToken != null)
        return ShareSnapshot(people, open)
    }

    override suspend fun add(email: String, level: ShareLevel) {
        http.call("POST", "$base?supportsAllDrives=true&sendNotificationEmail=true", buildJsonObject {
            put("type", "user")
            put("role", roleOf(level))
            put("emailAddress", email)
        })
    }

    override suspend fun change(shareId: String, level: ShareLevel) {
        http.call("PATCH", "$base/$shareId?supportsAllDrives=true", buildJsonObject { put("role", roleOf(level)) })
    }

    override suspend fun remove(shareId: String) {
        http.call("DELETE", "$base/$shareId?supportsAllDrives=true")
    }
}
