package com.kalotrapezis.tetra.auto

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.nio.ByteBuffer

/** No phone yet: what to do, the QR while this car is not paired, and Share. */
@Composable
internal fun Looking(state: LinkState.Looking, link: Link, openShare: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_directions_car), null, Modifier.size(40.dp), tint = Color.White)
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            if (state.pairCode != null) {
                Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(stringResource(R.string.pair_steps), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFCCCCCC))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.looking), style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                Text(stringResource(R.string.looking_steps), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFCCCCCC))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = openShare) { Icon(painterResource(R.drawable.ic_share), null); Text("  " + stringResource(R.string.share_title)) }
                if (state.pairCode == null) TextButton(onClick = link::forget) { Text(stringResource(R.string.pair_again)) }
            }
        }
        state.pairCode?.let { code ->
            val bitmap = remember(code) { qrBitmap(link.qr(code)) }
            Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) { Image(bitmap.asImageBitmap(), stringResource(R.string.pair_title), Modifier.padding(16.dp).size(280.dp)) }
        }
    }
}

/** Share: what came from the phone (an app offered for installing), and files from here to the phone. */
@Composable
internal fun SharePage(link: Link, share: Share, close: () -> Unit) {
    BackHandler(onBack = close)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) share.send(link, uris) }
    LaunchedEffect(Unit) { share.refresh() }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF121416), contentColor = Color.White) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
                Text(stringResource(R.string.share_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = link.connected) {
                    Icon(painterResource(R.drawable.ic_send), null); Text("  " + stringResource(R.string.share_send))
                }
            }
            if (!link.connected) Text(stringResource(R.string.share_needs_phone), color = Color(0xFFCCCCCC))
            share.newApp?.let { apk ->
                Surface(color = Color(0xFF1F3A2B), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.share_app_came, apk.name), Modifier.weight(1f))
                        Button(onClick = { share.open(apk) }) { Text(stringResource(R.string.share_install)) }
                    }
                }
            }
            share.moving.forEach { (name, done) ->
                Column { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis); LinearProgressIndicator(progress = { done }, Modifier.fillMaxWidth()) }
            }
            if (share.files.isEmpty()) Text(stringResource(R.string.share_empty), color = Color(0xFFCCCCCC))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(share.files, key = { it.path }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { share.open(f) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_folder), null, Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, f.length()), style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                        }
                        IconButton(onClick = { share.delete(f) }) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

/** The pairing QR, black on white (ZXing). */
internal fun qrBitmap(text: String, size: Int = 560): Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
