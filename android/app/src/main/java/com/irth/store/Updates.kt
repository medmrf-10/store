package com.irth.store

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

const val ACTION_KEY = "store.action"
const val ACT_UPDATES = "updates"
const val ACT_UPDATE_ALL = "update_all"

/** تفضيلات تنبيهات التحديث وسجل ما نُبِّه عنه حتى لا يتكرر الإشعار لنفس الإصدار. */
object StorePrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)
    fun notifyOn(ctx: Context) = sp(ctx).getBoolean("notify", true)
    fun setNotify(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("notify", v).apply()
    fun primes(ctx: Context) = sp(ctx).getInt("primes", 0)
    fun lastPrime(ctx: Context) = sp(ctx).getLong("primeAt", 0L)
    fun primed(ctx: Context) = sp(ctx).edit().putInt("primes", primes(ctx) + 1).putLong("primeAt", System.currentTimeMillis()).apply()
    fun notified(ctx: Context): Set<String> = sp(ctx).getStringSet("notified", emptySet()) ?: emptySet()
    fun addNotified(ctx: Context, keys: Collection<String>) =
        sp(ctx).edit().putStringSet("notified", (notified(ctx) + keys).toSet()).apply()
    fun lastCheck(ctx: Context) = sp(ctx).getLong("checkAt", 0L)
    fun checked(ctx: Context) = sp(ctx).edit().putLong("checkAt", System.currentTimeMillis()).apply()
}

fun canNotify(ctx: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

/** التطبيقات التي لها إصدار أحدث من المثبّت — المتجر نفسه آخراً لأن تثبيته يغلق الجلسة. */
fun outdatedApps(ctx: Context, apps: List<AppEntry>): List<AppEntry> =
    apps.filter { it.url.isNotEmpty() && stateOf(ctx, it) == AppState.OUTDATED }
        .sortedBy { if (it.pkg == ctx.packageName) 1 else 0 }

object Updates {
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 41

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("store-updates", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "تحديثات التطبيقات", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "تنبيه حين يصدر إصدار جديد من تطبيق مثبّت عندك" }
                )
            }
        }
    }

    private fun open(ctx: Context, act: String, req: Int) = PendingIntent.getActivity(
        ctx, req,
        Intent(ctx, MainActivity::class.java).putExtra(ACTION_KEY, act)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** يُنبّه مرة واحدة لكل (حزمة، إصدار) جديد. يعيد عدد ما نُبِّه عنه. */
    fun notifyNew(ctx: Context, apps: List<AppEntry>): Int {
        StorePrefs.checked(ctx)
        val ups = outdatedApps(ctx, apps)
        val seen = StorePrefs.notified(ctx)
        val fresh = ups.filter { "${it.pkg}:${it.vcode}" !in seen }
        if (fresh.isEmpty() || !StorePrefs.notifyOn(ctx) || !canNotify(ctx)) return 0
        channel(ctx)
        val title = if (ups.size == 1) "تحديث ${ups[0].name} ${ups[0].version} متاح" else "${ups.size} تحديثات متاحة"
        val text = if (ups.size == 1) ups[0].whatsnew.ifEmpty { "إصدار جديد جاهز للتثبيت" }
        else ups.joinToString("، ") { "${it.name} ${it.version}" }
        val big = ups.joinToString("\n") { a -> "• ${a.name} ${a.version}" + (if (a.whatsnew.isNotEmpty()) " — ${a.whatsnew}" else "") }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(open(ctx, ACT_UPDATES, 1))
            .addAction(0, if (ups.size == 1) "حدّث الآن" else "حدّث الكل", open(ctx, ACT_UPDATE_ALL, 2))
            .setNumber(ups.size)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
        StorePrefs.addNotified(ctx, fresh.map { "${it.pkg}:${it.vcode}" })
        return fresh.size
    }

    fun clear(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
}

class UpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val apps = runCatching { fetchCatalog() }.getOrElse { return Result.retry() }
        Updates.notifyNew(applicationContext, apps)
        return Result.success()
    }
}

/**
 * طابور التثبيت: تنزيلات «حدّث الكل» تكتمل بأي ترتيب، والمثبِّت يُفتح لواحد فقط في كل مرة؛
 * التالي يُفتح عند عودة المتجر للواجهة. تحديث المتجر نفسه ينتظر حتى تنتهي البقية.
 */
object DlHub {
    private val handled = mutableSetOf<Long>()
    private val queue = ArrayDeque<Long>()
    val inflight = mutableSetOf<Long>()
    val selfIds = mutableSetOf<Long>()
    private var busyAt = 0L

    fun track(id: Long, self: Boolean) { inflight.add(id); if (self) selfIds.add(id) }
    fun failed(id: Long) { inflight.remove(id) }

    fun ready(ctx: Context, dm: DownloadManager, id: Long) {
        inflight.remove(id)
        if (!handled.add(id)) return
        queue.addLast(id)
        pump(ctx, dm)
    }

    fun resumed(ctx: Context) {
        busyAt = 0L
        pump(ctx, ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
    }

    fun pending(): Int = queue.size

    private fun pump(ctx: Context, dm: DownloadManager) {
        if (busyAt != 0L && System.currentTimeMillis() - busyAt < 120_000) return
        val next = queue.firstOrNull { it !in selfIds }
            ?: queue.firstOrNull()?.takeIf { (inflight - selfIds).isEmpty() }
            ?: return
        queue.remove(next)
        val uri = dm.getUriForDownloadedFile(next) ?: return pump(ctx, dm)
        busyAt = System.currentTimeMillis()
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure { busyAt = 0L }
    }
}
