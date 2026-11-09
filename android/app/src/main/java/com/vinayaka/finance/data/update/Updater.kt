package com.vinayaka.finance.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** What `releases/version.json` in the GitHub repo says about the newest published APK. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apk: String = "vinayaka-finance.apk",
    val sha256: String = "",
    val size: Long = 0,
    val notes: String = "",
)

class UpdateException(message: String) : Exception(message)

/** The decisions, kept free of Android so they can be unit tested. */
object UpdateLogic {
    private val json = Json { ignoreUnknownKeys = true }

    /** Null when the text is not a valid version file. */
    fun parse(text: String): UpdateInfo? =
        runCatching { json.decodeFromString(UpdateInfo.serializer(), text) }.getOrNull()?.takeIf { it.versionCode > 0 && it.apk.isNotBlank() }

    /** An update is offered only when the published version is higher than the installed one (never a downgrade). */
    fun isNewer(info: UpdateInfo, installedVersionCode: Int) = info.versionCode > installedVersionCode

    /** A blank checksum in the version file means "not provided", so nothing is checked. */
    fun checksumOk(expected: String, actualHex: String) = expected.isBlank() || expected.trim().equals(actualHex, ignoreCase = true)

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Checks the public GitHub repo for a newer APK, downloads it and hands it to Android's installer.
 * Android only installs it over the current app if it is signed with the same key, so a file from anywhere else cannot replace the app.
 */
class Updater(private val app: Context, private val client: OkHttpClient = OkHttpClient()) {

    /** The newer version, or null when this one is current. Throws [UpdateException] or an IOException when it cannot be checked. */
    suspend fun check(installedVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE + "version.json").header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw UpdateException("Could not reach the update server (HTTP ${r.code}).")
            val info = UpdateLogic.parse(r.body.string()) ?: throw UpdateException("The update information could not be read.")
            info.takeIf { UpdateLogic.isNewer(it, installedVersionCode) }
        }
    }

    /** Downloads (or reuses an earlier verified download of) the APK. [onProgress] gets 0..100. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "vinayaka-finance-${info.versionCode}.apk")
        if (target.exists() && matches(target, info)) return@withContext target
        dir.listFiles()?.forEach { it.delete() }

        val part = File(dir, "download.part")
        val request = Request.Builder().url(BASE + info.apk).header("Cache-Control", "no-cache").build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw UpdateException("Could not download the update (HTTP ${r.code}).")
            val total = r.body.contentLength().takeIf { it > 0 } ?: info.size
            r.body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        ensureActive()
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        if (!UpdateLogic.checksumOk(info.sha256, UpdateLogic.hex(digest.digest()))) {
            part.delete()
            throw UpdateException("The downloaded file did not match the published one. GitHub may still be refreshing; try again in a few minutes.")
        }
        part.renameTo(target)
        target
    }

    private fun matches(file: File, info: UpdateInfo): Boolean {
        if (info.sha256.isBlank()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return UpdateLogic.checksumOk(info.sha256, UpdateLogic.hex(digest.digest()))
    }

    /** Android asks the user once to allow this app to install packages. */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        /** Where `publishApk` (see app/build.gradle.kts) puts the APK and version.json; the repo is public, so no sign-in is needed. */
        const val BASE = "https://raw.githubusercontent.com/laxmanarao5/Ganesh-Chaturthi-Finance-Manager/master/android/releases/"
    }
}
