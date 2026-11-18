package com.kalotrapezis.drive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** [dir] as Tetra's path ("Documents/Work"), or null when it is outside Tetra's folder. */
private fun tetraRelative(root: File, dir: File): String? = runCatching { DriveRules.relative(root, dir) }.getOrNull()

/**
 * Save as's where and what name (the user, 13 Nov: "If I click Save as I should choose where and with what name to save"):
 * [name] (the name field and whatever else the dialog asks) above a Folder row; a tap on the row slides to Tetra's
 * folders, one level at a time as Files' Move: a tap goes in, Up goes out, and the folder you are in is where Save puts
 * it ([pick] at once, so the dialog's Save is never stale). The back arrow slides back to the name.
 */
@Composable
internal fun SaveAsWhere(dir: File, pick: (File) -> Unit, name: @Composable () -> Unit) {
    val root = remember { driveRoot() }
    var browsing by remember { mutableStateOf(false) }
    val rel = tetraRelative(root, dir)
    val path = rel?.let { listOf("Tetra") + it.split('/').filter(String::isNotEmpty) }?.joinToString(" › ") ?: dir.path
    AnimatedContent(browsing, label = "save-as-where", transitionSpec = {
        if (targetState) slideInHorizontally { it } togetherWith slideOutHorizontally { -it } else slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
    }) { b -> if (!b) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        name()
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable {
            if (rel == null) pick(root) // a file from outside Tetra: its folders start at Tetra's top
            browsing = true; WriterLog.i("Save as: choosing the folder")
        }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.files_folder), style = MaterialTheme.typography.labelMedium)
                    Text(path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.common_open), modifier = Modifier.size(20.dp))
            }
        }
    } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val at = rel ?: ""
        val inside = remember(at) { runCatching { DriveRules.subfolders(root, at) }.getOrDefault(emptyList()) }
        Row(Modifier.fillMaxWidth().clickable { browsing = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back), modifier = Modifier.size(22.dp))
            Text(path, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (at.isNotEmpty()) item("up") { SaveFolderLine(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.files_up_to, DriveRules.parent(at).substringAfterLast('/').ifEmpty { "Tetra" })) {
                pick(if (DriveRules.parent(at).isEmpty()) root else File(root, TetraSystem.onDisk(DriveRules.parent(at)))) } }
            items(inside, key = { it }) { f -> SaveFolderLine(R.drawable.ic_folder, f.substringAfterLast('/')) { pick(File(root, TetraSystem.onDisk(f))) } }
            if (inside.isEmpty()) item("none") { Text(stringResource(R.string.files_no_folders), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
        }
    } }
}

@Composable private fun SaveFolderLine(icon: Int, text: String, click: () -> Unit) =
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = click)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Text(text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
