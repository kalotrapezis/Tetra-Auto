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

/**
 * Tetra Auto: the car's whole app. Paired and the phone there, its picture fills the screen and every touch goes back to it;
 * else the way to pair (a QR for Tetra on the phone) and Share. The head unit's home screen if chosen, so it is there at start.
 */
class MainActivity : ComponentActivity() {
    private val video = Video()
    private lateinit var share: Share
    private lateinit var link: Link

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        share = Share(applicationContext)
        link = Link(applicationContext, video, share)
        // the whole screen, bars and all: the phone draws Tetra Car this size
        @Suppress("DEPRECATION") android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.let { link.width = it.widthPixels; link.height = it.heightPixels; link.dpi = it.densityDpi }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Car(link, share, video) } }
    }

    override fun onStart() { super.onStart(); link.start() }
    override fun onStop() { link.stop(); super.onStop() }

    // the unit's Back and its steering wheel's voice key go to the phone while its picture is shown
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (link.connected && showing && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_SEARCH ||
                k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_NEXT || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            if (event.action == KeyEvent.ACTION_UP) link.send(Wire.KEY, ByteBuffer.allocate(4).putInt(k).array())
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object { @Volatile var showing = false }
}

@Composable
private fun Car(link: Link, share: Share, video: Video) {
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(link.shareAsked) { if (link.shareAsked > 0) sharing = true }
    LaunchedEffect(share.newApp) { if (share.newApp != null) sharing = true }
    val state = link.state
    MainActivity.showing = state is LinkState.Connected && !sharing
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // the picture stays while Share is over it, so the decoder is not started again
        if (state is LinkState.Connected) Projection(link, video)
        if (state is LinkState.Looking) Looking(state, link) { sharing = true }
        if (sharing) SharePage(link, share) { sharing = false; share.newApp = null }
        share.said?.let { s ->
            LaunchedEffect(s) { kotlinx.coroutines.delay(4000); if (share.said == s) share.said = null }
            Surface(Modifier.align(Alignment.BottomCenter).padding(24.dp), shape = RoundedCornerShape(24.dp), color = Color(0xEE222222), contentColor = Color.White) {
                Text(s, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

/** The phone's picture, the size of the screen; touches as fractions of it, so the phone maps them onto its own. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun Projection(link: Link, video: Video) = AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
    SurfaceView(c).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) { video.surface(h.surface, w, hgt); link.send(Wire.KEYFRAME, ByteArray(0)) }
            override fun surfaceDestroyed(h: SurfaceHolder) { video.surface(null, 0, 0) }
        })
        setOnTouchListener { v, e ->
            val n = e.pointerCount
            val b = ByteBuffer.allocate(3 + n * 9)
            b.put(e.actionMasked.toByte()).put(e.actionIndex.toByte()).put(n.toByte())
            for (i in 0 until n) b.put(e.getPointerId(i).toByte()).putFloat(e.getX(i) / v.width).putFloat(e.getY(i) / v.height)
            link.send(Wire.TOUCH, b.array())
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }
})

/** No phone yet: what to do, the QR while this car is not paired, and Share. */
@Composable
private fun Looking(state: LinkState.Looking, link: Link, openShare: () -> Unit) {
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
private fun SharePage(link: Link, share: Share, close: () -> Unit) {
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
private fun qrBitmap(text: String, size: Int = 560): Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
