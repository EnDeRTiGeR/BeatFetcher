package com.example.youtubetomp3.util

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
        @Volatile
        var shouldRetryAfterPermission: Boolean = false
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
                
                // Check install permission (Android 8.0+ / API 26+)
                // Since app supports Android 10+, this check will always run
                val canInstall = activity.packageManager.canRequestPackageInstalls()
                if (!canInstall) {
                    withContext(Dispatchers.Main) {
                        showInstallPermissionDialog(activity)
                    }
                    return@withContext null
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Update ${latestTag} found. Downloading...", Toast.LENGTH_SHORT).show()
                }

                val f = downloadApk(apkUrl)
                if (f == null) {
                    Log.w(TAG, "Download failed")
                }
                f
            } ?: return

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Launching installer...", Toast.LENGTH_SHORT).show()
                installApk(activity, apkFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check/install failed", e)
        }
    }
    
    /**
     * Show dialog to request install permission and open settings
     * Works for Android 8.0+ (API 26+), which includes Android 10+
     */
    private fun showInstallPermissionDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Enable Install Permission")
            .setMessage("To install updates, please allow 'Install unknown apps' permission for BeatFetcher in your device settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    shouldRetryAfterPermission = true
                    // Try the standard intent first (works on most devices)
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    
                    // Check if intent can be resolved before starting
                    if (intent.resolveActivity(activity.packageManager) != null) {
                        activity.startActivity(intent)
                    } else {
                        // Fallback: try opening app info settings
                        openAppInfoSettings(activity)
                    }
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Unknown app sources settings unavailable, trying fallback", e)
                    openAppInfoSettings(activity)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open install permission settings", e)
                    Toast.makeText(
                        activity,
                        "Please enable 'Install unknown apps' for BeatFetcher in Settings > Apps > BeatFetcher > Install unknown apps",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }
    
    /**
     * Fallback: Open app info settings where user can find install permission
     */
    private fun openAppInfoSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(intent)
                Toast.makeText(
                    activity,
                    "Please enable 'Install unknown apps' in the app settings",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Last resort: open main settings
                val mainSettingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(mainSettingsIntent)
                Toast.makeText(
                    activity,
                    "Please go to Apps > BeatFetcher > Install unknown apps and enable it",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app info settings", e)
            Toast.makeText(
                activity,
                "Please manually enable 'Install unknown apps' for BeatFetcher in Settings",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        // Compare MAJOR.MINOR.PATCH numerically; differing prefixes (e.g., v) are ignored
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
        // Equal numerically -> not newer
        return false
    }

    private data class Release(val tag: String, val htmlUrl: String, val apkUrl: String?)

    private fun fetchLatestRelease(owner: String, repo: String): Release? {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
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
                    Log.d(TAG, "Release asset: name=$name, contentType=$contentType")
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
        // Double-check permission before installation (Android 8.0+ / API 26+)
        // Since app supports Android 10+, this check will always run
        val canInstall = activity.packageManager.canRequestPackageInstalls()
        if (!canInstall) {
            Log.w(TAG, "Install permission not granted, showing dialog")
            showInstallPermissionDialog(activity)
            return
        }

        val authority = activity.packageName + ".fileProvider"
        val contentUri = try {
            FileProvider.getUriForFile(activity, authority, file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create FileProvider URI", e)
            Toast.makeText(activity, "Failed to prepare update file", Toast.LENGTH_LONG).show()
            return
        }
        
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // For Android 7.0+ (API 24+), also grant write permission if needed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        
        try {
            // Verify that an app can handle this intent
            if (install.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(install)
                Log.d(TAG, "Install intent started successfully")
            } else {
                Log.e(TAG, "No app found to handle installation")
                Toast.makeText(
                    activity,
                    "No app found to install the update. Please install manually from Downloads.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during installation - permission may have been revoked", e)
            showInstallPermissionDialog(activity)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity found to handle installation", e)
            Toast.makeText(
                activity,
                "Cannot open installer. Please install the update manually from: ${file.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start installer", e)
            Toast.makeText(
                activity,
                "Failed to install update: ${e.message}. Please try installing manually.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
