package com.kalotrapezis.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The guide (Plan.md (5b); the user, 26 Oct: "click here, choose here, allow this because this … go through it many
 * times … use it while reading it"). Chapters of steps; a step is a few words and a drawn copy of that bit of Tetra with
 * the spot to tap ringed. Try it opens the real screen with the step docked under it (beside it on a tablet). It opens
 * by itself on the first start and from Settings › Guide any time. Drawn, not screenshots: the copies use Tetra's own
 * strings, so every step is in the language the app is in.
 */

/** Where a step's Try it goes; None stays in the guide. */
internal enum class GuideGo { None, Home, Files, Settings, Sync }

/** A permission a step asks for, with its state and an Allow button. */
internal enum class GuideAsk { AllFiles, Photos, Notifications, Alarms, Camera, Contacts, Phone, Usage, Location }

internal enum class GuidePic {
    Welcome, SettingsRow, AndroidDialog, AndroidSwitch, HomeOpen, HomePencil, HomeMove, HomeSize, HomeHide, HomeDone,
    FilesFolder, FilesHold, FilesBar, FilesWhere, FilesMore, FilesNew, FilesTrash, Alone, SyncScan, SettingsLook, SettingsFind, Icons,
}

internal class GuideStep(@StringRes val title: Int, @StringRes val body: Int, val pic: GuidePic, val go: GuideGo = GuideGo.None, val ask: GuideAsk? = null)

internal enum class GuideChapter(@StringRes val title: Int, val icon: Int, val steps: List<GuideStep>) {
    Start(R.string.guide_ch_start, R.drawable.ic_school, listOf(
        GuideStep(R.string.guide_welcome_t, R.string.guide_welcome_b, GuidePic.Welcome),
        GuideStep(R.string.guide_again_t, R.string.guide_again_b, GuidePic.SettingsRow, GuideGo.Settings),
    )),
    Computer(R.string.guide_ch_computer, R.drawable.ic_computer, listOf(
        GuideStep(R.string.guide_alone_t, R.string.guide_alone_b, GuidePic.Alone),
        GuideStep(R.string.guide_scan_t, R.string.guide_scan_b, GuidePic.SyncScan, GuideGo.Sync),
    )),
    Permissions(R.string.guide_ch_permissions, R.drawable.ic_lock, listOf(
        GuideStep(R.string.guide_perm_t, R.string.guide_perm_b, GuidePic.AndroidDialog),
        GuideStep(R.string.guide_files_t, R.string.guide_files_b, GuidePic.AndroidSwitch, ask = GuideAsk.AllFiles),
        GuideStep(R.string.guide_photos_t, R.string.guide_photos_b, GuidePic.AndroidDialog, ask = GuideAsk.Photos),
        GuideStep(R.string.guide_notify_t, R.string.guide_notify_b, GuidePic.AndroidDialog, ask = GuideAsk.Notifications),
        GuideStep(R.string.guide_alarms_t, R.string.guide_alarms_b, GuidePic.AndroidSwitch, ask = GuideAsk.Alarms),
        GuideStep(R.string.guide_camera_t, R.string.guide_camera_b, GuidePic.AndroidDialog, ask = GuideAsk.Camera),
        GuideStep(R.string.guide_contacts_t, R.string.guide_contacts_b, GuidePic.AndroidDialog, ask = GuideAsk.Contacts),
        GuideStep(R.string.guide_phone_t, R.string.guide_phone_b, GuidePic.AndroidDialog, ask = GuideAsk.Phone),
        GuideStep(R.string.guide_usage_t, R.string.guide_usage_b, GuidePic.AndroidSwitch, ask = GuideAsk.Usage),
        GuideStep(R.string.guide_location_t, R.string.guide_location_b, GuidePic.AndroidDialog, ask = GuideAsk.Location),
    )),
    Home(R.string.guide_ch_home, R.drawable.ic_home, listOf(
        GuideStep(R.string.guide_open_t, R.string.guide_open_b, GuidePic.HomeOpen, GuideGo.Home),
        GuideStep(R.string.guide_pencil_t, R.string.guide_pencil_b, GuidePic.HomePencil, GuideGo.Home),
        GuideStep(R.string.guide_move_t, R.string.guide_move_b, GuidePic.HomeMove, GuideGo.Home),
        GuideStep(R.string.guide_size_t, R.string.guide_size_b, GuidePic.HomeSize, GuideGo.Home),
        GuideStep(R.string.guide_hide_t, R.string.guide_hide_b, GuidePic.HomeHide, GuideGo.Home),
        GuideStep(R.string.guide_done_t, R.string.guide_done_b, GuidePic.HomeDone, GuideGo.Home),
    )),
    Files(R.string.guide_ch_files, R.drawable.ic_folder, listOf(
        GuideStep(R.string.guide_folder_t, R.string.guide_folder_b, GuidePic.FilesFolder, GuideGo.Files),
        GuideStep(R.string.guide_hold_t, R.string.guide_hold_b, GuidePic.FilesHold, GuideGo.Files),
        GuideStep(R.string.guide_bar_t, R.string.guide_bar_b, GuidePic.FilesBar, GuideGo.Files),
        GuideStep(R.string.guide_where_t, R.string.guide_where_b, GuidePic.FilesWhere, GuideGo.Files),
        GuideStep(R.string.guide_more_t, R.string.guide_more_b, GuidePic.FilesMore, GuideGo.Files),
        GuideStep(R.string.guide_new_t, R.string.guide_new_b, GuidePic.FilesNew, GuideGo.Files),
        GuideStep(R.string.guide_trash_t, R.string.guide_trash_b, GuidePic.FilesTrash, GuideGo.Files),
    )),
    Settings(R.string.guide_ch_settings, R.drawable.ic_settings, listOf(
        GuideStep(R.string.guide_look_t, R.string.guide_look_b, GuidePic.SettingsLook, GuideGo.Settings),
        GuideStep(R.string.guide_find_t, R.string.guide_find_b, GuidePic.SettingsFind, GuideGo.Settings),
    )),
    Icons(R.string.guide_ch_icons, R.drawable.ic_view_grid, listOf(GuideStep(R.string.guide_icons_t, R.string.guide_icons_b, GuidePic.Icons))),
}

/** The icon catalog (the user, 26 Oct: "a catalog with the iconography … and what each means"): Tetra's own icons, as the screens draw them. */
private val ICONS = listOf(
    R.drawable.ic_chevron_left to R.string.guide_icon_back,
    R.drawable.ic_close to R.string.guide_icon_close,
    R.drawable.ic_check to R.string.guide_icon_check,
    R.drawable.ic_add to R.string.guide_icon_add,
    R.drawable.ic_search to R.string.guide_icon_search,
    R.drawable.ic_edit to R.string.guide_icon_edit,
    R.drawable.ic_more_vert to R.string.guide_icon_more,
    R.drawable.ic_share to R.string.guide_icon_share,
    R.drawable.ic_copy to R.string.guide_icon_copy,
    R.drawable.ic_move to R.string.guide_icon_move,
    R.drawable.ic_delete to R.string.guide_icon_delete,
    R.drawable.ic_restore to R.string.guide_icon_restore,
    R.drawable.ic_tag to R.string.guide_icon_tag,
    R.drawable.ic_favorite_border to R.string.guide_icon_favorite,
    R.drawable.ic_fullscreen to R.string.guide_icon_size,
    R.drawable.ic_visibility_off to R.string.guide_icon_hide,
    R.drawable.ic_drag_handle to R.string.guide_icon_handle,
    R.drawable.ic_unfold_more to R.string.guide_icon_choices,
    R.drawable.ic_undo to R.string.guide_icon_undo,
    R.drawable.ic_redo to R.string.guide_icon_redo,
    R.drawable.ic_save to R.string.guide_icon_save,
    R.drawable.ic_refresh to R.string.guide_icon_refresh,
    R.drawable.ic_sync to R.string.guide_icon_sync,
    R.drawable.ic_qr_code to R.string.guide_icon_qr,
    R.drawable.ic_id_card to R.string.guide_icon_card,
    R.drawable.ic_computer to R.string.guide_icon_computer,
    R.drawable.ic_lock to R.string.guide_icon_lock,
    R.drawable.ic_notifications to R.string.guide_icon_notifications,
    R.drawable.ic_info to R.string.guide_icon_info,
    R.drawable.ic_settings to R.string.guide_icon_settings,
    R.drawable.ic_home to R.string.guide_icon_home,
    R.drawable.ic_toc to R.string.guide_icon_toc,
    R.drawable.ic_school to R.string.guide_icon_guide,
)

/** A place in the guide as one Int (chapter × 100 + step), so it survives a turn of the screen as it is. */
internal object GuidePlace {
    fun of(chapter: Int, step: Int = 0) = chapter * 100 + step
    fun chapter(at: Int) = GuideChapter.entries[at / 100]
    fun step(at: Int) = chapter(at).steps[at % 100]
    fun next(at: Int): Int? = when {
        at % 100 + 1 < chapter(at).steps.size -> at + 1
        at / 100 + 1 < GuideChapter.entries.size -> of(at / 100 + 1)
        else -> null
    }
    fun previous(at: Int): Int? = when {
        at % 100 > 0 -> at - 1
        at / 100 > 0 -> of(at / 100 - 1, GuideChapter.entries[at / 100 - 1].steps.size - 1)
        else -> null
    }
}

/** The guide's mark colour: the one bright thing in a picture, so the eye finds the spot to tap. */
@Composable private fun markColour() = lookTint(Color(0xFFFFB020), Color(0xFFB25E00))

/** A pulsing ring round the spot to tap, and a dot where the finger goes. */
@Composable private fun Modifier.mark(): Modifier {
    val pulse by rememberInfiniteTransition(label = "mark").animateFloat(0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "pulse")
    val c = markColour()
    return drawWithContent {
        drawContent()
        val ring = 4.dp.toPx(); val grow = ring + pulse * 10.dp.toPx(); val r = 14.dp.toPx()
        drawRoundRect(c.copy(alpha = 1f - pulse), Offset(-grow, -grow), Size(size.width + 2 * grow, size.height + 2 * grow), CornerRadius(r + grow), style = Stroke(2.dp.toPx()))
        drawRoundRect(c, Offset(-ring, -ring), Size(size.width + 2 * ring, size.height + 2 * ring), CornerRadius(r), style = Stroke(3.dp.toPx()))
        drawCircle(c.copy(alpha = 0.3f), 8.dp.toPx(), center)
    }
}

/** Where a chapter or step stands: "Home · 2 of 6". */
@Composable private fun whereLabel(at: Int) = GuidePlace.chapter(at).let { ch -> stringResource(ch.title) + " · " + stringResource(R.string.guide_step_of, at % 100 + 1, ch.steps.size) }

/**
 * The guide, the whole screen: the chapters beside the step on a tablet (a menu of them on a phone), the step's words,
 * its picture, its permission if it asks for one, and Back · Try it · Next.
 */
@Composable
internal fun GuideScreen(at: Int, go: (Int) -> Unit, tryIt: (GuideGo) -> Unit, close: () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val chapter = GuidePlace.chapter(at); val step = GuidePlace.step(at)
    BackHandler { GuidePlace.previous(at)?.let(go) ?: close() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (wide) Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
                GuideChapter.entries.forEach { ch -> ChapterRow(ch, ch == chapter) { go(GuidePlace.of(ch.ordinal)) } }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (wide) Text(stringResource(R.string.guide_step_of, at % 100 + 1, chapter.steps.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    else Box(Modifier.weight(1f)) {
                        var menu by remember { mutableStateOf(false) }
                        TextButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_toc), contentDescription = stringResource(R.string.guide_chapters), modifier = Modifier.size(20.dp))
                            Text(whereLabel(at), modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            GuideChapter.entries.forEach { ch ->
                                DropdownMenuItem(text = { Text(stringResource(ch.title)) }, leadingIcon = { Icon(painterResource(ch.icon), null, Modifier.size(20.dp)) },
                                    onClick = { menu = false; go(GuidePlace.of(ch.ordinal)) })
                            }
                        }
                    }
                    TextButton(onClick = close) { Text(stringResource(if (chapter == GuideChapter.Start) R.string.guide_skip else R.string.common_close), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(step.title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(step.body), style = MaterialTheme.typography.bodyLarge)
                        GuideFrame(step)
                        step.ask?.let { GuideAskRow(it) }
                    }
                }
                GuideButtons(at, go, close, Modifier.widthIn(max = 720.dp).padding(bottom = 12.dp)) {
                    if (step.go != GuideGo.None) OutlinedButton(onClick = { tryIt(step.go) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.guide_try_it), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable private fun ChapterRow(ch: GuideChapter, chosen: Boolean, click: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .clickable(onClick = click).heightIn(min = 52.dp).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    Icon(painterResource(ch.icon), contentDescription = null, modifier = Modifier.size(22.dp))
    Text(stringResource(ch.title), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Back (when there is a step before), [middle], and Next — Finish on the very last step. */
@Composable private fun GuideButtons(at: Int, go: (Int) -> Unit, close: () -> Unit, modifier: Modifier = Modifier, middle: @Composable RowScope.() -> Unit = {}) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuidePlace.previous(at)?.let { p -> OutlinedButton(onClick = { go(p) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(stringResource(R.string.common_back), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        middle()
        val next = GuidePlace.next(at)
        Button(onClick = { if (next != null) go(next) else close() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Text(stringResource(if (next != null) R.string.common_next else R.string.guide_finish), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

/**
 * The app's screen with the guide's step docked: under it on a phone, beside it on a tablet (with the picture there,
 * which has room). [at] null: the screen alone. The screen keeps its place in the composition either way, so docking
 * or closing the step loses nothing on it.
 */
@Composable
internal fun GuideDock(at: Int?, go: (Int) -> Unit, full: () -> Unit, close: () -> Unit, content: @Composable () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    if (wide) Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        if (at != null) Surface(Modifier.width(360.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DockTop(at, full, close)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(GuidePlace.step(at).title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(GuidePlace.step(at).body), style = MaterialTheme.typography.bodyLarge)
                    GuideFrame(GuidePlace.step(at))
                }
                GuideButtons(at, go, close)
            }
        }
    } else Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().then(if (at != null) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) { content() }
        if (at != null) Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DockTop(at, full, close)
                Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(GuidePlace.step(at).title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(GuidePlace.step(at).body), style = MaterialTheme.typography.bodyMedium)
                }
                GuideButtons(at, go, close)
            }
        }
    }
}

@Composable private fun DockTop(at: Int, full: () -> Unit, close: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(whereLabel(at), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    IconButton(onClick = full) { Icon(painterResource(R.drawable.ic_school), contentDescription = stringResource(R.string.guide_open)) }
    IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close)) }
}

// ---- permissions ----

private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** Allowed, not yet, or null: not on this device (calls on a tablet without a SIM). */
private fun granted(c: Context, ask: GuideAsk): Boolean? = when (ask) {
    GuideAsk.AllFiles -> storageAllowed(c)
    GuideAsk.Photos -> hasVisualAccess(c)
    GuideAsk.Notifications -> androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()
    GuideAsk.Alarms -> Build.VERSION.SDK_INT < 31 || c.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
    GuideAsk.Camera -> has(c, Manifest.permission.CAMERA)
    GuideAsk.Contacts -> has(c, Manifest.permission.READ_CONTACTS) && has(c, Manifest.permission.WRITE_CONTACTS)
    GuideAsk.Phone -> if (PhoneRelay.noCalls(c)) null else PhoneCall.allAllowed(c) && has(c, Manifest.permission.READ_CALL_LOG)
    GuideAsk.Usage -> DataStats.hasAccess(c)
    GuideAsk.Location -> has(c, Manifest.permission.ACCESS_FINE_LOCATION)
}

/** Android's own question, where there is one; the rest are switches on a page of Android's settings. */
private fun question(ask: GuideAsk): Array<String>? = when (ask) {
    GuideAsk.Photos -> photoPermissions()
    GuideAsk.Notifications -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else null
    GuideAsk.Camera -> arrayOf(Manifest.permission.CAMERA)
    GuideAsk.Contacts -> arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    GuideAsk.Phone -> PhoneCall.PERMISSIONS + Manifest.permission.READ_CALL_LOG
    GuideAsk.Location -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    else -> null
}

private fun settingsPage(c: Context, ask: GuideAsk): Intent {
    val pkg = Uri.parse("package:${c.packageName}")
    return when (ask) {
        GuideAsk.AllFiles -> Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
        GuideAsk.Alarms -> if (Build.VERSION.SDK_INT >= 31) Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        GuideAsk.Usage -> DataStats.accessIntent(c)
        GuideAsk.Notifications -> Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageName)
        else -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }
}

/** The permission's state and its button: Allow asks Android; once Android stops asking (said no twice), Settings. */
@Composable private fun GuideAskRow(ask: GuideAsk) {
    val context = LocalContext.current
    val back = resumes()
    var asked by rememberSaveable(ask) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true; back.intValue++
        if (ask == GuideAsk.Phone) PhoneCall.Watcher.start(context.applicationContext)
        if (ask == GuideAsk.Location) SyncListenService.start(context)
    }
    val ok = remember(back.intValue, ask) { granted(context, ask) }
    val q = question(ask)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(if (ok == true) R.drawable.ic_check else R.drawable.ic_info), contentDescription = null, tint = if (ok == false) markColour() else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(when (ok) { true -> R.string.guide_allowed; false -> R.string.guide_not_yet; null -> R.string.guide_not_here }), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (ok == false) Button(onClick = {
            if (q != null && !asked) launcher.launch(q)
            else runCatching { context.startActivity(settingsPage(context, ask).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }) { Text(stringResource(if (q != null && !asked) R.string.common_allow else R.string.common_settings), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private fun askIcon(ask: GuideAsk?) = when (ask) {
    GuideAsk.AllFiles -> R.drawable.ic_folder; GuideAsk.Photos -> R.drawable.ic_gallery; GuideAsk.Notifications -> R.drawable.ic_notifications
    GuideAsk.Alarms -> R.drawable.ic_schedule; GuideAsk.Camera -> R.drawable.ic_document_scanner; GuideAsk.Contacts -> R.drawable.ic_contacts
    GuideAsk.Phone -> R.drawable.ic_call; GuideAsk.Usage -> R.drawable.ic_data_usage; GuideAsk.Location -> R.drawable.ic_my_location
    null -> R.drawable.ic_lock
}

// ---- the pictures: Tetra's own pieces drawn small, the spot to tap marked ----

@Composable private fun GuideFrame(step: GuideStep) = Surface(
    Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)),
) { Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(horizontal = 20.dp, vertical = 24.dp), contentAlignment = Alignment.Center) { GuidePicture(step) } }

@Composable private fun GuidePicture(step: GuideStep) {
    val name = stringResource(R.string.app_name)
    when (step.pic) {
        GuidePic.Welcome -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.drawable.gallery_art to R.string.app_photos, R.drawable.files_art to R.string.app_files, R.drawable.notes_art to R.string.app_notes, R.drawable.calendar_art to R.string.app_calendar)
                .forEach { (art, label) -> MiniApplet(art, label) }
        }
        GuidePic.SettingsRow -> Rows {
            MiniRow(R.drawable.ic_school, stringResource(R.string.guide_title), stringResource(R.string.guide_summary), Modifier.mark())
            MiniRow(R.drawable.ic_sync, stringResource(R.string.app_sync), stringResource(R.string.settings_sync_summary))
        }
        GuidePic.AndroidDialog -> Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(askIcon(step.ask)), contentDescription = null, modifier = Modifier.size(28.dp))
                Text("$name · ${stringResource(if (step.ask == null) R.string.guide_ch_permissions else step.title)}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MiniButton(stringResource(R.string.common_allow), Modifier.mark())
                MiniButton(stringResource(R.string.guide_dont_allow))
            }
        }
        GuidePic.AndroidSwitch -> Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 360.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ArtImage(R.drawable.settings_art, 28.dp); Text(name, style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(step.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Switch(checked = true, onCheckedChange = null, modifier = Modifier.mark())
                }
            }
        }
        GuidePic.HomeOpen -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniApplet(R.drawable.gallery_art, R.string.app_photos)
            MiniApplet(R.drawable.files_art, R.string.app_files, Modifier.mark())
            MiniApplet(R.drawable.notes_art, R.string.app_notes)
            MiniApplet(R.drawable.calendar_art, R.string.app_calendar)
        }
        GuidePic.HomePencil -> HomeHeader(name) { Box(Modifier.size(40.dp).mark(), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) } }
        // the part held, carried to the empty cell, and again
        GuidePic.HomeMove -> Box(Modifier.width(224.dp).height(96.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.align(Alignment.CenterEnd).size(88.dp).border(1.5.dp, markColour().copy(alpha = 0.6f), RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), RoundedCornerShape(16.dp)))
            val t = loop(2800)
            EditedPart(Modifier.graphicsLayer { translationX = 136.dp.toPx() * eased(t, 0.15f, 0.7f); val lift = if (t in 0.1f..0.75f) 1.06f else 1f; scaleX = lift; scaleY = lift }.mark())
        }
        GuidePic.HomeSize -> EditedPart(sizeMark = Modifier.mark())
        GuidePic.HomeHide -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditedPart(eyeMark = Modifier.mark())
            Text(stringResource(R.string.home_hidden_tap), style = MaterialTheme.typography.titleSmall)
            MiniApplet(R.drawable.timer_art, R.string.app_timer, Modifier.alpha(0.55f))
        }
        GuidePic.HomeDone -> HomeHeader(stringResource(R.string.home_edit)) {
            TextButton(onClick = {}) { Text(stringResource(R.string.home_reset), maxLines = 1) }
            Button(onClick = {}, modifier = Modifier.mark()) { Text(stringResource(R.string.common_done), maxLines = 1) }
        }
        GuidePic.FilesFolder -> Rows {
            MiniFile(R.drawable.ic_folder_documents, "Documents", stringResource(R.string.files_system_folder), modifier = Modifier.mark())
            MiniFile(R.drawable.ic_file_word, "Report.docx", "Words")
        }
        GuidePic.FilesHold -> Rows {
            MiniFile(R.drawable.ic_folder_documents, "Documents", stringResource(R.string.files_system_folder))
            MiniFile(R.drawable.ic_check, "Report.docx", "Words", chosen = true, modifier = Modifier.mark())
            MiniFile(R.drawable.ic_table, "Budget.xlsx", "Tables")
        }
        GuidePic.FilesBar -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Island {
            Row { listOf(R.drawable.ic_share, R.drawable.ic_copy, R.drawable.ic_move, R.drawable.ic_tag, R.drawable.ic_favorite_border, R.drawable.ic_delete).forEach { icon ->
                Box(Modifier.size(44.dp).then(if (icon == R.drawable.ic_move) Modifier.mark() else Modifier), contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null) }
            } }
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.files_selected, 1, 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
        }
            Flow(listOf(R.drawable.ic_folder_documents, R.drawable.ic_folder), label = stringResource(R.string.common_move))
            Flow(listOf(R.drawable.ic_folder_documents, R.drawable.ic_folder), copy = true, label = stringResource(R.string.common_copy))
        }
        GuidePic.FilesWhere -> Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.files_move_into), style = MaterialTheme.typography.bodyMedium)
            Text("Tetra › Documents", style = MaterialTheme.typography.titleMedium)
            MiniFile(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.files_up_to, "Tetra"), null)
            MiniFile(R.drawable.ic_folder_education, "School", null)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Button(onClick = {}, modifier = Modifier.weight(1f).mark()) { Text(stringResource(R.string.files_move_here), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        GuidePic.FilesMore -> Rows { MiniFile(R.drawable.ic_file_word, "Report.docx", "Words", moreMark = Modifier.mark()) }
        GuidePic.FilesNew -> Island { Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_home), contentDescription = null) }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_favorite_border), contentDescription = null) }
            Surface(color = driveNavigationSelectedColor(), contentColor = driveNavigationSelectedContentColor(), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(22.dp)); Text(stringResource(R.string.app_files), maxLines = 1) }
            }
            Box(Modifier.size(44.dp).mark(), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_add), contentDescription = null) }
        } }
        GuidePic.FilesTrash -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Island {
                Box(Modifier.padding(top = 6.dp).width(40.dp).height(14.dp).mark(), contentAlignment = Alignment.Center) { Box(Modifier.width(28.dp).height(3.dp).background(islandContentColor().copy(alpha = 0.65f), CircleShape)) }
                Row(Modifier.padding(8.dp)) { listOf(R.drawable.ic_home, R.drawable.ic_favorite_border, R.drawable.ic_folder, R.drawable.ic_add).forEach { Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(painterResource(it), null) } } }
            }
            Flow(listOf(R.drawable.ic_folder, R.drawable.ic_delete), back = true, label = stringResource(R.string.files_trash) + " · " + stringResource(R.string.photos_restore))
        }
        GuidePic.Alone -> SyncHeader()
        GuidePic.SyncScan -> Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SyncHeader()
            MiniButton(stringResource(R.string.sync_scan_code), Modifier.fillMaxWidth().mark(), R.drawable.ic_qr_code)
            MiniButton(stringResource(R.string.sync_show_device), Modifier.fillMaxWidth(), R.drawable.ic_id_card)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Flow(listOf(R.drawable.ic_gallery, R.drawable.ic_computer), copy = true) }
        }
        GuidePic.SettingsLook -> Rows {
            MiniRow(R.drawable.ic_sync, stringResource(R.string.app_sync), stringResource(R.string.settings_sync_summary))
            MiniRow(R.drawable.ic_palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_summary), Modifier.mark())
        }
        GuidePic.Icons -> androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ICONS.forEach { (icon, meaning) ->
                Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null) }
                    Text(stringResource(meaning), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
        GuidePic.SettingsFind -> Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().mark()) {
            Row(Modifier.padding(horizontal = 16.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(22.dp)); Text(stringResource(R.string.settings_search), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 0 → 1 over [ms], over and over: the clock of a picture that moves. */
@Composable private fun loop(ms: Int): Float =
    rememberInfiniteTransition(label = "loop").animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = "t").value

/** Where [t] is between [from] and [to], 0 before and 1 after, eased in and out. */
private fun eased(t: Float, from: Float, to: Float) = FastOutSlowInEasing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

/**
 * What a step does, played over and over (the user, 26 Oct: "if you throw files there they fly there, if you click copy
 * there they go everywhere"): a file flies from one place to the other. [copy]: one stays behind; [back]: it goes in and
 * comes out again (Trash, then Restore). The same as the desktop guide's Flow.
 */
@Composable private fun Flow(places: List<Int>, copy: Boolean = false, back: Boolean = false, label: String? = null) {
    val t = loop(if (back) 3600 else 2600)
    val span = 188.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Box(Modifier.width(240.dp).height(64.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                places.forEach { Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(painterResource(it), null, Modifier.size(26.dp)) } }
            }
            if (copy) FlowFile(Modifier)
            val at = if (back) eased(t, 0.08f, 0.38f) - eased(t, 0.55f, 0.88f) else eased(t, 0.12f, 0.62f)
            val seen = if (back) 1f - 0.8f * at else 1f - eased(t, 0.62f, 0.75f)
            FlowFile(Modifier.graphicsLayer { translationX = span.toPx() * at; alpha = seen; if (back) { scaleX = 1f - 0.4f * at; scaleY = scaleX } })
        }
    }
}

