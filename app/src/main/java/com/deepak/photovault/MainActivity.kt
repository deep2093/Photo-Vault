package com.deepak.photovault

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.deepak.photovault.core.media.FavouritesStore
import com.deepak.photovault.core.media.MediaAccess
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.ui.shell.AppearanceSettings
import com.deepak.photovault.ui.shell.AppearanceStore
import com.deepak.photovault.ui.shell.GalleryState
import com.deepak.photovault.ui.shell.MediaActions
import com.deepak.photovault.ui.shell.PhotoVaultApp
import com.deepak.photovault.ui.shell.PlatformSignals
import com.deepak.photovault.ui.shell.ShellState

/** Android host for the shared PhotoVault shell (the same screens are written to run in a PC app later). */
class MainActivity : ComponentActivity(), MediaActions {
    private var signals by mutableStateOf(PlatformSignals())
    private lateinit var gallery: GalleryState

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshSignals()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshAccess()
    }

    /** Android's own confirmation for Recently deleted, restore and delete forever. Nothing to do on return:
     * the library notices the change by itself. */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = ShellState(PrefsAppearanceStore(this))
        gallery = GalleryState(MediaStoreLibrary(this), PrefsFavouritesStore(this))
        refreshSignals()
        refreshAccess()
        setContent {
            val dark = state.theme.isDark
            // Status and navigation bar icons follow the PhotoVault theme, not the system theme.
            LaunchedEffect(dark) {
                val transparent = Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BackHandler(enabled = state.canGoBack) { state.back() }
            PhotoVaultApp(state, gallery, this, signals)
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
        // Settings can change while the app is away: "remove animations", or photo access in app info.
        refreshSignals()
        refreshAccess()
    }

    override fun onStop() {
        unregisterReceiver(powerSaveReceiver)
        super.onStop()
    }

    // ---- Photo access (Android 11-12: storage; 13: images and videos; 14+: may be "selected photos only") ----

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshAccess() {
        gallery.access = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.FULL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
            else -> MediaAccess.NONE
        }
    }

    override fun requestAccess() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    // ---- Actions ----

    override fun share(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { Uri.parse(it.uri) }
        val type = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.apply {
            setType(type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Lets the receiving app read every shared item, not just the first.
            clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        }
        startActivity(Intent.createChooser(send, null))
    }

    override fun moveToRecentlyDeleted(items: List<MediaItem>) = askAndroid(items) { uris ->
        MediaStore.createTrashRequest(contentResolver, uris, true)
    }

    override fun restore(items: List<MediaItem>) = askAndroid(items) { uris ->
        MediaStore.createTrashRequest(contentResolver, uris, false)
    }

    override fun deleteForever(items: List<MediaItem>) = askAndroid(items) { uris ->
        MediaStore.createDeleteRequest(contentResolver, uris)
    }

    /** Shows Android's own confirmation; PhotoVault never deletes around it. */
    private fun askAndroid(items: List<MediaItem>, request: (List<Uri>) -> android.app.PendingIntent) {
        if (items.isEmpty()) return
        val pending = request(items.map { Uri.parse(it.uri) })
        consentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }

    // ---- Platform signals ----

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

/** Favourites by MediaStore ID. Kept by the app; they move into the drive manifest with backup (Phase 4). */
private class PrefsFavouritesStore(context: Context) : FavouritesStore {
    private val prefs = context.applicationContext.getSharedPreferences("favourites", Context.MODE_PRIVATE)

    override fun load(): Set<Long> = prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()

    override fun save(ids: Set<Long>) {
        prefs.edit().putStringSet("ids", ids.map(Long::toString).toSet()).apply()
    }
}
