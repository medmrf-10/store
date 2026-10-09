package com.irth.store

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
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
    val vcode: Int = 0,
)

val Bg = Color(0xFF14110E); val Card = Color(0xFF1C1814); val Txt = Color(0xFFEFE6D6)
val Muted = Color(0xFFA39684); val Line = Color(0xFF2F2923); val Primary = Color(0xFFE3A598)
val Gold = Color(0xFFC9A35C); val Soft = Color(0xFF2A241E); val Press = Color(0xFF241F1A)

private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
private val sjson = Json { ignoreUnknownKeys = true }

suspend fun fetchCatalog(): List<AppEntry> = withContext(Dispatchers.IO) {
    val body = client.newCall(Request.Builder().url(CATALOG).build()).execute().use { it.body?.string() ?: "" }
    sjson.decodeFromString<Map<String, List<AppEntry>>>(body)["apps"] ?: emptyList()
}

class DlReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = dm.query(DownloadManager.Query().setFilterById(id))
        if (!q.moveToFirst()) return
        if (q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
            val uri = dm.getUriForDownloadedFile(id) ?: return
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        setContent { StoreApp() }
    }
}

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
            var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
            var err by remember { mutableStateOf(false) }
            var sel by remember { mutableStateOf<AppEntry?>(null) }
            var upd by remember { mutableStateOf<AppEntry?>(null) }
            val scope = rememberCoroutineScope()
            val ctx = LocalContext.current
            fun reload() { scope.launch { runCatching { apps = fetchCatalog() }.onFailure { err = true } } }
            LaunchedEffect(Unit) {
                reload()
                val vc = runCatching {
                    @Suppress("DEPRECATION")
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
                }.getOrDefault(0)
                runCatching { upd = fetchCatalog().firstOrNull { it.slug == "appstore" && it.vcode > vc && it.url.isNotEmpty() } }
            }

            Scaffold(containerColor = Bg, topBar = { StoreBar() }) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    when {
                        apps == null && !err -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp)
                        }
                        err -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                            Text("تعذر تحميل الكتالوج", color = Muted)
                            TextButton(onClick = { err = false; reload() }) {
                                Icon(Icons.Filled.Refresh, null, tint = Gold); Spacer(Modifier.width(6.dp)); Text("أعد المحاولة", color = Gold)
                            }
                        }
                        else -> AppList(apps!!) { sel = it }
                    }
                    upd?.let { u ->
                        Surface(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                            color = Card, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("تحديث المتجر متاح — ${u.version}", color = Gold, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                TextButton(onClick = { downloadApk(ctx, u); upd = null }) { Text("نزّل الآن", color = Primary) }
                            }
                        }
                    }
                }
            }

            sel?.let { a ->
                AlertDialog(
                    onDismissRequest = { sel = null },
                    containerColor = Card,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a, 44); Spacer(Modifier.width(10.dp)); Text(a.name, color = Txt, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column {
                            Text(a.desc, color = Muted, fontSize = 14.sp, lineHeight = 24.sp)
                            val meta = listOf(a.version.let { if (it.isNotEmpty()) "إصدار $it" else "" }, a.size).filter { it.isNotEmpty() }.joinToString(" · ")
                            if (meta.isNotEmpty()) Text(meta, color = Gold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    },
                    confirmButton = {
                        val ctx = LocalContext.current
                        if (a.url.isNotEmpty()) {
                            Button(
                                onClick = { downloadApk(ctx, a); sel = null },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Card),
                            ) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text("تنزيل وتثبيت") }
                        } else Text("قريباً", color = Muted)
                    },
                    dismissButton = { TextButton(onClick = { sel = null }) { Text("إغلاق", color = Muted) } },
                )
            }
        }
    }
}

fun downloadApk(ctx: Context, a: AppEntry) {
    val url = if (a.url.startsWith("http")) a.url else "$BASE/${a.url.removePrefix("./").removePrefix("/")}"
    val file = "${a.slug}-${a.version.ifEmpty { "latest" }}.apk"
    val req = DownloadManager.Request(Uri.parse(url))
        .setTitle("تنزيل ${a.name}")
        .setDescription(file)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file)
        .setMimeType("application/vnd.android.package-archive")
    (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
}

@Composable
fun StoreBar() {
    Surface(color = Bg.copy(alpha = 0.95f)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Storefront, null, tint = Primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("App Store", color = Primary, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
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
fun AppList(apps: List<AppEntry>, onPick: (AppEntry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("متجرنا", color = Txt, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Text("تطبيقاتنا على أندرويد — نزّل وثبّت مباشرة", color = Muted, fontSize = 13.sp)
            }
        }
        item { Text("التطبيقات", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
        itemsIndexed(apps) { _, a ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(16.dp))
                    .clickable { onPick(a) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(a, 56); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = Txt, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(a.desc, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOf(a.version.let { if (it.isNotEmpty()) "إصدار $it" else "" }, a.size).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, color = Gold, fontSize = 11.sp)
                }
                if (a.tag.isNotEmpty()) Text(a.tag, color = Gold, fontSize = 10.sp, modifier = Modifier.border(1.dp, Gold, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (a.url.isNotEmpty()) Icons.Filled.CloudDownload else Icons.Filled.CheckCircle,
                    null, tint = if (a.url.isNotEmpty()) Primary else Muted,
                )
            }
        }
        if (apps.isEmpty()) item { Text("لا تطبيقات بعد", color = Muted, modifier = Modifier.padding(30.dp)) }
        item {
            Text("من مشروع إرث — محتوى علمي موثوق في جيبك", color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
