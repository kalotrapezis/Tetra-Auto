package com.kalotrapezis.drive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/** An extension's icon, or the puzzle piece until Gecko has drawn it. */
@Composable internal fun ExtIcon(id: String, size: Int = 28) {
    val icon = BrowserEngine.icons[id]
    if (icon != null) Image(icon, null, Modifier.size(size.dp).clip(MaterialTheme.shapes.extraSmall))
    else Icon(painterResource(R.drawable.ic_extension), null, Modifier.size(size.dp), tint = BROWSER_COLOR)
}

/**
 * The puzzle piece (the user, 3 Nov: "a puzzle piece as an extension manager as the other browsers do"): every extension
 * with its switch; a tap presses its button (its popup), › slides to its own page — Options, Pin to the toolbar, Allow in
 * Private, Remove — with ‹ back (Office's strips). "Get extensions" opens addons.mozilla.org in a tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionsSheet(pinned: List<String>, private: Boolean, close: () -> Unit, pin: (String) -> Unit, press: (WebExtension) -> Unit, openTab: (String) -> Unit) =
    BrowserSheet(onDismissRequest = close) {
        var chosen by remember { mutableStateOf<String?>(null) }
        val ext = chosen?.let { id -> BrowserEngine.extensions.firstOrNull { it.id == id } }
        androidx.activity.compose.BackHandler(ext != null) { chosen = null }
        AnimatedContent(ext, label = "extensions", transitionSpec = {
            if (targetState != null) slideInHorizontally { it } togetherWith slideOutHorizontally { -it } else slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
        }) { e ->
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                if (e == null) {
                    Text(stringResource(R.string.browser_extensions), Modifier.padding(start = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
                    if (BrowserEngine.extensions.isEmpty()) Text(stringResource(R.string.browser_ext_none), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BrowserEngine.extensions.forEach { x ->
                        val on = x.metaData.enabled
                        val usable = on && (!private || x.metaData.allowedInPrivateBrowsing)
                        Row(Modifier.fillMaxWidth().clickable { if (usable) { close(); press(x) } else chosen = x.id }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ExtIcon(x.id)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(x.metaData.name ?: x.id, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (usable) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                                val note = when { !on -> null; private && !x.metaData.allowedInPrivateBrowsing -> stringResource(R.string.browser_ext_not_private); "ext:${x.id}" in pinned -> stringResource(R.string.browser_ext_pinned); else -> null }
                                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(on, { BrowserEngine.setEnabled(x, it) })
                            IconButton(onClick = { chosen = x.id }) { Icon(painterResource(R.drawable.ic_chevron_right), x.metaData.name) }
                        }
                    }
                    TextButton(onClick = { close(); openTab(BrowserEngine.GET_MORE) }, Modifier.padding(start = 12.dp, top = 8.dp)) {
                        Icon(painterResource(R.drawable.ic_add), null, Modifier.padding(end = 8.dp).size(20.dp)); Text(stringResource(R.string.browser_ext_get))
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { chosen = null }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
                        ExtIcon(e.id)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(e.metaData.name ?: e.id, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(e.metaData.version, e.metaData.creatorName).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    e.metaData.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    @Composable fun row(label: String, icon: Int, tint: Color = Color.Unspecified, end: @Composable () -> Unit = {}, act: () -> Unit) =
                        Row(Modifier.fillMaxWidth().clickable(onClick = act).padding(start = 20.dp, end = 12.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
                            Text(label, Modifier.weight(1f).padding(start = 16.dp), color = tint)
                            end()
                        }
                    e.metaData.optionsPageUrl?.let { u -> row(stringResource(R.string.browser_ext_options), R.drawable.ic_settings) { close(); openTab(u) } }
                    val id = "ext:${e.id}"
                    row(stringResource(if (id in pinned) R.string.browser_ext_unpin else R.string.browser_ext_pin), R.drawable.ic_push_pin) { pin(id) }
                    row(stringResource(R.string.browser_ext_private), R.drawable.ic_visibility_off, end = { Switch(e.metaData.allowedInPrivateBrowsing, { BrowserEngine.setPrivate(e, it) }) }) {
                        BrowserEngine.setPrivate(e, !e.metaData.allowedInPrivateBrowsing)
                    }
                    e.metaData.amoListingUrl?.let { u -> row(stringResource(R.string.browser_ext_page), R.drawable.ic_info) { close(); openTab(u) } }
                    row(stringResource(R.string.browser_ext_remove), R.drawable.ic_delete, MaterialTheme.colorScheme.error) { chosen = null; BrowserEngine.remove(e) }
                }
            }
        }
    }

/** Adding an extension (from addons.mozilla.org's page or a link), or an update asking for more: what it may do, and Private's switch. */
@Composable
internal fun ExtAskDialog(a: ExtAsk) {
    var private by remember(a) { mutableStateOf(false) }
    val name = a.ext.metaData.name ?: a.ext.id
    LaunchedEffect(a) { if (a.ext.id !in BrowserEngine.icons) a.ext.metaData.icon.getBitmap(96).accept({ b -> b?.let { BrowserEngine.icons[a.ext.id] = it.asImageBitmap() } }, { }) }
    // every site said once, not each of the sites it also names (Privacy Badger's hundred Google domains)
    val all = a.permissions.any { it == "<all_urls>" || it == "*://*/*" }
    val sites = a.permissions.filter { "://" in it || it == "<all_urls>" }
    val shown = a.permissions.filter { "://" !in it && it != "<all_urls>" } + if (all) listOf("<all_urls>") else sites.take(5) + listOfNotNull("…".takeIf { sites.size > 5 })
    TetraAlertDialog(onDismissRequest = { a.answer(false, false) },
        icon = { ExtIcon(a.ext.id, 40) },
        title = { Text(stringResource(if (a.update) R.string.browser_ext_update_title else R.string.browser_ext_add_title, name)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (a.permissions.isNotEmpty()) {
                Text(stringResource(R.string.browser_ext_can), style = MaterialTheme.typography.titleSmall)
                shown.forEach { Text("• " + permission(it), style = MaterialTheme.typography.bodyMedium) }
            }
            if (!a.update) Row(Modifier.fillMaxWidth().clickable { private = !private }.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(private, { private = it }); Text(stringResource(R.string.browser_ext_private))
            }
        } },
        confirmButton = { TextButton(onClick = { a.answer(true, private) }) { Text(stringResource(if (a.update) R.string.browser_ext_allow else R.string.common_add)) } },
        dismissButton = { TextButton(onClick = { a.answer(false, false) }) { Text(stringResource(R.string.common_cancel)) } })
}

/** An extension's popup (uBlock's panel): its own page in a sheet from the bottom, closed by a tap outside or by itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtPopupSheet(p: Pair<WebExtension, org.mozilla.geckoview.GeckoSession>) =
    BrowserSheet(onDismissRequest = { BrowserEngine.closePopup() }, full = true) {
        Row(Modifier.padding(start = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ExtIcon(p.first.id, 20); Text(p.first.metaData.name ?: "", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
        }
        // shortcut: a fixed height, not the popup's own; measure its document if a popup shows cut or too much empty room
        AndroidView({ c -> GeckoView(c).apply { setSession(p.second) } }, Modifier.fillMaxWidth().height(520.dp).navigationBarsPadding(), onRelease = { it.releaseSession() })
    }

/** A permission as Firefox names it; every site (the one that matters most) said in words. */
// shortcut: the other permissions show Firefox's own names (tabs, storage…); give them sentences in every language if the user asks
@Composable private fun permission(p: String) = if (p == "<all_urls>" || p == "*://*/*") stringResource(R.string.browser_ext_all_sites) else p
