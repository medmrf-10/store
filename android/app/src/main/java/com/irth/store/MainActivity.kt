package com.irth.store

import android.app.DownloadManager
import android.Manifest
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

const val BASE = "https://medmrf-10.github.io/store"
const val CATALOG = "$BASE/apps.json"

@Serializable
data class AppEntry(
    val slug: String = "", val name: String = "", val page: String = "",
    val icon: String = "", val tag: String = "", val desc: String = "",
    val version: String = "", val size: String = "", val url: String = "",
    val vcode: Int = 0, val pkg: String = "", val whatsnew: String = "",
    val shots: List<String> = emptyList(), val changes: List<Change> = emptyList(), val pick: String = "",
)

@Serializable
data class Change(val v: String = "", val c: Int = 0, val t: String = "")

val Bg = Color(0xFF14110E); val Card = Color(0xFF1C1814); val Txt = Color(0xFFEFE6D6)
val Muted = Color(0xFFA39684); val Line = Color(0xFF2F2923); val Primary = Color(0xFFE3A598)
val Gold = Color(0xFFC9A35C); val Soft = Color(0xFF2A241E)

private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
private val sjson = Json { ignoreUnknownKeys = true }

suspend fun fetchCatalog(): List<AppEntry> = withContext(Dispatchers.IO) {
    val body = client.newCall(Request.Builder().url(CATALOG).build()).execute().use { it.body?.string() ?: "" }
    sjson.decodeFromString<Map<String, List<AppEntry>>>(body)["apps"] ?: emptyList()
}

/** حالة التطبيق على الجهاز: غير مثبّت / مثبّت بإصدار قديم / مثبّت بأحدث إصدار. */
enum class AppState { ABSENT, OUTDATED, CURRENT }

fun installedVcode(ctx: Context, pkg: String): Int = runCatching {
    if (pkg.isEmpty()) return -1
    @Suppress("DEPRECATION")
    ctx.packageManager.getPackageInfo(pkg, 0).let {
        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
    }
}.getOrDefault(-1)

fun stateOf(ctx: Context, a: AppEntry): AppState {
    val iv = installedVcode(ctx, a.pkg)
    return when {
        iv < 0 -> AppState.ABSENT
        a.vcode > iv -> AppState.OUTDATED
        else -> AppState.CURRENT
    }
}

data class Dl(var id: Long, var prog: Float = -1f, var failed: Boolean = false)

class DlReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = dm.query(DownloadManager.Query().setFilterById(id))
        if (q.moveToFirst() && q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
            DlHub.ready(ctx, dm, id)
        }
        else DlHub.failed(id)
        q.close()
    }
}

fun enqueueApk(ctx: Context, a: AppEntry): Long {
    val url = if (a.url.startsWith("http")) a.url else "$BASE/${a.url.removePrefix("./").removePrefix("/")}"
    val file = "${a.slug}-${a.version.ifEmpty { "latest" }}.apk"
    val req = DownloadManager.Request(Uri.parse(url))
        .setTitle("تنزيل ${a.name}")
        .setDescription(file)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file)
        .setMimeType("application/vnd.android.package-archive")
    return (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        .also { DlHub.track(it, a.pkg == ctx.packageName) }
}

fun openApp(ctx: Context, pkg: String) {
    runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) } }
}

