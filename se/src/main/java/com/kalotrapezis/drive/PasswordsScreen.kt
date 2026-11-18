package com.kalotrapezis.drive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the purple of the user's icon (4 Nov: a padlock and its key, "more curves… no corners"; Assets/Icons/Applets/Passwords.png)
internal val PASSWORDS_COLOR = Color(0xFF7E37ED)
internal const val PASSWORDS_SETTINGS = "settings-passwords" // the sheet's Settings: Settings › Passwords (MainActivity)

private enum class PwPage { Passwords, Create, TwoFactor }
private enum class PwKind(val label: Int) { All(R.string.common_all), Logins(R.string.browser_passwords), Cards(R.string.browser_cards), Addresses(R.string.browser_addresses) }

/** One row of the Passwords list: what it is in the vault, by its place there. */
private sealed class PwItem(val index: Int) {
    class Login(i: Int, val l: SavedLogin) : PwItem(i)
    class Card(i: Int, val c: SavedCard) : PwItem(i)
    class Address(i: Int, val a: Map<String, String>) : PwItem(i)
    val title get() = when (this) { is Login -> l.host; is Card -> "•••• ${c.last4}"; is Address -> addressLabel(a) }
    val detail get() = when (this) { is Login -> listOf(l.user.ifEmpty { "—" }, l.note).filter(String::isNotBlank).joinToString(" · "); is Card -> listOf(c.name, "${c.month}/${c.year}").filter { it.isNotBlank() && it != "/" }.joinToString(" · "); is Address -> a.values.joinToString(", ") }
    fun matches(q: String) = q.isBlank() || when (this) {
        is Login -> listOf(l.host, l.user, l.note); is Card -> listOf(c.name, c.last4); is Address -> a.values.toList()
    }.any { it.contains(q.trim(), ignoreCase = true) }
}

internal fun Context.activity(): Activity? = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

/**
 * The Passwords applet: locked until the fingerprint or the screen lock opens it, every time it comes back (the user,
 * 4 Nov: "Lock every time"); [start] "<host>" finds that site's logins (the Browser's key), "2fa" opens the 2FA page.
 */
