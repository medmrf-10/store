package com.irth.store.autoupdate

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * وحدة التحديث التلقائي القياسية لكل تطبيق أندرويد يُنشر على متجر:
 * https://medmrf-10.github.io/store/
 *
 * الاستخدام:
 * 1. استدعِ StoreAutoUpdater.check(slug = "your-app", currentVersionCode = BuildConfig.VERSION_CODE)
 * 2. إذا عاد بنتيجة غير فارغة، اعرض رسالة للمستخدم أو ابدأ StoreAutoUpdater.downloadAndInstall(context, info)
 */
object StoreAutoUpdater {
    const val BASE_URL = "https://medmrf-10.github.io/store"
    const val CATALOG_URL = "$BASE_URL/apps.json"

    data class UpdateInfo(
        val slug: String,
        val name: String,
        val version: String,
        val vcode: Int,
        val size: String,
        val apkUrl: String,
        val whatsnew: String,
        val sha256: String = ""
    )

    suspend fun check(
        slug: String,
        currentVersionCode: Int,
        pkg: String? = null
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val apps = JSONObject(body).optJSONArray("apps") ?: return@withContext null
            for (i in 0 until apps.length()) {
                val obj = apps.getJSONObject(i)
                val matchSlug = obj.optString("slug") == slug
                val matchPkg = !pkg.isNullOrBlank() && obj.optString("pkg") == pkg
                if (!matchSlug && !matchPkg) continue
                val remoteVcode = obj.optInt("vcode", 0)
                if (remoteVcode <= currentVersionCode) return@withContext null
                val rawUrl = obj.optString("url")
                if (rawUrl.isBlank()) return@withContext null
                val fullUrl = if (rawUrl.startsWith("http")) {
                    rawUrl
                } else {
                    "$BASE_URL/${rawUrl.removePrefix("./").removePrefix("/")}"
                }
                return@withContext UpdateInfo(
                    slug = obj.optString("slug", slug),
                    name = obj.optString("name", slug),
                    version = obj.optString("version", remoteVcode.toString()),
                    vcode = remoteVcode,
                    size = obj.optString("size", ""),
                    apkUrl = fullUrl,
                    whatsnew = obj.optString("whatsnew", ""),
                    sha256 = obj.optString("sha256", "")
                )
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun downloadAndInstall(context: Context, info: UpdateInfo): Long {
        val appCtx = context.applicationContext
        val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "${info.slug}-${info.version}.apk"
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("تحديث ${info.name} (${info.version})")
            .setDescription(info.whatsnew.ifBlank { fileName })
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")

        val downloadId = dm.enqueue(req)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                runCatching { appCtx.unregisterReceiver(this) }
                val uri = dm.getUriForDownloadedFile(id) ?: return
                val installIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { appCtx.startActivity(installIntent) }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appCtx.registerReceiver(receiver, filter)
        }
        return downloadId
    }
}
