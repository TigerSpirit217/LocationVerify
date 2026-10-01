package com.tiger.locationverify.util

import android.os.Build
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.tiger.locationverify.R

/** Keep controls clear of system bars, cutouts and the keyboard with targetSdk 37. */
@Suppress("DEPRECATION")
fun AppCompatActivity.applySystemBarInsets() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
    // Replace the layout's legacy inset handling to avoid applying padding twice.
    root.fitsSystemWindows = false
    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    WindowCompat.getInsetsController(window, root).apply {
        val lightBars = resources.getBoolean(R.bool.light_system_bars)
        isAppearanceLightStatusBars = lightBars
        isAppearanceLightNavigationBars = lightBars
    }
    // Android 6–7 cannot draw dark navigation icons; keep their bar background dark.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        window.navigationBarColor = ContextCompat.getColor(this, R.color.primary_dark)
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val safeArea = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        view.setPadding(
            left + safeArea.left,
            top + safeArea.top,
            right + safeArea.right,
            bottom + safeArea.bottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