@Composable
internal fun PasswordsScreen(back: () -> Unit, start: String?) {
    val context = LocalContext.current
    var locked by remember { mutableStateOf(true) }
    var away by remember { mutableStateOf(false) } // a file being chosen: coming back from it is not leaving
    var said by remember { mutableStateOf<String?>(null) }
    val canLock = remember { context.getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS }
    fun unlock() {
        if (!canLock) { locked = false; return } // no screen lock on this device: nothing to ask (said on the lock screen once)
        val a = context.activity() ?: return
        authenticateVault(a, { locked = false; said = null }, { said = it }, context.getString(R.string.pw_unlock), context.getString(R.string.home_vault_unlock_detail))
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !away) locked = true
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START && locked) unlock()
        }
        lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    if (locked) {
        BackHandler(onBack = back)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(56.dp), tint = PASSWORDS_COLOR)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_passwords), style = MaterialTheme.typography.headlineSmall)
            said?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            if (!canLock) Text(stringResource(R.string.pw_no_lock), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = ::unlock, colors = ButtonDefaults.buttonColors(containerColor = PASSWORDS_COLOR, contentColor = Color.White)) {
                Icon(painterResource(R.drawable.ic_lock_open), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pw_unlock))
            }
        }
        return
    }
    PasswordsOpen(back, start, setAway = { away = it })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordsOpen(back: () -> Unit, start: String?, setAway: (Boolean) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    var page by remember { mutableStateOf(if (start == "2fa") PwPage.TwoFactor else PwPage.Passwords) }
    var vault by remember { mutableStateOf(PasswordStore.load(context)) }
    var codes by remember { mutableStateOf(AuthSecrets.load(context)) }
    // the other devices' merged in on opening, when sync is on
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { PasswordStore.keep(app, vault) }?.let { vault = it }
        withContext(Dispatchers.IO) { PasswordStore.keep2fa(app, codes) }?.let { codes = it }
    }
    fun keep(v: BrowserVault) { vault = v; scope.launch { withContext(Dispatchers.IO) { runCatching { PasswordStore.keep(app, v) }.onFailure { WriterLog.e("Passwords: not saved", it) }.getOrNull() }?.let { vault = it } } }
    fun keep2fa(e: List<OtpEntry>) { codes = e; scope.launch { withContext(Dispatchers.IO) { runCatching { PasswordStore.keep2fa(app, e) }.onFailure { WriterLog.e("2FA: not saved", it) }.getOrNull() }?.let { codes = it } } }

    var query by remember { mutableStateOf(start?.takeIf { it != "2fa" }.orEmpty()) }
    var kind by remember { mutableStateOf(PwKind.All) }
    var open by remember { mutableStateOf<PwItem?>(null) }
    var editing by remember { mutableStateOf<PwItem?>(null) }
    var newLogin by remember { mutableStateOf<SavedLogin?>(null) } // + New, or Create's Save: the form, prefilled
    var newOf by remember { mutableStateOf(PwKind.Logins) }
    var more by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf<OtpEntry?>(null) }
    var editingCode by remember { mutableStateOf<Int?>(null) }
    var report by remember { mutableStateOf<Pair<String, Uri?>?>(null) } // an import's result, and its file to offer deleting
    var differ by remember { mutableStateOf<List<SavedLogin>>(emptyList()) } // the CSV's logins with another password than the saved
    var generated by remember { mutableStateOf("") }

    fun addCodes(r: OtpUri.Read, file: Uri?, quiet: Boolean = false) {
        val (all, n) = AuthSecrets.merge(codes, r.entries)
        if (n > 0) keep2fa(all)
        WriterLog.i("2FA: added $n of ${r.entries.size}${if (r.skipped > 0) ", ${r.skipped} not time codes" else ""}")
        if (!quiet || n == 0) report = (context.getString(R.string.pw_codes_added, n, r.entries.size - n) + if (r.skipped > 0) "\n" + context.getString(R.string.pw_codes_skipped, r.skipped) else "") to file
    }
    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        setAway(false)
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
        val r = text?.let(FirefoxImport::logins)
        if (r == null || r.logins.isEmpty()) { report = context.getString(R.string.pw_import_nothing) to null; return@rememberLauncherForActivityResult }
        val have = vault.logins.associateBy { it.host to it.user }
        val fresh = r.logins.filter { (it.host to it.user) !in have }.distinctBy { it.host to it.user }
        differ = r.logins.filter { n -> have[n.host to n.user]?.let { it.password != n.password } == true }.distinctBy { it.host to it.user }
        if (fresh.isNotEmpty()) keep(vault.with(BrowserVault(logins = fresh)))
        WriterLog.i("Passwords: imported ${fresh.size} new logins, ${differ.size} differ")
        report = context.getString(R.string.pw_logins_added, fresh.size, r.logins.size - fresh.size) to uri
    }
    val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        setAway(false)
        if (uri == null) return@rememberLauncherForActivityResult
        val r = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()?.let(OtpUri::lines)
        if (r == null) report = context.getString(R.string.pw_import_nothing) to null else addCodes(r, uri)
    }
    fun copy(label: String, text: String, said: Int) { SecretClip.copy(context, label, text); Toast.makeText(context, said, Toast.LENGTH_SHORT).show() }

    if (scanning) {
        BackHandler { scanning = false }
        CodeScannerTab(back = { scanning = false }, takes = { it.startsWith("otpauth", true) }, hint = stringResource(R.string.pw_scan_hint), onCode = { raw ->
            OtpUri.parse(raw)?.let { scanning = false; addCodes(it, null); true } ?: false
        })
        return
    }

    val items = remember(vault) {
        vault.logins.mapIndexed { i, l -> PwItem.Login(i, l) }.sortedWith(compareBy({ !it.l.pinned }, { it.l.host })) + // ♥ first
            vault.cards.mapIndexed { i, c -> PwItem.Card(i, c) } + vault.addresses.mapIndexed { i, a -> PwItem.Address(i, a) }
    }
    val shown = items.filter { (kind == PwKind.All || (kind == PwKind.Logins) == (it is PwItem.Login) && (kind == PwKind.Cards) == (it is PwItem.Card) && (kind == PwKind.Addresses) == (it is PwItem.Address)) && it.matches(query) }
    // the item open, as the vault has it now (edited or deleted elsewhere: gone)
    val current = open?.let { o -> items.firstOrNull { it.index == o.index && it::class == o::class } }
    BackHandler(enabled = current != null && !wide) { open = null }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val top = PaddingValues(top = 72.dp, bottom = if (wide || page != PwPage.Create) 104.dp else 160.dp) // room for the island, and on a phone the actions over it
        when (page) {
            PwPage.Passwords, PwPage.Create -> Row(Modifier.fillMaxSize()) {
                val listShown = wide || current == null
                if (listShown) Column(if (wide) Modifier.width(400.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(top.calculateTopPadding()))
                    if (page == PwPage.Create) CreateTab(Modifier.weight(1f), set = { generated = it })
                    else {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
                            placeholder = { Text(stringResource(R.string.pw_search)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_clear)) } },
                            shape = CircleShape)
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PwKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(stringResource(k.label)) }) }
                        }
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = top.calculateBottomPadding())) {
                            if (items.isEmpty()) item { EmptyPasswords(import = { more = true }, add = { newOf = PwKind.Logins; newLogin = SavedLogin("", "", "") }) }
                            else if (shown.isEmpty()) item { Text(stringResource(R.string.pw_none_found), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(shown, key = { "${it::class.simpleName}${it.index}" }) { it ->
                                PwRow(it, chosen = wide && current?.let { c -> c.index == it.index && c::class == it::class } == true, click = { open = it },
                                    copy = (it as? PwItem.Login)?.let { l -> { copy(context.getString(R.string.pw_password), l.l.password, R.string.pw_password_copied) } },
                                    pin = (it as? PwItem.Login)?.let { l -> { keep(vault.copy(logins = vault.logins.mapIndexed { j, x -> if (j == l.index) x.copy(pinned = !x.pinned) else x })) } })
                            }
                        }
                    }
                }
                if (wide || current != null) Box(Modifier.weight(1f).fillMaxHeight().padding(top = top.calculateTopPadding())) {
                    if (current != null) PwDetail(current, close = { open = null }, closeable = !wide, copy = ::copy, edit = { editing = current }, delete = {
                        open = null
                        keep(when (current) {
                            is PwItem.Login -> vault.copy(logins = vault.logins.filterIndexed { i, _ -> i != current.index })
                            is PwItem.Card -> vault.copy(cards = vault.cards.filterIndexed { i, _ -> i != current.index })
                            is PwItem.Address -> vault.copy(addresses = vault.addresses.filterIndexed { i, _ -> i != current.index })
                        })
                    })
                    else if (page == PwPage.Passwords && items.isNotEmpty()) Text(stringResource(R.string.pw_choose_one), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PwPage.TwoFactor -> TwoFactorPage(codes, top, wide, copy = { copy(context.getString(R.string.pw_code), it, R.string.pw_code_copied) },
                edit = { editingCode = it }, delete = { i -> keep2fa(codes.filterIndexed { j, _ -> j != i }) },
                pin = { i -> keep2fa(codes.mapIndexed { j, e -> if (j == i) e.copy(pinned = !e.pinned) else e }) }, scan = { scanning = true }, type = { typing = OtpEntry("", "", "") })
        }
        // top: back and the page's name (as the other applets)
        Row(Modifier.align(Alignment.TopStart).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = { if (current != null && !wide) open = null else back() }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            }
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                Text(stringResource(if (page == PwPage.TwoFactor) R.string.pw_2fa else R.string.app_passwords), Modifier.padding(horizontal = 18.dp, vertical = 13.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
        // the island holds the three tabs (the user, 4 Nov: "the tabs of the app make them like the contacts app"); the page's
        // actions sit over it in the middle with their words, as the Clock's ("place them above the island on the page at the
        // center like the clock add words as well"); beside it on a tablet. A phone's open item has its own buttons, no island.
        if (wide || current == null) {
            val tabs = @Composable {
                IslandBottomBar(expand = { more = true }, visible = !more) {
                    listOf(Triple(PwPage.Passwords, R.string.browser_passwords, R.drawable.ic_key), Triple(PwPage.Create, R.string.pw_create, R.drawable.ic_add),
                        Triple(PwPage.TwoFactor, R.string.pw_2fa, R.drawable.ic_shield)).forEach { (p, label, icon) ->
                        IslandNavigationItem(stringResource(label), page == p, icon) { page = p; open = null }
                    }
                }
            }
            val actions = @Composable {
                if (!more) when (page) {
                    PwPage.Passwords -> Unit // its own row, below
                    PwPage.Create -> {
                        ActionPill(R.drawable.ic_copy, stringResource(R.string.common_copy)) { copy(context.getString(R.string.pw_password), generated, R.string.pw_password_copied) }
                        ActionPill(R.drawable.ic_save, stringResource(R.string.pw_save_login)) { newOf = PwKind.Logins; newLogin = SavedLogin("", "", generated) }
                    }
                    PwPage.TwoFactor -> Unit // its own row, below
                }
            }
            // the passwords' list as Contacts' (the user, 4 Nov: "the import goes to the bottom row the plus goes to the right corner
            // with no text the island to the left"): the island left, Import and + round at the right, nothing over it
            if (page == PwPage.Passwords) Row(Modifier.align(Alignment.BottomStart).then(if (wide) Modifier.width(400.dp) else Modifier.fillMaxWidth())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                tabs()
                if (!more) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IslandAction(R.drawable.ic_file_download, stringResource(R.string.common_import)) { more = true }
                    IslandAction(R.drawable.ic_add, stringResource(R.string.common_new)) { newOf = if (kind == PwKind.Cards || kind == PwKind.Addresses) kind else PwKind.Logins; newLogin = SavedLogin("", "", "") }
                }
            }
            // 2FA likewise (the user: "the island to the left and a plus to the right that will pop two pills scan and insert key")
            else if (page == PwPage.TwoFactor) {
                var adding by remember { mutableStateOf(false) }
                BackHandler(enabled = adding) { adding = false }
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (adding && !more) {
                        ActionPill(R.drawable.ic_qr_code, stringResource(R.string.pw_scan)) { adding = false; scanning = true }
                        ActionPill(R.drawable.ic_key, stringResource(R.string.pw_type_key)) { adding = false; typing = OtpEntry("", "", "") }
                    }
                    if (!more) IslandAction(if (adding) R.drawable.ic_close else R.drawable.ic_add, stringResource(if (adding) R.string.common_close else R.string.common_add)) { adding = !adding }
                }
                Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp)) { tabs() }
            }
            else if (wide) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { tabs(); actions() }
            else {
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp, start = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { tabs() }
            }
        }
    }

    if (more) ModalBottomSheet(onDismissRequest = { more = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.common_import), style = MaterialTheme.typography.titleLarge)
            @Composable fun choice(icon: Int, title: Int, how: Int, go: () -> Unit) = Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { more = false; go() }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, tint = PASSWORDS_COLOR)
                Column { Text(stringResource(title), style = MaterialTheme.typography.bodyLarge); Text(stringResource(how), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)) }
            }
            choice(R.drawable.ic_key, R.string.pw_import_google_csv, R.string.pw_import_google_csv_how) { setAway(true); pickCsv.launch(arrayOf("text/*", "application/octet-stream")) }
            choice(R.drawable.ic_qr_code, R.string.pw_import_google_auth, R.string.pw_import_google_auth_how) { page = PwPage.TwoFactor; scanning = true }
            choice(R.drawable.ic_file_text, R.string.pw_import_text, R.string.pw_import_text_how) { setAway(true); pickText.launch(arrayOf("text/*", "application/octet-stream")) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.15f))
            // autofill and sync are Settings › Passwords' (the user, 4 Nov: "this kind of thing should go to the settings page")
            choice(R.drawable.ic_settings, R.string.common_settings, R.string.pw_settings_summary) {
                context.startActivity(android.content.Intent(context, MainActivity::class.java).putExtra(Applet.EXTRA, PASSWORDS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
    }

    report?.let { (text, file) ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text(stringResource(R.string.common_import)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(text); if (file != null) Text(stringResource(R.string.pw_delete_export_note), color = MaterialTheme.colorScheme.error) } },
            confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { if (file != null) TextButton(onClick = {
                val gone = runCatching { DocumentsContract.deleteDocument(context.contentResolver, file) }.getOrDefault(false)
                WriterLog.i("Passwords: the export ${if (gone) "deleted" else "not deleted"}")
                Toast.makeText(context, if (gone) R.string.pw_export_deleted else R.string.pw_export_not_deleted, Toast.LENGTH_LONG).show()
                report = null
            }) { Text(stringResource(R.string.browser_delete_file)) } })
    }
    if (report == null && differ.isNotEmpty()) AlertDialog(onDismissRequest = { differ = emptyList() },
        title = { Text(stringResource(R.string.pw_differ_title)) }, text = { Text(stringResource(R.string.pw_differ, differ.size)) },
        confirmButton = { TextButton(onClick = { differ = emptyList() }) { Text(stringResource(R.string.pw_keep_mine)) } },
        dismissButton = { TextButton(onClick = { keep(vault.with(BrowserVault(logins = differ))); differ = emptyList() }) { Text(stringResource(R.string.pw_take_file)) } })

    // the forms: a new one (of the chosen kind) or the one being edited
    val form = editing
    if (newLogin != null || form != null) {
        val close = { newLogin = null; editing = null }
        when {
            form is PwItem.Login || (form == null && newOf == PwKind.Logins) -> LoginForm((form as? PwItem.Login)?.l ?: newLogin!!, close) { l ->
                val rest = if (form is PwItem.Login) vault.logins.filterIndexed { i, _ -> i != form.index } else vault.logins
                keep(vault.copy(logins = (rest.filter { it.host != l.host || it.user != l.user } + l)))
                open = null; close(); if (page == PwPage.Create) page = PwPage.Passwords
            }
            form is PwItem.Card || (form == null && newOf == PwKind.Cards) -> CardForm((form as? PwItem.Card)?.c ?: SavedCard("", "", "", ""), close) { c ->
                val rest = if (form is PwItem.Card) vault.cards.filterIndexed { i, _ -> i != form.index } else vault.cards
                keep(vault.copy(cards = rest.filter { it.number != c.number } + c)); open = null; close()
            }
            else -> AddressForm((form as? PwItem.Address)?.a.orEmpty(), close) { a ->
                val rest = if (form is PwItem.Address) vault.addresses.filterIndexed { i, _ -> i != form.index } else vault.addresses
                keep(vault.copy(addresses = (rest + a).distinct())); open = null; close()
            }
        }
    }
    typing?.let { e -> CodeForm(e, isNew = true, close = { typing = null }) { got -> addCodes(OtpUri.Read(listOf(got)), null, quiet = true); typing = null } }
    editingCode?.let { i -> codes.getOrNull(i)?.let { e -> CodeForm(e, isNew = false, close = { editingCode = null }) { got -> keep2fa(codes.toMutableList().also { it[i] = got }); editingCode = null } } }
}

