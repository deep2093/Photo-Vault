package com.deepak.photovault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.deepak.photovault.ui.shell.AppearanceSettings
import com.deepak.photovault.ui.shell.AppearanceStore
import com.deepak.photovault.ui.shell.PhotoVaultApp
import com.deepak.photovault.ui.shell.PlatformSignals
import com.deepak.photovault.ui.shell.ShellState

/** Android host for the shared PhotoVault shell (the same screens run in the PC app). */
class MainActivity : ComponentActivity() {
    private var signals by mutableStateOf(PlatformSignals())

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshSignals()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = ShellState(PrefsAppearanceStore(this))
        refreshSignals()
        setContent {
            val dark = state.theme.isDark
            // Status and navigation bar icons follow the PhotoVault theme, not the system theme.
            LaunchedEffect(dark) {
                val transparent = Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PhotoVaultApp(state, signals)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // "Remove animations" has no broadcast; re-read it whenever the app comes back.
        refreshSignals()
    }

    override fun onStop() {
        unregisterReceiver(powerSaveReceiver)
        super.onStop()
    }

    private fun refreshSignals() {
        val power = getSystemService(PowerManager::class.java)
        val animatorScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        signals = PlatformSignals(
            powerSaveMode = power?.isPowerSaveMode == true,
            systemReduceMotion = animatorScale == 0f,
        )
    }
}

/** Appearance choices in SharedPreferences. Not secret, so no encryption. */
private class PrefsAppearanceStore(context: Context) : AppearanceStore {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    override fun load() = AppearanceSettings(
        themeId = prefs.getString("theme", null) ?: AppearanceSettings().themeId,
        blurOn = prefs.getBoolean("blur", true),
        reduceMotion = prefs.getBoolean("reduce_motion", false),
    )

    override fun save(settings: AppearanceSettings) {
        prefs.edit()
            .putString("theme", settings.themeId)
            .putBoolean("blur", settings.blurOn)
            .putBoolean("reduce_motion", settings.reduceMotion)
            .apply()
    }
}
