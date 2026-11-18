package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Buttons on the dark panels: Maps' blue in both looks (the light look's dark primary was lost on the dark glass). */
@Composable internal fun panelButton() = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0), contentColor = Color.White,
    disabledContainerColor = Color(0xFF5C6BC0).copy(alpha = 0.35f), disabledContentColor = Color.White.copy(alpha = 0.55f))

/** The bottom island's three (the user, 21 Oct: "an Island at the bottom with navigation, favorites, search"). */
internal enum class MapsTab(@androidx.annotation.StringRes val label: Int, val icon: Int) {
    Navigate(R.string.maps_tab_directions, R.drawable.ic_navigation), Favorites(R.string.maps_tab_saved, R.drawable.ic_favorite), Search(R.string.maps_tab_search, R.drawable.ic_search)
}

/** Icons, the open one named (the user, 2 Nov: "a proper island, not words in every button"); ♪ at the end plays Maps' station. */
@Composable internal fun MapsIsland(tab: MapsTab?, music: Boolean, playMusic: () -> Unit, pick: (MapsTab) -> Unit) = Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
    Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MapsTab.entries.forEach { t ->
            val on = t == tab
            Surface(shape = CircleShape, color = if (on) Color.White.copy(alpha = 0.18f) else Color.Transparent, modifier = Modifier.clickable { pick(t) }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(t.icon), contentDescription = stringResource(t.label), modifier = Modifier.size(22.dp))
                    if (on) Text(stringResource(t.label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Surface(shape = CircleShape, color = if (music) Color.White.copy(alpha = 0.18f) else Color.Transparent, modifier = Modifier.clickable(onClick = playMusic)) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Icon(painterResource(R.drawable.ic_headphones), contentDescription = stringResource(R.string.maps_music), modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The panels above the island: dark glass like the other islands, at most half the screen. */
@Composable internal fun MapsPanel(content: @Composable () -> Unit) = Surface(color = islandColor(), contentColor = islandContentColor(),
    shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) { Column(Modifier.padding(vertical = 12.dp)) { content() } }

@Composable private fun chipColors() = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Color.White.copy(alpha = 0.22f),
    selectedLabelColor = Color.White, iconColor = Color.White, selectedLeadingIconColor = Color.White)

@Composable internal fun MapsSearchField(query: String, hint: String, change: (String) -> Unit, done: (() -> Unit)? = null) = OutlinedTextField(query, change, singleLine = true,
    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { done?.invoke() }),
    placeholder = { Text(hint) }, leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { change("") }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear)) } else DictateButton({ change(it.trim()) }) },
    colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent, focusedPlaceholderColor = Color.White.copy(alpha = 0.6f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
        focusedLeadingIconColor = Color.White, unfocusedLeadingIconColor = Color.White, focusedTrailingIconColor = Color.White, unfocusedTrailingIconColor = Color.White),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))