@Composable private fun IconWord(icon: Int, word: Int) { Icon(painterResource(icon), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(word)) }

/** A page's action over the island, its word beside its icon (the Clock's round ones, with words: two or three of them). */
@Composable private fun ActionPill(icon: Int, label: String, click: () -> Unit) =
    Surface(onClick = click, color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
        Row(Modifier.height(52.dp).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(20.dp)); Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }

@Composable private fun Avatar(text: String) = Box(Modifier.size(40.dp).clip(CircleShape).background(PASSWORDS_COLOR.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
    Text(text.trim().let { if (it.isEmpty()) "•" else String(Character.toChars(it.codePointAt(0))).uppercase() }, fontWeight = FontWeight.Bold, color = PASSWORDS_COLOR)
}

@Composable private fun PwRow(item: PwItem, chosen: Boolean, click: () -> Unit, copy: (() -> Unit)?, pin: (() -> Unit)?) =
    Row(Modifier.fillMaxWidth().background(if (chosen) PASSWORDS_COLOR.copy(alpha = 0.15f) else Color.Transparent).clickable(onClick = click).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(when (item) { is PwItem.Login -> item.l.host.removePrefix("www."); is PwItem.Card -> "💳"; is PwItem.Address -> item.title })
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(item.detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pin != null) (item as? PwItem.Login)?.l?.pinned?.let { on -> IconButton(onClick = pin) {
            Icon(painterResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border), stringResource(R.string.pw_pin), tint = if (on) PASSWORDS_COLOR else LocalContentColor.current)
        } }
        if (copy != null) IconButton(onClick = copy) { Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.pw_copy_password)) }
    }