@Composable private fun FlowFile(modifier: Modifier) = Surface(modifier.padding(start = 13.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.background, shadowElevation = 2.dp) {
    Icon(painterResource(R.drawable.ic_file), contentDescription = null, tint = markColour(), modifier = Modifier.padding(3.dp).size(20.dp))
}

@Composable private fun MiniApplet(art: Int, @StringRes label: Int, modifier: Modifier = Modifier) =
    Column(modifier.width(76.dp).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtImage(art, 48.dp)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }

/** A Home part while Home is edited: its frame, ⤢ and the eye in its corner (as HomeGrid draws them). */
@Composable private fun EditedPart(modifier: Modifier = Modifier, sizeMark: Modifier = Modifier, eyeMark: Modifier = Modifier) =
    Box(modifier.size(88.dp).border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), MaterialTheme.shapes.large)) {
        Box(Modifier.align(Alignment.Center)) { MiniApplet(R.drawable.files_art, R.string.app_files) }
        Row(Modifier.fillMaxWidth().padding(2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            EditButton(R.drawable.ic_fullscreen, sizeMark); EditButton(R.drawable.ic_visibility_off, eyeMark)
        }
    }

@Composable private fun EditButton(icon: Int, modifier: Modifier) = Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = modifier.size(26.dp)) {
    Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp)) }
}