class MainActivity : ComponentActivity() {
    val action = mutableStateOf<String?>(null)
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        action.value = intent?.getStringExtra(ACTION_KEY)
        Updates.schedule(this)
        setContent { StoreApp(action) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ACTION_KEY)?.let { action.value = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreApp(action: MutableState<String?>) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Primary, onPrimary = Card, background = Bg, onBackground = Txt,
                surface = Bg, onSurface = Txt, surfaceVariant = Card, onSurfaceVariant = Muted,
                outline = Line, secondary = Gold, tertiary = Soft,
            )
        ) {
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
            var err by remember { mutableStateOf(false) }
            var refreshing by remember { mutableStateOf(false) }
            var sel by remember { mutableStateOf<AppEntry?>(null) }
            var query by remember { mutableStateOf("") }
            var rev by remember { mutableStateOf(0) } // يُعاد حساب حالات التثبيت عند عودته
            val downloads = remember { mutableStateMapOf<String, Dl>() }
            var toast by remember { mutableStateOf<String?>(null) }
            var notifOk by remember { mutableStateOf(canNotify(ctx)) }
            var primeFor by remember { mutableStateOf<String?>(null) }
            var showPrime by remember { mutableStateOf(false) }
            val listState = rememberLazyListState()

            val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                notifOk = canNotify(ctx); showPrime = false
                if (ok) { StorePrefs.setNotify(ctx, true); toast = "سننبّهك فور صدور أي تحديث" }
                else toast = "لن نرسل تنبيهات — تجد التحديثات دائماً أعلى المتجر"
            }

            fun enableNotify() {
                StorePrefs.setNotify(ctx, true)
                if (canNotify(ctx)) { notifOk = true; showPrime = false; toast = "تنبيهات التحديث مفعّلة"; return }
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    (StorePrefs.primes(ctx) < 2 || (ctx as? ComponentActivity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // رُفض نهائياً أو أُطفئت من النظام: افتح إعدادات إشعارات المتجر مباشرة
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                    }
                    showPrime = false
                }
            }

            fun maybePrime(name: String?) {
                if (canNotify(ctx) && StorePrefs.notifyOn(ctx)) return
                val days3 = 3L * 24 * 3600 * 1000
                if (StorePrefs.primes(ctx) >= 3 || System.currentTimeMillis() - StorePrefs.lastPrime(ctx) < days3) return
                StorePrefs.primed(ctx); primeFor = name; showPrime = true
            }

            fun reload(done: (() -> Unit)? = null) {
                scope.launch {
                    runCatching { apps = fetchCatalog(); err = false }
                        .onFailure { err = true }
                    apps?.let { list ->
                        // ما يراه المستخدم داخل المتجر لا يحتاج إشعاراً لاحقاً
                        StorePrefs.addNotified(ctx, outdatedApps(ctx, list).map { "${it.pkg}:${it.vcode}" })
                        Updates.clear(ctx)
                    }
                    done?.invoke()
                }
            }

            LaunchedEffect(Unit) { reload() }

            // إعادة تقييم حالة التثبيت عند العودة للتطبيق (بعد تثبيت/حذف) ومتابعة طابور التثبيت
            DisposableEffect(Unit) {
                val o = object : androidx.lifecycle.LifecycleEventObserver {
                    override fun onStateChanged(s: androidx.lifecycle.LifecycleOwner, e: androidx.lifecycle.Lifecycle.Event) {
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { rev++; notifOk = canNotify(ctx); DlHub.resumed(ctx) }
                    }
                }
                (ctx as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(o)
                onDispose { (ctx as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.removeObserver(o) }
            }

            // متابعة تقدّم التنزيلات داخل التطبيق
            LaunchedEffect(downloads.values.any { it.prog < 1f && !it.failed }) {
                while (downloads.values.any { it.prog < 1f && !it.failed }) {
                    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    for ((slug, d) in downloads.entries.toList()) {
                        if (d.prog >= 1f || d.failed) continue
                        val q = dm.query(DownloadManager.Query().setFilterById(d.id))
                        if (q.moveToFirst()) {
                            val st = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val got = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val tot = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            when (st) {
                                DownloadManager.STATUS_SUCCESSFUL -> { downloads[slug] = d.copy(prog = 1f); DlHub.ready(ctx, dm, d.id) }
                                DownloadManager.STATUS_FAILED -> { downloads[slug] = d.copy(failed = true); DlHub.failed(d.id); toast = "تعذر تنزيل $slug" }
                                else -> if (tot > 0) downloads[slug] = d.copy(prog = got.toFloat() / tot.toFloat())
                            }
                        } else { downloads[slug] = d.copy(failed = true); DlHub.failed(d.id) }
                        q.close()
                    }
                    delay(350)
                }
            }

            fun startDl(a: AppEntry, prime: Boolean = true) {
                val busy = downloads[a.slug]?.let { !it.failed && it.prog < 1f } == true
                if (busy) return
                val id = enqueueApk(ctx, a)
                downloads[a.slug] = Dl(id)
                if (prime) maybePrime(a.name)
            }

            val updates = apps?.let { run { rev; outdatedApps(ctx, it) } } ?: emptyList()

            fun updateAll() {
                if (updates.isEmpty()) return
                updates.forEach { startDl(it, prime = false) }
                toast = if (updates.size == 1) "يُنزَّل ${updates[0].name}… يفتح المثبِّت تلقائياً"
                else "تُنزَّل ${updates.size} تحديثات — يفتح المثبِّت لكل واحد بالتتابع"
                maybePrime(null)
            }

            // إجراءات قادمة من الإشعار
            LaunchedEffect(action.value, apps) {
                val act = action.value ?: return@LaunchedEffect
                if (apps == null) return@LaunchedEffect
                sel = null; query = ""
                listState.animateScrollToItem(0)
                if (act == ACT_UPDATE_ALL) updateAll()
                action.value = null
            }

            val filtered = apps?.filter {
                query.isBlank() || it.name.contains(query, true) || it.desc.contains(query, true)
            } ?: emptyList()

            sel?.let { s ->
                val a = apps?.firstOrNull { it.slug == s.slug } ?: s
                val st = remember(rev, a.vcode) { stateOf(ctx, a) }
                AppDetail(
                    a, st, installedVcode(ctx, a.pkg), downloads[a.slug],
                    onBack = { sel = null }, onDl = { startDl(a) },
                    onCancel = {
                        downloads[a.slug]?.let { d ->
                            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(d.id); DlHub.failed(d.id)
                        }
                        downloads.remove(a.slug)
                    },
                    onOpen = { openApp(ctx, a.pkg) },
                )
                return@MaterialTheme
            }

            Scaffold(
                containerColor = Bg,
                topBar = {
                    StoreBar(
                        count = apps?.size ?: 0, notifOn = notifOk && StorePrefs.notifyOn(ctx),
                        onBell = {
                            if (notifOk && StorePrefs.notifyOn(ctx)) { StorePrefs.setNotify(ctx, false); notifOk = canNotify(ctx); rev++; toast = "أُوقفت تنبيهات التحديث" }
                            else { primeFor = null; showPrime = true }
                        },
                        onRefresh = { refreshing = true; reload { refreshing = false } },
                    )
                },
                snackbarHost = {
                    toast?.let {
                        LaunchedEffect(it) { delay(4500); if (toast == it) toast = null }
                        Surface(Modifier.fillMaxWidth().padding(16.dp), color = Card, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(it, color = Muted, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                TextButton(onClick = { toast = null }) { Text("حسناً", color = Gold) }
                            }
                        }
                    }
                },
            ) { pad ->
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { refreshing = true; reload { refreshing = false } },
                    modifier = Modifier.fillMaxSize().padding(pad),
                ) {
                    when {
                        apps == null && !err -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp)
                        }
                        err -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CloudOff, null, tint = Muted, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("تعذر تحميل الكتالوج", color = Txt, fontWeight = FontWeight.Bold)
                            Text("تحقق من اتصالك ثم أعد المحاولة", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { err = false; reload() },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Card),
                            ) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("أعد المحاولة") }
                        }
                        else -> CatalogView(
                            filtered, updates, query, { query = it }, downloads,
                            stateOf = { a -> rev; stateOf(ctx, a) },
                            listState = listState,
                            pick = apps?.firstOrNull { it.pick.isNotEmpty() }?.takeIf { query.isBlank() },
                            prime = if (showPrime) primeFor ?: "" else null,
                            onEnableNotify = ::enableNotify, onLater = { showPrime = false },
                            onUpdateAll = ::updateAll,
                            onPick = { sel = it }, onDl = { startDl(it) },
                            onCancel = { a -> downloads[a.slug]?.let { d ->
                                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(d.id)
                                DlHub.failed(d.id)
                                downloads.remove(a.slug)
                            } },
                            onOpen = { a -> openApp(ctx, a.pkg) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StoreBar(count: Int, notifOn: Boolean, onBell: () -> Unit, onRefresh: () -> Unit) {
    Surface(color = Bg.copy(alpha = 0.95f)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Storefront, null, tint = Primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("App Store", color = Primary, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                if (count > 0) Text("$count تطبيقات", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onBell) {
                Icon(
                    if (notifOn) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
                    if (notifOn) "تنبيهات التحديث مفعّلة" else "فعّل تنبيهات التحديث",
                    tint = if (notifOn) Gold else Muted,
                )
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "تحديث", tint = Muted) }
        }
    }
}

@Composable
fun Pill(t: String, c: Color) {
    Text(t, color = c, fontSize = 10.sp, modifier = Modifier.border(1.dp, c, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun MetaChip(t: String) {
    Text(t, color = Muted, fontSize = 11.sp, modifier = Modifier.background(Soft, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun ActionBtn(label: String, icon: ImageVector, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Card, disabledContainerColor = Soft, disabledContentColor = Muted),
        shape = RoundedCornerShape(12.dp),
    ) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
fun DlProgress(prog: Float, onCancel: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { if (prog < 0) 0f else prog },
                modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Gold, trackColor = Soft,
            )
            Spacer(Modifier.width(10.dp))
            Text(if (prog < 0) "…" else "${(prog * 100).toInt()}٪", color = Gold, fontSize = 12.sp)
            TextButton(onClick = onCancel) { Text("إلغاء", color = Muted, fontSize = 12.sp) }
        }
    }
}

@Composable
fun AppIcon(a: AppEntry, size: Int) {
    val src = if (a.icon.startsWith("http")) a.icon else "$BASE/${a.icon.removePrefix("./").removePrefix("/")}"
    AsyncImage(
        model = src, contentDescription = a.name,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.27).toInt().dp)).background(Soft),
        contentScale = ContentScale.Crop,
    )
}