@Composable private fun EmptyPasswords(import: () -> Unit, add: () -> Unit) =
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_key), null, Modifier.size(48.dp), tint = PASSWORDS_COLOR)
        Text(stringResource(R.string.pw_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = import, colors = ButtonDefaults.buttonColors(containerColor = PASSWORDS_COLOR, contentColor = Color.White)) { IconWord(R.drawable.ic_file_download, R.string.pw_import_google_csv) }
        OutlinedButton(onClick = add) { IconWord(R.drawable.ic_add, R.string.pw_add_login) }
    }

/** A field of the detail: its name, its value (hidden until 👁 when secret), and copy. */
@Composable private fun Field(label: String, value: String, secret: Boolean = false, copy: (() -> Unit)? = null) {
    if (value.isEmpty()) return
    var shown by remember { mutableStateOf(!secret) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = PASSWORDS_COLOR)
            Text(if (shown) value else "•".repeat(minOf(12, value.length)), style = MaterialTheme.typography.bodyLarge, fontFamily = if (secret) FontFamily.Monospace else null)
        }
        if (secret) IconButton(onClick = { shown = !shown }) { Icon(painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility), stringResource(if (shown) R.string.common_hide else R.string.common_show)) }
        if (copy != null) IconButton(onClick = copy) { Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.common_copy)) }
    }
}