/** Search: words, or a pill of a kind, nearest the map's middle first (the area or town on the screen). */
@Composable internal fun SearchPanel(query: String, setQuery: (String) -> Unit, pill: String?, setPill: (String?) -> Unit, results: List<Poi>,
    distance: (Poi) -> Double?, hasList: Boolean, near: String?, clearNear: () -> Unit, loading: Boolean, ok: () -> Unit, open: (Poi) -> Unit) = MapsPanel {
    near?.let { n -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(true, clearNear, label = { Text(stringResource(R.string.maps_near, n), maxLines = 1, overflow = TextOverflow.Ellipsis) }, colors = chipColors(),
            trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.maps_anywhere), modifier = Modifier.size(16.dp)) })
    } }
    // OK: what is picked, on the map around what is on the screen, the panel out of the way (the user, 21 Oct: "να πατάω απλά
    // ρεστοράντ και να πατάω οκ και να βγάζει τα ρεστοράντ σε αυτό το κομάτι που βλέπω … ή και λίγο μεγαλήτερο")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { MapsSearchField(query, if (near != null) stringResource(R.string.maps_search_near, near) else stringResource(R.string.maps_search_hint), setQuery, ok) }
        Button(onClick = ok, enabled = query.isNotBlank() || pill != null, colors = panelButton(), modifier = Modifier.padding(end = 12.dp)) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MapsSearch.PILLS.forEach { k -> FilterChip(pill == k.key, { setPill(if (pill == k.key) null else k.key) }, label = { Text("${k.emoji} ${stringResource(k.label)}", maxLines = 1, overflow = TextOverflow.Ellipsis) }, colors = chipColors()) }
    }
    when {
        loading -> Text(stringResource(R.string.maps_search_loading), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        !hasList -> Text(stringResource(R.string.maps_search_missing),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        query.isBlank() && pill == null && near == null -> Text(stringResource(R.string.maps_search_how),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        results.isEmpty() -> Text(stringResource(R.string.maps_nothing_found), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        else -> LazyColumn { items(results) { p -> PoiRow(p, distance(p)) { open(p) } } }
    }
}

@Composable internal fun PoiRow(p: Poi, metres: Double?, click: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(MapsSearch.emoji(p.kind), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.weight(1f)) {
        val context = LocalContext.current
        Text(p.title(context), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(p.named(context), MapsSearch.label(p.kind, context), p.more).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.7f))
    }
    metres?.let { Text(metresText(it), style = MaterialTheme.typography.labelMedium) }
}

/**
 * Saved: the lists first, each a roomy row with its emoji and how many places; a tap goes into it, Back comes out (the user,
 * 10 Nov: "have the lists and click to go in to them. so they get separated … see how much brithing room there is").
 * [shown]: null the lists; "all" every place; a list's id; "eopyy" / "amea-check" the app's own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun FavoritesPanel(places: List<Place>, lists: List<PlaceList>, eopyy: List<Poi>, shown: String?, show: (String?) -> Unit, newList: () -> Unit,
    editList: (PlaceList) -> Unit, distance: (Place) -> Double?, open: (Place) -> Unit, checks: List<AmeaCheck> = emptyList(),
    openCheck: (AmeaCheck) -> Unit = {}, openPoi: (Poi) -> Unit) = MapsPanel {
    val context = LocalContext.current
    if (shown == null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.maps_tab_saved), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = newList) { Text(stringResource(R.string.maps_new_list_button), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        LazyColumn {
            item { SavedRow("📍", stringResource(R.string.maps_all_places), pluralStringResource(R.plurals.maps_n_places, places.size, places.size), onClick = { show("all") }) }
            items(lists, key = { it.id }) { l ->
                val n = places.count { l.id in it.lists }
                SavedRow(l.emoji.ifBlank { "📌" }, l.name, pluralStringResource(R.plurals.maps_n_places, n, n), onClick = { show(l.id) }, onLongClick = { editList(l) }, more = { editList(l) })
            }
            // the app's own: EOPYY's pharmacies, and the municipalities' ΑμεΑ spots to place by hand, nearest first
            if (eopyy.isNotEmpty()) item { SavedRow("⚕️", stringResource(R.string.maps_kind_eopyy_pharmacies), pluralStringResource(R.plurals.maps_n_places, eopyy.size, eopyy.size), onClick = { show("eopyy") }) }
            if (checks.isNotEmpty()) item { SavedRow("♿", stringResource(R.string.maps_amea_to_place, checks.size), null, onClick = { show("amea-check") }) }
        }
        return@MapsPanel
    }
    androidx.activity.compose.BackHandler { show(null) }
    val list = lists.firstOrNull { it.id == shown }
    val inList = when (shown) { "all" -> places; else -> places.filter { shown in it.lists } }
    // the open list's head: ‹ back, its emoji and name (a long press there changes it), how many
    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { show(null) }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
        val (emoji, title, count) = when (shown) {
            "all" -> Triple("📍", stringResource(R.string.maps_all_places), inList.size)
            "eopyy" -> Triple("⚕️", stringResource(R.string.maps_kind_eopyy_pharmacies), eopyy.size)
            "amea-check" -> Triple("♿", stringResource(R.string.maps_amea_to_place, checks.size), -1)
            else -> Triple(list?.emoji.orEmpty(), list?.name.orEmpty(), inList.size)
        }
        Column(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { list?.let(editList) })) {
            Text("$emoji $title".trim(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (count >= 0) Text(pluralStringResource(R.plurals.maps_n_places, count, count), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
    when (shown) {
        "amea-check" -> LazyColumn { items(checks, key = { it.id }) { c ->
            // the street when known, else what the decision says, without its common opening
            val what = if (',' in c.address) c.address else c.subject.replace(Regex("^.*?(στάθμευσης|στάθμευση)\\s+(ΑμεΑ|ΑΜΕΑ|για ΑμεΑ|για ΑΜΕΑ)?[,»\"]*\\s*", RegexOption.IGNORE_CASE), "")
                .ifBlank { c.subject }
            SavedRow("♿", what, "${c.municipality} · ${c.date.take(4)}", end = distance(Place("", "", c.lat, c.lng))?.let(::metresText), onClick = { openCheck(c) })
        } }
        "eopyy" -> LazyColumn { items(eopyy) { p ->
            SavedRow("⚕️", p.title(context), p.named(context)?.takeIf { it.isNotEmpty() }, end = distance(p.place(context))?.let(::metresText), onClick = { openPoi(p) })
        } }
        else -> if (inList.isEmpty()) Text(stringResource(if (shown == "all") R.string.maps_no_saved else R.string.maps_list_empty),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        else {
            // a long list gets a line to find in it (the user, 10 Nov: "I was tring to find the scool"); not in the car
            var find by remember(shown) { mutableStateOf("") }
            if (inList.size > 15 && !LocalOnCar.current) MapsSearchField(find, stringResource(R.string.maps_list_find), { find = it })
            val found = if (find.isBlank()) inList else inList.filter { MapsSearch.fold("${it.name} ${it.label}").contains(MapsSearch.fold(find.trim())) }
            // the words many names here start with ("Δημοτικό Σχολείο …") are dimmed, so the part that tells them apart stands out
            val lead = found.map { it.short.split(' ').take(2).joinToString(" ") }.filter { ' ' in it }.groupingBy { it }.eachCount()
                .filterValues { it >= 3 }.keys
            LazyColumn {
                items(found.sortedBy { distance(it) ?: Double.MAX_VALUE }, key = { it.id }) { p ->
                    val more = listOfNotNull(
                        p.name.takeIf { p.label.isNotBlank() && MapsSearch.fold(p.label).replace(" ", "") != MapsSearch.fold(p.name).replace(" ", "") },
                        stringResource(R.string.maps_home_slot, p.slot).takeIf { p.slot > 0 },
                        lists.filter { it.id in p.lists && it.id != shown }.joinToString("") { it.emoji }.ifEmpty { null },
                    ).joinToString(" · ")
                    SavedRow(null, p.short, more.ifEmpty { null }, icon = placeIcon(p.icon), dim = lead.firstOrNull { p.short.startsWith("$it ") }?.length ?: 0,
                        end = distance(p)?.let(::metresText), onClick = { open(p) })
                }
            }
        }
    }
}

/**
 * One row of Saved: a round badge (an emoji or an emblem), the name on up to two lines ([dim] of its first characters quieter),
 * a quiet line under it, [end] (the distance) at the right; [more] a ⋮. In the car bigger, the name on one line, at a glance.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun SavedRow(emoji: String?, title: String, sub: String?, icon: Int? = null, dim: Int = 0, end: String? = null, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, more: (() -> Unit)? = null) {
    val car = LocalOnCar.current
    Row(Modifier.fillMaxWidth().heightIn(min = if (car) 88.dp else 64.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(start = 16.dp, end = if (more != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.12f), modifier = Modifier.size(if (car) 56.dp else 44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(if (car) 28.dp else 22.dp))
                else Text(emoji.orEmpty(), style = if (car) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
            }
        }
        Column(Modifier.weight(1f)) {
            val text = if (dim in 1 until title.length) androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Normal)); append(title.take(dim)); pop()
                append(title.drop(dim))
            } else androidx.compose.ui.text.AnnotatedString(title)
            Text(text, style = if (car) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = if (car) 1 else 2, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = if (car) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        end?.let { Text(it, style = if (car) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge) }
        more?.let { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.common_edit)) } }
    }
}

/** Directions: how to go, then where — a search, or one of Home's places and the car. */
@Composable internal fun NavigatePanel(mode: String, setMode: (String) -> Unit, rolling: Boolean, query: String, setQuery: (String) -> Unit, results: List<Poi>,
    quick: List<Place>, distance: (Poi) -> Double?, goPoi: (Poi) -> Unit, goPlace: (Place) -> Unit,
    trips: List<Trip> = emptyList(), plan: () -> Unit = {}, startTrip: (Trip) -> Unit = {}, deleteTrip: (Trip) -> Unit = {}) = MapsPanel {
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Place.MODES.forEach { m -> FilterChip(mode == m, { setMode(m) }, label = { Text(stringResource(modeLabelOf(m, rolling)), maxLines = 1, overflow = TextOverflow.Ellipsis) }, colors = chipColors(),
            leadingIcon = { Icon(painterResource(modeIcon(m, rolling)), contentDescription = null, modifier = Modifier.size(16.dp)) }) }
    }
    MapsSearchField(query, stringResource(R.string.maps_where_to), setQuery)
    // trips: several stops, one after the other (Plan a trip asks how many, then each in turn)
    if (query.isBlank()) {
        OutlinedButton(onClick = plan, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.maps_plan_trip_button), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        trips.forEach { t ->
            Row(Modifier.fillMaxWidth().clickable { startTrip(t) }.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("🧭", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f)) {
                    Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.stops.withIndex().joinToString(" → ") { (i, p) -> "${Trip.letter(i)} ${p.name}" }, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.7f))
                }
                IconButton(onClick = { deleteTrip(t) }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.maps_delete_trip)) }
            }
        }
    }
    if (query.isNotBlank()) LazyColumn { items(results) { p -> PoiRow(p, distance(p)) { goPoi(p) } } }
    else if (quick.isEmpty()) Text(stringResource(R.string.maps_directions_how), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
    else LazyColumn {
        items(quick, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().clickable { goPlace(p) }.padding(horizontal = 16.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(placeIcon(p.icon)), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(p.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@androidx.annotation.StringRes internal fun modeLabelOf(mode: String, rolling: Boolean) = when (mode) {
    "foot" -> if (rolling) R.string.maps_mode_roll else R.string.maps_mode_walk; "bike" -> R.string.maps_mode_bike; else -> R.string.maps_mode_car
}

/** "1 h 5 min", "12 min": a time for a glance. */
internal fun minutesText(c: android.content.Context, minutes: Long): String =
    if (minutes >= 60) c.getString(R.string.maps_hours_minutes, minutes / 60, minutes % 60) else c.getString(R.string.maps_minutes, minutes)

/**
 * A place's page (the user, 21 Oct: "If I click touch on a place there is no info page for that? Atlist when I am online?"):
 * what OSM knows of it offline — kind, address, hours, phone, website, wheelchair access — directions, Save, and Google
 * Maps for reviews and photos when online.
 */
@Composable internal fun PlaceCard(place: Place, poi: Poi?, address: String?, saved: Boolean, metres: Double?, rolling: Boolean, lists: List<PlaceList>,
    go: (String) -> Unit, save: () -> Unit, edit: () -> Unit, delete: () -> Unit, close: () -> Unit, remove: (() -> Unit)? = null, nearby: () -> Unit) {
    val context = LocalContext.current
    fun open(uri: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saved) Icon(painterResource(placeIcon(place.icon)), contentDescription = null) else Text(poi?.let { MapsSearch.emoji(it.kind) } ?: "📍", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // its name in the app's language under its own: Νευροκόπι under Гоце Делчев
                    poi?.named(context)?.takeIf { it != place.name }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    val sub = listOfNotNull(poi?.let { MapsSearch.label(it.kind, context) }, metres?.let { context.getString(R.string.maps_away, metresText(it)) }, lists.filter { it.id in place.lists }.joinToString(" ") { it.emoji }.ifEmpty { null })
                    if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                }
                if (saved) {
                    IconButton(onClick = edit) { Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.common_edit)) }
                    IconButton(onClick = delete) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                }
                // one I added to the map: it can go again
                remove?.let { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.maps_remove_from_map)) } }
                IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close)) }
            }
            // the place's own address when OSM has it, else the nearest one's
            (poi?.more?.takeIf { it.any(Char::isDigit) } ?: address)?.let { Text("📍 $it", style = MaterialTheme.typography.bodyMedium) }
            Text("%.6f, %.6f".format(java.util.Locale.ROOT, place.lat, place.lng), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            poi?.let { p ->
                if (p.more.isNotEmpty() && !p.more.any(Char::isDigit)) Text(p.more, style = MaterialTheme.typography.bodyMedium)
                if (p.hours.isNotEmpty()) Text("🕒 ${p.hours}", style = MaterialTheme.typography.bodyMedium)
                if (p.phone.isNotEmpty()) Text("📞 ${p.phone}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9FA8DA),
                    modifier = Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.phone.substringBefore(';').replace(" ", "")))) } })
                if (p.web.isNotEmpty()) Text("🌐 ${p.web.removePrefix("https://").removePrefix("http://").removeSuffix("/")}", style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9FA8DA), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { open(if (p.web.startsWith("http")) p.web else "https://${p.web}") })
                if (p.note.isNotEmpty()) Text("⚠️ ${p.note}", style = MaterialTheme.typography.bodyMedium)
                when (p.wheelchair) {
                    "yes" -> Text(stringResource(R.string.maps_wheelchair_yes), style = MaterialTheme.typography.bodyMedium)
                    "limited" -> Text(stringResource(R.string.maps_wheelchair_limited), style = MaterialTheme.typography.bodyMedium)
                    "no" -> Text(stringResource(R.string.maps_wheelchair_no), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Place.MODES.forEach { m ->
                    Button(onClick = { go(m) }, modifier = Modifier.weight(1f), colors = panelButton()) {
                        Icon(painterResource(modeIcon(m, rolling)), contentDescription = null, modifier = Modifier.size(18.dp)); Text(" " + stringResource(modeLabelOf(m, rolling)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = nearby) { Text(stringResource(R.string.maps_nearby), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (!saved) OutlinedButton(onClick = save) { Text(stringResource(R.string.common_save), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                // online: Google's page of the place, with its reviews and photos
                OutlinedButton(onClick = {
                    val q = if (poi != null && poi.name.isNotEmpty()) "${poi.name}, ${poi.more.ifEmpty { "%.6f,%.6f".format(java.util.Locale.ROOT, place.lat, place.lng) }}"
                        else "%.6f,%.6f".format(java.util.Locale.ROOT, place.lat, place.lng)
                    open("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(q))
                }) { Text("Google Maps", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** A list's name and emoji (the user, 21 Oct: "make lists with names and emojes"). */
@Composable internal fun ListDialog(l: PlaceList, dismiss: () -> Unit, delete: (() -> Unit)?, save: (PlaceList) -> Unit) {
    var name by remember { mutableStateOf(l.name) }
    var emoji by remember { mutableStateOf(l.emoji) }
    TetraAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (delete == null) R.string.maps_new_list else R.string.maps_list)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(emoji, { emoji = it.take(8) }, label = { Text(stringResource(R.string.maps_emoji)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("❤️", "⭐", "🚩", "✈️", "🍽️", "☕", "🏖️", "⛰️", "🌊", "🏛️", "🛍️", "🏠", "👨‍👩‍👧", "♿", "🅿️", "💊", "🎉", "🌳").forEach { e ->
                    Text(e, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { emoji = e }.padding(6.dp))
                }
            }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(l.copy(id = l.id.ifEmpty { MapsFiles.newId() }, name = name.trim(), emoji = emoji.trim())) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { Row {
            delete?.let { TextButton(onClick = it) { Text(stringResource(R.string.maps_delete_list)) } }
            TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
        } })
}

/** Plan a trip: how many stops (the user, 22 Oct: "questions like how many stops you will do 5"). */
@Composable internal fun TripCountDialog(dismiss: () -> Unit, pick: (Int) -> Unit) = TetraAlertDialog(onDismissRequest = dismiss,
    title = { Text(stringResource(R.string.maps_plan_trip)) },
    text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.maps_plan_how_many))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (2..10).forEach { n -> OutlinedButton(onClick = { pick(n) }) { Text("$n") } }
        }
    } },
    confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })

/** The trip's stops all pointed to: its name, then Save, or Save and go. */
@Composable internal fun TripNameDialog(stops: List<Place>, dismiss: () -> Unit, save: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(stops.joinToString(" → ") { it.name.substringBefore(",") }.take(60)) }
    TetraAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.maps_save_trip)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            stops.forEachIndexed { i, p -> Text("${Trip.letter(i)}  ${p.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim(), true) }) { Text(stringResource(R.string.maps_save_and_go)) } },
        dismissButton = { Row {
            TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim(), false) }) { Text(stringResource(R.string.common_save)) }
            TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
        } })
}

/** The ways there to pick from: each its time, length and roads; Start goes by the chosen one (drawn bold). */
@Composable internal fun RoutesPanel(routes: List<Route>, tolls: List<Int>, chosen: Int, choose: (Int) -> Unit, cancel: () -> Unit, start: () -> Unit) = MapsPanel {
    routes.forEachIndexed { i, r ->
        val on = i == chosen
        Surface(shape = MaterialTheme.shapes.large, color = if (on) Color.White.copy(alpha = 0.18f) else Color.Transparent,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable { choose(i) }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val minutes = Math.round(r.millis / 60_000.0)
                Text(minutesText(LocalContext.current, minutes), style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                Text(metresText(r.metres), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                Text(stringResource(when (r.roads) { "motorway" -> R.string.maps_roads_motorway; "main" -> R.string.maps_roads_main; "track" -> R.string.maps_roads_tracks; else -> R.string.maps_roads_local }),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // tolls on it (OSM's toll stations on its line), or none (the user: "alternative routs with tolls or without")
                // (OSM misses some ramp tolls: "No tolls" is said only for local roads)
                tolls.getOrNull(i)?.let { k -> if (k > 0) Text("💶 $k", style = MaterialTheme.typography.labelMedium)
                    else if (r.roads == "local") Text(stringResource(R.string.maps_no_tolls), style = MaterialTheme.typography.labelMedium) }
                if (i == 0) Text(stringResource(R.string.maps_fastest), style = MaterialTheme.typography.labelMedium, color = Color(0xFF9FA8DA))
            }
        }
    }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = start, modifier = Modifier.weight(1f), colors = panelButton()) { Text(stringResource(R.string.common_start), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        OutlinedButton(onClick = cancel) { Text(stringResource(R.string.common_cancel), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