@Composable
fun CatalogView(
    apps: List<AppEntry>, updates: List<AppEntry>, query: String, onQuery: (String) -> Unit,
    downloads: Map<String, Dl>, stateOf: (AppEntry) -> AppState,
    listState: LazyListState, pick: AppEntry?, prime: String?,
    onEnableNotify: () -> Unit, onLater: () -> Unit, onUpdateAll: () -> Unit,
    onPick: (AppEntry) -> Unit, onDl: (AppEntry) -> Unit, onCancel: (AppEntry) -> Unit, onOpen: (AppEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState) {
        item {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                placeholder = { Text("ابحث في التطبيقات", color = Muted) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, null, tint = Muted) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Gold, unfocusedBorderColor = Line,
                    focusedContainerColor = Card, unfocusedContainerColor = Card,
                    cursorColor = Gold, focusedTextColor = Txt, unfocusedTextColor = Txt,
                ),
            )
        }
        prime?.let { name -> item(key = "prime") { NotifyPrime(name.ifEmpty { null }, onEnableNotify, onLater) } }
        if (updates.isNotEmpty()) {
            item(key = "uh") {
                val active = updates.count { a -> downloads[a.slug]?.let { !it.failed && it.prog < 1f } == true }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SystemUpdate, null, tint = Gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("تحديثات متاحة (${updates.size})", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (active > 0) Text("يُنزَّل $active من ${updates.size}", color = Muted, fontSize = 12.sp)
                    else Button(
                        onClick = onUpdateAll, modifier = Modifier.heightIn(min = 40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Card),
                        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Icon(Icons.Filled.DownloadForOffline, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (updates.size == 1) "حدّث" else "حدّث الكل", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            items(updates, key = { "u-" + it.slug }) { a ->
                AppCard(a, stateOf(a), downloads[a.slug], onPick, onDl, onCancel, onOpen)
            }
        }
        pick?.let { p -> item(key = "pick") { EditorPick(p, stateOf(p)) { onPick(p) } } }
        item { Text("التطبيقات", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)) }
        items(apps.filter { updates.none { u -> u.slug == it.slug } }, key = { it.slug }) { a ->
            AppCard(a, stateOf(a), downloads[a.slug], onPick, onDl, onCancel, onOpen)
        }
        if (apps.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.SearchOff, null, tint = Muted, modifier = Modifier.size(36.dp))
                Text(if (query.isBlank()) "لا تطبيقات بعد" else "لا نتائج لـ«$query»", color = Muted, modifier = Modifier.padding(top = 10.dp))
            }
        }
        item {
            Text(
                "من مشروع إرث — محتوى علمي موثوق في جيبك",
                color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(18.dp),
            )
        }
    }
}