@Composable
private fun PwDetail(item: PwItem, close: () -> Unit, closeable: Boolean, copy: (String, String, Int) -> Unit, edit: () -> Unit, delete: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    val password = stringResource(R.string.pw_password); val user = stringResource(R.string.pw_user)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 104.dp).widthIn(max = 640.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(item.title.removePrefix("www."))
            Text(item.title, Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (closeable) IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
        }
        Spacer(Modifier.height(12.dp))
        when (item) {
            is PwItem.Login -> {
                Field(stringResource(R.string.pw_site), item.l.host)
                Field(user, item.l.user) { copy(user, item.l.user, R.string.pw_user_copied) }
                Field(password, item.l.password, secret = true) { copy(password, item.l.password, R.string.pw_password_copied) }
                Field(stringResource(R.string.pw_note), item.l.note)
            }
            is PwItem.Card -> {
                Field(stringResource(R.string.pw_card_name), item.c.name)
                Field(stringResource(R.string.pw_card_number), item.c.number, secret = true) { copy(item.title, item.c.number, R.string.pw_number_copied) }
                Field(stringResource(R.string.pw_card_expiry), "${item.c.month}/${item.c.year}".takeIf { it != "/" }.orEmpty())
            }
            is PwItem.Address -> item.a.forEach { (k, v) -> Field(addressField(k), v) { copy(k, v, R.string.pw_copied) } }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = edit) { Icon(painterResource(R.drawable.ic_edit), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.common_edit)) }
            OutlinedButton(onClick = { asking = true }) { Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.common_delete)) }
        }
    }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text(stringResource(R.string.pw_delete_title, item.title)) },
        text = { Text(stringResource(R.string.pw_delete_note)) },
        confirmButton = { TextButton(onClick = { asking = false; delete() }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.common_cancel)) } })
}

