package com.example.youtubetomp3.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.youtubetomp3.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }

    suspend fun checkAndInstallIfAvailable(
        activity: Activity,
        owner: String,
        repo: String
    ) {
        try {
            val apkFile = withContext(Dispatchers.IO) {
                val latest = fetchLatestRelease(owner, repo) ?: return@withContext null
                val latestTag = latest.tag
                val currentTag = BuildConfig.VERSION_NAME

                if (!isNewer(latestTag, currentTag)) {
                    Log.d(TAG, "No update available: latest=$latestTag current=$currentTag")
                    return@withContext null
                }

                val apkUrl = latest.apkUrl ?: run {
                    Log.w(TAG, "No APK asset found in latest release")
                    return@withContext null
                }

                downloadApk(apkUrl)
            } ?: return

            withContext(Dispatchers.Main) {
                installApk(activity, apkFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check/install failed", e)
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        // Try semantic compare vMAJOR.MINOR.PATCH, fallback to string compare
        fun parse(v: String): List<Int> = v.trim().trimStart('v', 'V')
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val li = l.getOrElse(i) { 0 }
            val ci = c.getOrElse(i) { 0 }
            if (li != ci) return li > ci
        }
        // If equal numerically but strings differ, fallback to inequality check
        return latest != current
    }

    private data class Release(val tag: String, val htmlUrl: String, val apkUrl: String?)

    private fun fetchLatestRelease(owner: String, repo: String): Release? {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BeatFetcher/${BuildConfig.VERSION_NAME}")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GitHub latest release failed: ${'$'}{resp.code}")
                return null
            }
            val bodyStr = resp.body?.string() ?: return null
            val json = JSONObject(bodyStr)
            val tag = json.optString("tag_name")
            val htmlUrl = json.optString("html_url")
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    val contentType = a.optString("content_type")
                    val browserUrl = a.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) || contentType.contains("android.package-archive")) {
                        apkUrl = browserUrl
                        break
                    }
                }
            }
            return Release(tag = tag, htmlUrl = htmlUrl, apkUrl = apkUrl)
        }
    }

    private fun downloadApk(apkUrl: String): File? {
        val req = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "BeatFetcher/${BuildConfig.VERSION_NAME}")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "APK download failed: ${'$'}{resp.code}")
                return null
            }
            val dir = File(context.cacheDir, "apks").apply { mkdirs() }
            val outFile = File(dir, "update-latest.apk")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                    fos.flush()
                }
            }
            return outFile
        }
    }

    private fun installApk(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = activity.packageManager.canRequestPackageInstalls()
            if (!canInstall) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${'$'}{activity.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Unknown app sources settings unavailable", e)
                }
                // User must grant permission; ask them to retry
                return
            }
        }

        val authority = activity.packageName + ".fileProvider"
        val contentUri = FileProvider.getUriForFile(activity, authority, file)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(install)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start installer", e)
        }
    }
}