@Composable
fun AppCard(
    a: AppEntry, st: AppState, d: Dl?,
    onPick: (AppEntry) -> Unit, onDl: (AppEntry) -> Unit, onCancel: (AppEntry) -> Unit, onOpen: (AppEntry) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable { onPick(a) }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(a, 56); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, color = Txt, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (a.tag.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Pill(a.tag, Gold) }
                }
                Text(a.desc, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOf(a.version.let { if (it.isNotEmpty()) "إصدار $it" else "" }, a.size).filter { it.isNotEmpty() }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = Gold, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            when {
                d != null && !d.failed && d.prog < 1f -> CircularProgressIndicator(progress = { if (d.prog < 0) 0f else d.prog }, modifier = Modifier.size(24.dp), color = Gold, strokeWidth = 2.dp)
                st == AppState.CURRENT -> Icon(Icons.Filled.CheckCircle, "مثبّت", tint = Primary)
                st == AppState.OUTDATED -> Icon(Icons.Filled.SystemUpdate, "تحديث", tint = Gold)
                a.url.isNotEmpty() -> Icon(Icons.Filled.CloudDownload, "تنزيل", tint = Primary)
                else -> Icon(Icons.Filled.Schedule, "قريباً", tint = Muted)
            }
        }
        // حالة داخل البطاقة نفسها
        when {
            d?.failed == true -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("فشل التنزيل", color = Color(0xFFE07A6A), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDl(a) }) { Text("أعد", color = Gold, fontSize = 12.sp) }
            }
            d != null && d.prog < 1f -> Box(Modifier.padding(top = 10.dp)) { DlProgress(d.prog) { onCancel(a) } }
            st == AppState.CURRENT -> Row(Modifier.padding(top = 10.dp)) {
                SmallBtn("افتح", onOpen = { onOpen(a) })
            }
            st == AppState.OUTDATED -> Row(Modifier.padding(top = 10.dp)) {
                SmallBtn("حدّث إلى ${a.version}", onOpen = { onDl(a) }, accent = true)
            }
        }
    }
}

@Composable
fun SmallBtn(label: String, accent: Boolean = false, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = if (accent) Gold.copy(alpha = 0.15f) else Soft,
        shape = RoundedCornerShape(10.dp),
        border = if (accent) androidx.compose.foundation.BorderStroke(1.dp, Gold) else null,
    ) {
        Text(label, color = if (accent) Gold else Txt, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
    }
}