// the address fields the Browser fills, by the names pages give them (BrowserVault.ADDRESS); then a note of the user's own,
// never filled (the user, 10 Nov: "I didn't see a notes field or a state field")
private val ADDRESS_FIELDS = listOf("name", "email", "tel", "organization", "street-address", "postal-code", "address-level2", "address-level1", "country-name", "note")
@Composable private fun addressField(k: String) = stringResource(when (k) {
    "name", "given-name", "family-name", "additional-name" -> R.string.common_name; "email" -> R.string.pw_email; "tel" -> R.string.pw_phone
    "organization" -> R.string.pw_organization; "street-address", "address-line1", "address-line2" -> R.string.pw_street; "postal-code" -> R.string.pw_postcode
    "address-level2" -> R.string.pw_city; "address-level1" -> R.string.pw_region; "note" -> R.string.pw_note; else -> R.string.pw_country
})

/** A form in a dialog with ✓ (Save) and × (Cancel), as the house's many-fields rule. */
@Composable private fun FormDialog(title: String, ok: Boolean, close: () -> Unit, save: () -> Unit, content: @Composable () -> Unit) =
    AlertDialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { TextButton(onClick = save, enabled = ok) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } })

@Composable private fun LoginForm(l: SavedLogin, close: () -> Unit, save: (SavedLogin) -> Unit) {
    var host by remember { mutableStateOf(l.host) }; var user by remember { mutableStateOf(l.user) }
    var pass by remember { mutableStateOf(l.password) }; var note by remember { mutableStateOf(l.note) }
    var shown by remember { mutableStateOf(false) }
    val site = BrowserRules.host(if ("://" in host) host else "https://$host").ifEmpty { host.trim().lowercase() }
    FormDialog(stringResource(if (l.host.isEmpty()) R.string.pw_add_login else R.string.common_edit), site.isNotEmpty() && pass.isNotEmpty(), close,
        { save(SavedLogin(site, user.trim(), pass, note.trim())) }) {
        OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.pw_site)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.pw_user)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.pw_password)) }, singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { Row {
                IconButton(onClick = { pass = PasswordGen.password(PasswordGen.Options()); shown = true }) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.pw_generate)) }
                IconButton(onClick = { shown = !shown }) { Icon(painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility), stringResource(if (shown) R.string.common_hide else R.string.common_show)) }
            } })
        OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.pw_note)) }, minLines = 2)
    }
}