@Composable private fun HomeHeader(title: String, end: @Composable RowScope.() -> Unit) =
    Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        end()
    }

@Composable private fun Rows(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** A Settings row: icon, title, its line, the chevron. */
@Composable private fun MiniRow(icon: Int, title: String, summary: String, modifier: Modifier = Modifier) =
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(18.dp))
    }

/** A row of Files: its icon, name and kind, the three dots; pale when [chosen], as DriveRowItem draws it. */
@Composable private fun MiniFile(icon: Int, name: String, kind: String?, chosen: Boolean = false, modifier: Modifier = Modifier, moreMark: Modifier? = null) = Surface(
    shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth(),
    color = if (chosen) Color(0xFFE3E4E6) else MaterialTheme.colorScheme.surfaceVariant,
    contentColor = if (chosen) Color(0xFF1B1D1F) else MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            kind?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        if (kind != null) Box(Modifier.size(32.dp).then(moreMark ?: Modifier), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null) }
    }
}

@Composable private fun MiniButton(text: String, modifier: Modifier = Modifier, icon: Int? = null) =
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = modifier.widthIn(min = 160.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(painterResource(it), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

/** Files' island at the bottom of the screen. */
@Composable private fun Island(content: @Composable ColumnScope.() -> Unit) =
    Surface(shape = MaterialTheme.shapes.extraLarge, color = islandColor(), contentColor = islandContentColor()) { Column(horizontalAlignment = Alignment.CenterHorizontally, content = content) }

/** Sync's top line with nothing paired: the computer, "No computer yet", "Pair once…". */
@Composable private fun SyncHeader() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Surface(shape = CircleShape, color = islandColor(), contentColor = islandContentColor()) { Icon(painterResource(R.drawable.ic_computer), contentDescription = null, modifier = Modifier.padding(12.dp).size(22.dp)) }
    Column {
        Text(stringResource(R.string.sync_no_computer_yet_short), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.sync_pair_once), style = MaterialTheme.typography.bodyMedium)
    }
}

