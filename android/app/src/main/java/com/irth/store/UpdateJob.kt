package com.irth.store

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * إشعارات التحديث داخل النظام: فحص دوري للكتالوج (كل ٦ ساعات بشبكة متاحة، يبقى بعد إعادة التشغيل)،
 * ويُشعر مرة واحدة لكل إصدار جديد من تطبيق مثبّت — والضغط على «حدّث الكل» يبدأ التنزيلات فوراً.
 */
object Updates {
    const val CH = "updates"
    const val JOB_ID = 4201
    const val NOTIF_ID = 4201
    const val EXTRA_UPDATE_ALL = "update_all"
    const val EXTRA_SHOW_UPDATES = "show_updates"
    private const val PREF = "updates"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", true)
    fun lastCheck(ctx: Context) = prefs(ctx).getLong("last", 0L)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("on", on).apply()
        if (on) schedule(ctx) else (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
    }

    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.allPendingJobs.any { it.id == JOB_ID }) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(info) }
    }

    /** التطبيقات المثبّتة التي لها إصدار أحدث في الكتالوج. */
    fun outdated(ctx: Context, apps: List<AppEntry>) = apps.filter { it.url.isNotEmpty() && stateOf(ctx, it) == AppState.OUTDATED }

    /** يحفظ آخر vcode أُشعر به لكل تطبيق، فلا يتكرر الإشعار للإصدار نفسه. */
    fun notifyIfNew(ctx: Context, apps: List<AppEntry>) {
        val p = prefs(ctx)
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        val outs = outdated(ctx, apps)
        if (outs.isEmpty()) { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID); return }
        val fresh = outs.filter { p.getInt("n.${it.slug}", 0) < it.vcode }
        if (fresh.isEmpty()) return
        val e = p.edit(); fresh.forEach { e.putInt("n.${it.slug}", it.vcode) }; e.apply()
        post(ctx, outs)
    }

    /** فتح المتجر يعني رؤية التحديثات: لا إشعار لاحق لما رآه المستخدم، ويُزال الإشعار القائم. */
    fun markSeen(ctx: Context, apps: List<AppEntry>) {
        val outs = outdated(ctx, apps)
        val e = prefs(ctx).edit(); outs.forEach { e.putInt("n.${it.slug}", it.vcode) }; e.apply()
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(
                NotificationChannel(CH, "تحديثات التطبيقات", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "إشعار عند صدور إصدار جديد من تطبيق مثبّت"
                }
            )
        }
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(ctx: Context, outs: List<AppEntry>) {
        if (!canNotify(ctx)) return
        channel(ctx)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SHOW_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags,
        )
        val all = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_UPDATE_ALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags,
        )
        val title = if (outs.size == 1) "تحديث ${outs[0].name} متاح — ${outs[0].version}" else "${outs.size} تحديثات متاحة"
        val body = if (outs.size == 1) outs[0].whatsnew.ifEmpty { "إصدار جديد جاهز للتثبيت" }
        else outs.joinToString("، ") { "${it.name} ${it.version}" }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        outs.forEach { style.addLine("${it.name} ${it.version}" + if (it.whatsnew.isNotEmpty()) " — ${it.whatsnew}" else "") }
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(if (outs.size == 1) NotificationCompat.BigTextStyle().bigText(body) else style)
            .setColor(0xFFC9A35C.toInt())
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_stat_update, if (outs.size == 1) "حدّث الآن" else "حدّث الكل", all)
            .setNumber(outs.size)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }
}

class UpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val ok = runCatching { Updates.notifyIfNew(applicationContext, fetchCatalog()) }.isSuccess
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