@Composable private fun CardForm(c: SavedCard, close: () -> Unit, save: (SavedCard) -> Unit) {
    var name by remember { mutableStateOf(c.name) }; var number by remember { mutableStateOf(c.number) }
    var month by remember { mutableStateOf(c.month) }; var year by remember { mutableStateOf(c.year) }
    val digits = number.filter(Char::isDigit)
    FormDialog(stringResource(if (c.number.isEmpty()) R.string.pw_add_card else R.string.common_edit), digits.length in 12..19, close,
        { save(SavedCard(name.trim(), digits, month.filter(Char::isDigit).padStart(2, '0').takeLast(2).takeIf { month.isNotBlank() }.orEmpty(), year.filter(Char::isDigit), c.cvc)) }) { // the code kept on the computer stays
        Text(stringResource(R.string.pw_card_no_cvc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.pw_card_name)) }, singleLine = true)
        OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.pw_card_number)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(month, { month = it.take(2) }, Modifier.weight(1f), label = { Text(stringResource(R.string.pw_month)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(year, { year = it.take(4) }, Modifier.weight(1f), label = { Text(stringResource(R.string.pw_year)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }
}

@Composable private fun AddressForm(a: Map<String, String>, close: () -> Unit, save: (Map<String, String>) -> Unit) {
    // the fields it has, then the usual ones it lacks
    val keys = remember { (a.keys + ADDRESS_FIELDS).distinct() }
    val values = remember { keys.associateWith { mutableStateOf(a[it].orEmpty()) } }
    FormDialog(stringResource(if (a.isEmpty()) R.string.pw_add_address else R.string.common_edit), values.values.count { it.value.isNotBlank() } >= 1, close,
        { save(values.mapValues { it.value.value.trim() }.filterValues { it.isNotEmpty() }) }) {
        keys.forEach { k -> val v = values.getValue(k); OutlinedTextField(v.value, { v.value = it }, label = { Text(addressField(k)) }, singleLine = k != "street-address" && k != "note") }
    }
}

/** Create a password: random characters or EFF's words, its strength in bits; Copy and Save are on the island. */
@Composable private fun CreateTab(modifier: Modifier, set: (String) -> Unit) {
    val context = LocalContext.current
    val words = remember { PasswordGen.words(context) }
    var phrase by remember { mutableStateOf(false) }
    var o by remember { mutableStateOf(PasswordGen.Options()) }
    var count by remember { mutableIntStateOf(5) }
    var again by remember { mutableIntStateOf(0) }
    val made = remember(phrase, o, count, again) { if (phrase && words.isNotEmpty()) PasswordGen.passphrase(words, count) else PasswordGen.password(o) }
    LaunchedEffect(made) { set(made) }
    val bits = if (phrase && words.isNotEmpty()) PasswordGen.passphraseBits(words, count) else o.bits
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 170.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.large) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(made, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                IconButton(onClick = { again++ }) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.pw_generate)) }
            }
        }
        val strong = bits >= 80; val good = bits >= 60
        LinearProgressIndicator(progress = { (bits / 128).toFloat().coerceIn(0.05f, 1f) }, Modifier.fillMaxWidth(),
            color = if (strong) Color(0xFF2E9E5B) else if (good) PASSWORDS_COLOR else MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.pw_bits, bits.toInt(), stringResource(if (strong) R.string.pw_strong else if (good) R.string.pw_good else R.string.pw_weak)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (words.isNotEmpty()) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to R.string.pw_random, true to R.string.pw_passphrase).forEachIndexed { i, (p, label) ->
                SegmentedButton(selected = phrase == p, onClick = { phrase = p }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
            }
        }
        if (phrase && words.isNotEmpty()) {
            Text(stringResource(R.string.pw_words, count))
            Slider(count.toFloat(), { count = it.toInt() }, valueRange = 3f..10f, steps = 6)
            Text(stringResource(R.string.pw_eff_credit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.pw_length, o.length))
            Slider(o.length.toFloat(), { o = o.copy(length = it.toInt()) }, valueRange = 8f..64f)
            @Composable fun check(label: String, on: Boolean, set: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, set); Text(label)
            }
            check("A–Z", o.upper) { o = o.copy(upper = it) }
            check("a–z", o.lower) { o = o.copy(lower = it) }
            check("0–9", o.digits) { o = o.copy(digits = it) }
            check("!@#$…", o.symbols) { o = o.copy(symbols = it) }
            check(stringResource(R.string.pw_no_similar), o.noSimilar) { o = o.copy(noSimilar = it) }
        }
    }
}

