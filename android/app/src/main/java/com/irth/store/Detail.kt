package com.irth.store

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

val Amiri = FontFamily(Font(R.font.amiri_bold, FontWeight.Bold), Font(R.font.amiri_regular, FontWeight.Normal))

fun abs(u: String) = if (u.startsWith("http")) u else "$BASE/${u.removePrefix("./").removePrefix("/")}"

/** صفحة التطبيق الكاملة على نمط Play: بطل + إجراء واحد واضح + لقطات + ما الجديد منذ إصدارك + سجل الإصدارات. */
@Composable
fun AppDetail(
    a: AppEntry, st: AppState, installed: Int, d: Dl?,
    onBack: () -> Unit, onDl: () -> Unit, onCancel: () -> Unit, onOpen: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var viewer by remember { mutableStateOf<Int?>(null) }
    var allLog by remember { mutableStateOf(false) }
    val since = if (st == AppState.OUTDATED) a.changes.filter { it.c > installed } else emptyList()

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Txt) }
            Text(a.name, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a, 88); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, color = Txt, fontFamily = Amiri, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp)
                        Text("من مشروع إرث", color = Primary, fontSize = 13.sp)
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (a.tag.isNotEmpty()) Pill(a.tag, Gold)
                            if (st == AppState.CURRENT) { Spacer(Modifier.width(6.dp)); Pill("مثبّت", Primary) }
                            if (st == AppState.OUTDATED) { Spacer(Modifier.width(6.dp)); Pill("تحديث متاح", Gold) }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(a.version.ifEmpty { "—" }, "الإصدار")
                    StatDivider()
                    Stat(a.size.ifEmpty { "—" }, "الحجم")
                    StatDivider()
                    Stat(if (installed > 0) "v$installed" else "—", "عندك")
                    if (a.changes.isNotEmpty()) { StatDivider(); Stat("${a.changes.size}", "إصداراً") }
                }
            }
            item {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    when {
                        d?.failed == true -> ActionBtn("فشل التنزيل — أعد المحاولة", Icons.Filled.Refresh, Primary, onClick = onDl)
                        d != null && d.prog < 1f -> DlProgress(d.prog, onCancel)
                        a.url.isEmpty() -> ActionBtn("قريباً", Icons.Filled.Schedule, Muted, enabled = false) {}
                        st == AppState.CURRENT -> ActionBtn("افتح", Icons.Filled.OpenInNew, Primary, onClick = onOpen)
                        st == AppState.OUTDATED -> ActionBtn("حدّث إلى ${a.version}", Icons.Filled.SystemUpdate, Gold, onClick = onDl)
                        else -> ActionBtn("تنزيل وتثبيت", Icons.Filled.Download, Primary, onClick = onDl)
                    }
                }
            }
            if (a.shots.isNotEmpty()) item {
                LazyRow(
                    Modifier.padding(top = 20.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(a.shots) { i, s ->
                        AsyncImage(
                            model = abs(s), contentDescription = "لقطة ${i + 1} من ${a.name}",
                            modifier = Modifier.height(340.dp).aspectRatio(9f / 19.5f)
                                .clip(RoundedCornerShape(16.dp)).background(Soft)
                                .border(1.dp, Line, RoundedCornerShape(16.dp))
                                .clickable { viewer = i },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            item {
                Section(if (since.size > 1) "ما الجديد منذ إصدارك (${since.size} إصدارات)" else "ما الجديد") {
                    when {
                        since.isNotEmpty() -> since.take(6).forEach { ChangeRow(it, highlight = true) }
                        a.changes.isNotEmpty() -> ChangeRow(a.changes.first(), highlight = true)
                        a.whatsnew.isNotEmpty() -> Text(a.whatsnew, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                        else -> Text("لا ملاحظات لهذا الإصدار", color = Muted, fontSize = 13.sp)
                    }
                    if (since.size > 6) Text("و${since.size - 6} إصدارات أخرى في السجل أدناه", color = Muted, fontSize = 12.sp)
                }
            }
            item {
                Section("عن التطبيق") {
                    Text(a.desc, color = Txt, fontSize = 15.sp, lineHeight = 26.sp)
                }
            }
            if (a.changes.size > 1) item {
                Section("سجل الإصدارات") {
                    Column(Modifier.animateContentSize()) {
                        (if (allLog) a.changes else a.changes.take(5)).forEach { ChangeRow(it, highlight = installed in 1 until it.c) }
                    }
                    if (a.changes.size > 5) TextButton(onClick = { allLog = !allLog }) {
                        Text(if (allLog) "أقل" else "عرض كل الإصدارات (${a.changes.size})", color = Gold)
                    }
                }
            }
            item {
                Section("معلومات") {
                    InfoRow(Icons.Filled.Inventory2, "الحزمة", a.pkg.ifEmpty { "—" })
                    InfoRow(Icons.Filled.Tag, "رقم البناء", if (a.vcode > 0) "${a.vcode}" else "—")
                    InfoRow(Icons.Filled.SdStorage, "حجم التنزيل", a.size.ifEmpty { "—" })
                    InfoRow(Icons.Filled.VerifiedUser, "المصدر", "medmrf-10.github.io/store")
                }
            }
        }
    }
    viewer?.let { start -> ShotViewer(a.shots, start) { viewer = null } }
}

@Composable
private fun Stat(v: String, l: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = Txt, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(l, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun StatDivider() = Box(Modifier.width(1.dp).height(28.dp).background(Line))

@Composable
private fun Section(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        Text(title, color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        body()
    }
}

@Composable
private fun ChangeRow(c: Change, highlight: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            c.v, color = if (highlight) Gold else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(52.dp).background(if (highlight) Gold.copy(alpha = 0.12f) else Soft, RoundedCornerShape(8.dp))
                .padding(vertical = 3.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.width(10.dp))
        Text(c.t, color = if (highlight) Txt else Muted, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, k: String, v: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v, color = Txt, fontSize = 13.sp)
    }
}

/** عارض لقطات بملء الشاشة مع سحب أفقي ومؤشر صفحات. */
@Composable
fun ShotViewer(shots: List<String>, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { shots.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { p ->
                AsyncImage(abs(shots[p]), "لقطة ${p + 1}", Modifier.fillMaxSize().padding(vertical = 40.dp), contentScale = ContentScale.Fit)
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "إغلاق", tint = Txt)
            }
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(shots.size) { i ->
                    Box(Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape).background(if (i == pager.currentPage) Gold else Muted))
                }
            }
        }
    }
}

