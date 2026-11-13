package com.kalotrapezis.tetra.auto

import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Tetra Auto SE, "stand alone edition" (the user, 13 Nov): Tetra Car run by the head unit itself — the map with its offline maps
 * and GPS, the radio out of its own speakers — with the phone only for its hotspot and for signals and commands over Tetra
 * Auto's link (Link, mode "standalone"). The head unit's home screen if chosen, as Tetra Auto.
 */
class MainActivity : ComponentActivity() {
    private val video = Video() // the link's; SE never shows a picture
    private lateinit var share: Share
    private lateinit var link: Link

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
        if (got[Manifest.permission.ACCESS_FINE_LOCATION] == true) Fixes.startCar(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        // the unit's bars, swiped out, stay on some head units (the user, 10 Nov): hidden again 3 s after they show
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.systemBars())) { v.removeCallbacks(rehide); v.postDelayed(rehide, 3000) }
            androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets)
        }
        CarView.load(this); Fixes.load(this); Radio.init(this); Speech.init(this)
        share = Share(applicationContext)
        link = Link(applicationContext, video, share, mode = "standalone", signal = PhoneLink::signal)
        PhoneLink.link = link
        @Suppress("DEPRECATION") android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.let { link.width = it.widthPixels; link.height = it.heightPixels; link.dpi = it.densityDpi }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) Fixes.startCar(this)
        val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ask.launch(missing.toTypedArray())
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { CarScreen(link, share) } }
    }

    private val rehide = Runnable { hideBars() }
    private fun hideBars() = WindowInsetsControllerCompat(window, window.decorView).run {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(WindowInsetsCompat.Type.systemBars())
    }
    override fun onResume() { super.onResume(); hideBars() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }

    override fun onStart() { super.onStart(); link.start() }
    override fun onDestroy() { link.stop(); PhoneLink.link = null; Fixes.stopCar(this); Radio.release(); Speech.release(); super.onDestroy() }

    // the steering wheel's media keys: the radio
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (event.action == KeyEvent.ACTION_UP) Radio.toggle()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