/** 2FA: each code with the seconds left as a ring, the next code shown in its last 5 s; a tap copies, a long press edits. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TwoFactorPage(codes: List<OtpEntry>, top: PaddingValues, wide: Boolean, copy: (String) -> Unit, edit: (Int) -> Unit, delete: (Int) -> Unit, pin: (Int) -> Unit, scan: () -> Unit, type: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000 - now % 1000) } }
    var query by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<Int?>(null) }
    val shown = codes.withIndex().filter { (_, e) -> query.isBlank() || "${e.issuer} ${e.account}".contains(query.trim(), true) }.sortedBy { !it.value.pinned }
    Column(Modifier.fillMaxSize().padding(top = top.calculateTopPadding()), horizontalAlignment = Alignment.CenterHorizontally) {
        if (codes.isNotEmpty()) OutlinedTextField(query, { query = it }, Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
            placeholder = { Text(stringResource(R.string.pw_search_codes)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_clear)) } }, shape = CircleShape)
        if (codes.isNotEmpty() && shown.isEmpty()) Text(stringResource(R.string.pw_none_found), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (codes.isEmpty()) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(48.dp), tint = PASSWORDS_COLOR)
            Text(stringResource(R.string.pw_codes_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = scan, colors = ButtonDefaults.buttonColors(containerColor = PASSWORDS_COLOR, contentColor = Color.White)) { IconWord(R.drawable.ic_qr_code, R.string.pw_scan) }
            OutlinedButton(onClick = type) { IconWord(R.drawable.ic_key, R.string.pw_type_key) }
        }
        LazyColumn(Modifier.widthIn(max = if (wide) 900.dp else 640.dp).fillMaxWidth(), contentPadding = PaddingValues(bottom = top.calculateBottomPadding())) {
            items(shown, key = { "${it.index}|${it.value.key}" }) { (i, e) -> // an edit may make two alike: the place keeps keys apart
                var menu by remember { mutableStateOf(false) }
                val code = Totp.code(e, now); val left = Totp.left(e, now)
                Box {
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { copy(code) }, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(e.issuer.ifEmpty { e.account })
                        Column(Modifier.weight(1f)) {
                            Text(e.issuer.ifEmpty { e.account.ifEmpty { "—" } }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (e.issuer.isNotEmpty() && e.account.isNotEmpty()) Text(e.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (code.isEmpty()) "—" else Totp.grouped(code), fontFamily = FontFamily.Monospace, fontSize = 28.sp, color = if (left <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            if (left <= 5 && code.isNotEmpty()) Text(stringResource(R.string.pw_next_code, Totp.grouped(Totp.code(e, now + left * 1000L))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { pin(i) }) {
                            Icon(painterResource(if (e.pinned) R.drawable.ic_favorite else R.drawable.ic_favorite_border), stringResource(R.string.pw_pin), tint = if (e.pinned) PASSWORDS_COLOR else LocalContentColor.current)
                        }
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { left / e.period.toFloat() }, Modifier.size(34.dp), color = if (left <= 5) MaterialTheme.colorScheme.error else PASSWORDS_COLOR, strokeWidth = 3.dp)
                            Text("$left", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.common_copy)) }, { menu = false; copy(code) }, leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) })
                        DropdownMenuItem({ Text(stringResource(R.string.common_edit)) }, { menu = false; edit(i) }, leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) })
                        DropdownMenuItem({ Text(stringResource(R.string.common_delete)) }, { menu = false; asking = i }, leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) })
                    }
                }
            }
        }
    }
    asking?.let { i -> codes.getOrNull(i)?.let { e ->
        AlertDialog(onDismissRequest = { asking = null }, title = { Text(stringResource(R.string.pw_delete_title, e.issuer.ifEmpty { e.account })) },
            text = { Text(stringResource(R.string.pw_delete_code_note)) },
            confirmButton = { TextButton(onClick = { asking = null; delete(i) }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.common_cancel)) } })
    } }
}

/** A 2FA account typed (its key, or an otpauth:// link pasted), or edited: the name always; the key and its settings when new. */
@Composable private fun CodeForm(e: OtpEntry, isNew: Boolean, close: () -> Unit, save: (OtpEntry) -> Unit) {
    var issuer by remember { mutableStateOf(e.issuer) }; var account by remember { mutableStateOf(e.account) }
    var secret by remember { mutableStateOf(e.secret) }
    var digits by remember { mutableIntStateOf(e.digits) }; var period by remember { mutableStateOf(e.period.toString()) }
    var algorithm by remember { mutableStateOf(e.algorithm) }
    var advanced by remember { mutableStateOf(false) }
    val link = OtpUri.totp(secret.trim()) // a pasted link: all of it from there
    val ok = link != null || (Totp.base32(secret) != null && (issuer.isNotBlank() || account.isNotBlank()) && (period.toIntOrNull() ?: 0) in 1..300)
    FormDialog(stringResource(if (isNew) R.string.pw_type_key else R.string.common_edit), ok, close, {
        save(link ?: OtpEntry(issuer.trim(), account.trim(), Totp.clean(secret), algorithm, digits, period.toInt()))
    }) {
        OutlinedTextField(issuer, { issuer = it }, label = { Text(stringResource(R.string.pw_issuer)) }, singleLine = true)
        OutlinedTextField(account, { account = it }, label = { Text(stringResource(R.string.pw_account)) }, singleLine = true)
        if (isNew) {
            OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.pw_key)) }, supportingText = { Text(stringResource(R.string.pw_key_hint)) },
                isError = secret.isNotBlank() && link == null && Totp.base32(secret) == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.pw_advanced)) }
            if (advanced) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 8).forEach { d -> FilterChip(digits == d, { digits = d }, { Text(stringResource(R.string.pw_digits, d)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Totp.ALGORITHMS.forEach { a -> FilterChip(algorithm == a, { algorithm = a }, { Text(a) }) } }
                OutlinedTextField(period, { period = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.pw_period)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
    }
}
