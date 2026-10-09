package com.irth.store

import android.app.DownloadManager
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
)

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

object DlHub {
    val handled = mutableSetOf<Long>()
    fun install(ctx: Context, dm: DownloadManager, id: Long) {
        if (!handled.add(id)) return
        val uri = dm.getUriForDownloadedFile(id) ?: return
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}

class DlReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = dm.query(DownloadManager.Query().setFilterById(id))
        if (q.moveToFirst() && q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
            DlHub.install(ctx, dm, id)
        }
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
}

fun openApp(ctx: Context, pkg: String) {
    runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) } }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        setContent { StoreApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreApp() {
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
            var myVcode by remember { mutableStateOf(0) }
            var rev by remember { mutableStateOf(0) } // يُعاد حساب حالات التثبيت عند عودته
            val downloads = remember { mutableStateMapOf<String, Dl>() }
            var toast by remember { mutableStateOf<String?>(null) }

            fun reload(done: (() -> Unit)? = null) {
                scope.launch {
                    runCatching { apps = fetchCatalog(); err = false }
                        .onFailure { err = true }
                    done?.invoke()
                }
            }

            LaunchedEffect(Unit) {
                @Suppress("DEPRECATION")
                myVcode = runCatching {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
                    }
                }.getOrDefault(0)
                reload()
            }

            // إعادة تقييم حالة التثبيت عند العودة للتطبيق (بعد تثبيت/حذف)
            DisposableEffect(Unit) {
                val o = object : androidx.lifecycle.LifecycleEventObserver {
                    override fun onStateChanged(s: androidx.lifecycle.LifecycleOwner, e: androidx.lifecycle.Lifecycle.Event) {
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) rev++
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
                                DownloadManager.STATUS_SUCCESSFUL -> { downloads[slug] = d.copy(prog = 1f); DlHub.install(ctx, dm, d.id) }
                                DownloadManager.STATUS_FAILED -> { downloads[slug] = d.copy(failed = true); toast = "تعذر تنزيل $slug" }
                                else -> if (tot > 0) downloads[slug] = d.copy(prog = got.toFloat() / tot.toFloat())
                            }
                        } else downloads[slug] = d.copy(failed = true)
                        q.close()
                    }
                    delay(350)
                }
            }

            fun startDl(a: AppEntry) {
                val id = enqueueApk(ctx, a)
                downloads[a.slug] = Dl(id)
                sel = null
            }

            val self = apps?.firstOrNull { it.slug == "appstore" }
            val selfUpd = self?.takeIf { it.vcode > myVcode && it.url.isNotEmpty() }
            val updates = apps?.filter { it.slug != "appstore" && run { rev; stateOf(ctx, it) } == AppState.OUTDATED } ?: emptyList()
            val filtered = apps?.filter {
                query.isBlank() || it.name.contains(query, true) || it.desc.contains(query, true)
            } ?: emptyList()

            Scaffold(
                containerColor = Bg,
                topBar = { StoreBar(count = apps?.size ?: 0, onRefresh = { refreshing = true; reload { refreshing = false } }) },
                snackbarHost = {
                    toast?.let {
                        Surface(Modifier.fillMaxWidth().padding(16.dp), color = Card, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(it, color = Muted, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                TextButton(onClick = { toast = null }) { Text("حسناً", color = Gold) }
                            }
                        }
                    }
                },
                bottomBar = {
                    selfUpd?.let { u ->
                        Surface(
                            color = Card, border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.SystemUpdate, null, tint = Gold, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("تحديث المتجر متاح — ${u.version}", color = Gold, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                downloads[u.slug]?.takeIf { it.prog in 0f..0.99f }?.let { d ->
                                    CircularProgressIndicator(progress = { d.prog }, modifier = Modifier.size(18.dp), color = Gold, strokeWidth = 2.dp)
                                } ?: TextButton(onClick = { startDl(u) }) { Text("نزّل الآن", color = Primary) }
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
                            onPick = { sel = it }, onDl = ::startDl,
                            onCancel = { a -> downloads[a.slug]?.let { d ->
                                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(d.id)
                                downloads.remove(a.slug)
                            } },
                            onOpen = { a -> openApp(ctx, a.pkg) },
                        )
                    }
                }
            }

            // صفحة تفاصيل التطبيق
            sel?.let { a ->
                val st = remember(rev, a.vcode) { stateOf(ctx, a) }
                val d = downloads[a.slug]
                ModalBottomSheet(onDismissRequest = { sel = null }, containerColor = Card) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a, 64); Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, color = Txt, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (a.tag.isNotEmpty()) Pill(a.tag, Gold)
                                    if (st == AppState.CURRENT) { Spacer(Modifier.width(6.dp)); Pill("مثبّت", Primary) }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (a.version.isNotEmpty()) MetaChip("إصدار ${a.version}")
                            if (a.size.isNotEmpty()) MetaChip(a.size)
                            if (st != AppState.ABSENT) MetaChip("عندك v${installedVcode(ctx, a.pkg)}")
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(a.desc, color = Muted, fontSize = 14.sp, lineHeight = 24.sp)
                        if (a.whatsnew.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("ما الجديد", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(a.whatsnew, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                        Spacer(Modifier.height(20.dp))
                        when {
                            d?.failed == true -> ActionBtn("فشل التنزيل — أعد المحاولة", Icons.Filled.Refresh, Primary) { startDl(a) }
                            d != null && d.prog < 1f -> DlProgress(d.prog) {
                                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(d.id); downloads.remove(a.slug)
                            }
                            a.url.isEmpty() -> ActionBtn("قريباً", Icons.Filled.Schedule, Muted, enabled = false) {}
                            st == AppState.CURRENT -> ActionBtn("افتح", Icons.Filled.OpenInNew, Primary) { openApp(ctx, a.pkg) }
                            st == AppState.OUTDATED -> ActionBtn("حدّث إلى ${a.version}", Icons.Filled.SystemUpdate, Gold) { startDl(a) }
                            else -> ActionBtn("تنزيل وتثبيت", Icons.Filled.Download, Primary) { startDl(a) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StoreBar(count: Int, onRefresh: () -> Unit) {
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
    onPick: (AppEntry) -> Unit, onDl: (AppEntry) -> Unit, onCancel: (AppEntry) -> Unit, onOpen: (AppEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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
        if (updates.isNotEmpty()) {
            item {
                Row(Modifier.padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SystemUpdate, null, tint = Gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("تحديثات متاحة (${updates.size})", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            items(updates, key = { "u-" + it.slug }) { a ->
                AppCard(a, stateOf(a), downloads[a.slug], onPick, onDl, onCancel, onOpen)
            }
        }
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