/** «اختيار المحرر»: بطاقة بطل بأول لقطة وسطر تحريري يقول لماذا هذا التطبيق الآن. */
@Composable
fun EditorPick(a: AppEntry, st: AppState, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(20.dp)).background(Card)
            .border(1.dp, Line, RoundedCornerShape(20.dp)).clickable(onClick = onPick),
    ) {
        if (a.shots.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(170.dp).background(Soft)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.shots.take(3).forEach { s ->
                        AsyncImage(abs(s), null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    }
                }
            }
        }
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = Gold, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("اختيار المحرر", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a, 48); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = Txt, fontFamily = Amiri, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp)
                    Text(a.pick, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when (st) { AppState.CURRENT -> "افتح الصفحة"; AppState.OUTDATED -> "حدّث إلى ${a.version}"; else -> "اكتشف ${a.name}" },
                color = if (st == AppState.OUTDATED) Gold else Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            )
        }
    }
}

/** تهيئة طلب إذن الإشعارات: فائدة واضحة أولاً، و«ليس الآن» لا تستهلك فرصة النظام. */
@Composable
fun NotifyPrime(appName: String?, onEnable: () -> Unit, onLater: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(16.dp)).background(Card)
            .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NotificationsActive, null, tint = Gold, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (appName != null) "اعرف فور صدور إصدار جديد من $appName" else "اعرف فور صدور تحديث لتطبيقاتك",
                color = Txt, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
        }
        Text(
            "تنبيه واحد فقط عند صدور إصدار جديد لتطبيق مثبّت عندك — بلا إعلانات ولا رسائل أخرى. ومنه تحدّث بضغطة.",
            color = Muted, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onEnable, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Card), shape = RoundedCornerShape(12.dp), modifier = Modifier.height(44.dp)) {
                Text("نبّهني بالتحديثات", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onLater, modifier = Modifier.height(44.dp)) { Text("ليس الآن", color = Muted) }
        }
    }
}
