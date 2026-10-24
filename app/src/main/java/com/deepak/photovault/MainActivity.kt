package com.deepak.photovault

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
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
import com.deepak.photovault.core.media.AndroidMediaDetails
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.ui.shell.AppIconChoice
import com.deepak.photovault.ui.shell.AppIconSwitcher
import com.deepak.photovault.ui.shell.AppearanceSettings
import com.deepak.photovault.ui.shell.AppearanceStore
import com.deepak.photovault.ui.shell.DriveState
import com.deepak.photovault.ui.shell.GalleryState
import com.deepak.photovault.ui.shell.MediaActions
import com.deepak.photovault.ui.shell.PhotoVaultApp
import com.deepak.photovault.ui.shell.PlatformSignals
import com.deepak.photovault.ui.shell.ShellState
import com.deepak.photovault.ui.shell.Destination
import com.deepak.photovault.ui.shell.Overlay
import com.deepak.photovault.ui.shell.IconGlyph
import com.deepak.photovault.ui.shell.OpeningAnimation
import com.deepak.photovault.ui.shell.PickedImage
import com.deepak.photovault.backup.BackupGraph
import android.graphics.BitmapFactory
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android host for the shared PhotoVault shell (the same screens are written to run in a PC app later). */
class MainActivity : ComponentActivity(), MediaActions {
    companion object {
        const val EXTRA_OPEN_BACKUP = "open_backup"

        /** The opening animation has played in this run of the app. */
        private var introPlayed = false
    }

    private var signals by mutableStateOf(PlatformSignals())
    private lateinit var gallery: GalleryState
    private lateinit var drive: DriveState
    private val iconSwitcher by lazy { LauncherIconSwitcher(this) }

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshSignals()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshAccess()
    }

    /** Android's own confirmation for Recently deleted, restore and delete forever. Nothing to do on return:
     * the library notices the change by itself. */
    /** Told whether the person agreed to Android's delete or restore prompt (the delete animation waits for it). */
    private var consentDone: ((Boolean) -> Unit)? = null
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        consentDone?.invoke(result.resultCode == RESULT_OK)
        consentDone = null
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Waiting for Android's "allow changes" answer before renaming an album. */
    private var pendingRename: Triple<List<MediaItem>, String, (String?) -> Unit>? = null

    private val renameLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val (items, newName, done) = pendingRename ?: return@registerForActivityResult
        pendingRename = null
        if (result.resultCode != RESULT_OK) {
            done("Not renamed.")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { moveToFolder(items, newName) }
            done(error)
        }
    }

    /** Waiting for the photo picker to hand back a profile picture. */
    private var onProfilePhoto: ((PickedImage) -> Unit)? = null

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val done = onProfilePhoto
        onProfilePhoto = null
        if (uri != null && done != null) {
            lifecycleScope.launch {
                val image = withContext(Dispatchers.IO) { runCatching { ProfilePicture.load(contentResolver, uri) }.getOrNull() }
                if (image != null) done(image)
            }
        }
    }

    private lateinit var shell: ShellState
    private var showIntro by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = ShellState(PrefsAppearanceStore(this))
        shell = state
        gallery = GalleryState(MediaStoreLibrary(this), PrefsFavouritesStore(this), AndroidMediaDetails(this), PrefsHiddenAlbumsStore(this))
        drive = DriveState(AndroidDrivePlatform(this))
        val backup = BackupGraph.get(this).state
        runCatching { iconSwitcher.tidyUp() }
        refreshSignals()
        refreshAccess()
        openFromIntent(intent)
        // The opening animation plays once per fresh start of the app, never with reduce motion (24 Oct).
        showIntro = savedInstanceState == null && !introPlayed && !state.glassSettings(signals).reduceMotion
        introPlayed = true
        val glyph = iconSwitcher.current().glyph
        val introIcon = if (showIntro) introBitmap(glyph) else null
        setContent {
            val dark = state.theme.isDark
            // Status and navigation bar icons follow the PhotoVault theme, not the system theme.
            LaunchedEffect(dark) {
                val transparent = Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BackHandler(enabled = state.canGoBack) { state.back() }
            Box(Modifier.fillMaxSize()) {
                PhotoVaultApp(state, gallery, this@MainActivity, drive, backup, signals, iconSwitcher)
                if (showIntro && introIcon != null) {
                    OpeningAnimation(
                        icon = introIcon,
                        glyph = glyph,
                        background = if (dark) androidx.compose.ui.graphics.Color(0xFF0B0E14) else androidx.compose.ui.graphics.Color(0xFFF7F9FC),
                        onDone = { showIntro = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    /** A backup notification opens the Backup section. */
    private fun openFromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_BACKUP, false) == true) {
            shell.overlays.clear()
            shell.destination = Destination.SETTINGS
            shell.open(Overlay.Backup)
        }
    }

    private fun introBitmap(glyph: IconGlyph): ImageBitmap? = runCatching {
        val id = resources.getIdentifier("ic_${glyph.name.lowercase()}_foreground", "drawable", packageName)
        BitmapFactory.decodeResource(resources, id)?.asImageBitmap()
    }.getOrNull()

    override fun onStart() {
        super.onStart()
        foreground = true
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
        foreground = false
        refreshSignals()
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
        // Photo locations are asked for together, so the details panel can show where photos were taken.
        permissionLauncher.launch(permissions + Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    override fun requestLocationAccess() {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
    }

    override fun openMap(latitude: Double, longitude: Double, label: String) {
        // A geo: link opens whichever maps app the phone uses; PhotoVault itself looks nothing up.
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
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

    override fun moveToRecentlyDeleted(items: List<MediaItem>, onDone: (Boolean) -> Unit) = askAndroid(items, onDone) { uris ->
        MediaStore.createTrashRequest(contentResolver, uris, true)
    }

    override fun restore(items: List<MediaItem>) = askAndroid(items, {}) { uris ->
        MediaStore.createTrashRequest(contentResolver, uris, false)
    }

    override fun deleteForever(items: List<MediaItem>, onDone: (Boolean) -> Unit) = askAndroid(items, onDone) { uris ->
        MediaStore.createDeleteRequest(contentResolver, uris)
    }

    /** Shows Android's own confirmation; PhotoVault never deletes around it. */
    private fun askAndroid(items: List<MediaItem>, onDone: (Boolean) -> Unit, request: (List<Uri>) -> android.app.PendingIntent) {
        if (items.isEmpty()) return onDone(false)
        val pending = request(items.map { Uri.parse(it.uri) })
        consentDone = onDone
        consentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }

    // ---- Backup and family ----

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    override fun pickProfilePhoto(onPicked: (PickedImage) -> Unit) {
        onProfilePhoto = onPicked
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun cropProfilePhoto(image: PickedImage, left: Int, top: Int, side: Int, onDone: (ByteArray) -> Unit) {
        lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.Default) { runCatching { ProfilePicture.crop(image, left, top, side) }.getOrNull() }
            if (jpeg != null) onDone(jpeg)
        }
    }

    override fun phoneStorage(): Pair<Long, Long>? = runCatching {
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        stat.totalBytes to stat.availableBytes
    }.getOrNull()

    override fun deleteAlbum(albumId: String, onDone: (Boolean) -> Unit) = moveToRecentlyDeleted(gallery.items.filter { it.albumId == albumId }, onDone)

    override fun renameAlbum(albumId: String, newName: String, onDone: (String?) -> Unit) {
        val items = gallery.items.filter { it.albumId == albumId }
        if (items.isEmpty()) {
            onDone("This album is empty.")
            return
        }
        pendingRename = Triple(items, newName, onDone)
        val request = MediaStore.createWriteRequest(contentResolver, items.map { Uri.parse(it.uri) })
        renameLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    /** Moves every item to a sibling folder named [newName] (same parent, so Android allows it). Null on success. */
    private fun moveToFolder(items: List<MediaItem>, newName: String): String? = try {
        val first = Uri.parse(items.first().uri)
        val current = contentResolver.query(first, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: error("No folder")
        val parent = current.trimEnd('/').substringBeforeLast('/', "")
        val target = (if (parent.isEmpty()) "" else "$parent/") + newName + "/"
        val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }
        items.forEach { contentResolver.update(Uri.parse(it.uri), values, null, null) }
        null
    } catch (e: Exception) {
        "Android didn't allow renaming this album."
    }

    // ---- Platform signals ----

    private var foreground = false

    private fun refreshSignals() {
        val power = getSystemService(PowerManager::class.java)
        val animatorScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        signals = PlatformSignals(
            powerSaveMode = power?.isPowerSaveMode == true,
            systemReduceMotion = animatorScale == 0f,
            foreground = foreground,
        )
    }
}

/** Profile pictures: the picked photo at screen size (upright), then the square chosen in the app at 256 px. */
private object ProfilePicture {
    private const val SIZE = 256
    private const val MAX_SIDE = 1600

    fun load(resolver: android.content.ContentResolver, uri: Uri): PickedImage {
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
            if (longSide > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longSide
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return PickedImage(out.toByteArray(), bitmap.width, bitmap.height)
    }

    fun crop(image: PickedImage, left: Int, top: Int, side: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size)
        val s = side.coerceIn(1, minOf(bitmap.width, bitmap.height))
        val l = left.coerceIn(0, bitmap.width - s)
        val t = top.coerceIn(0, bitmap.height - s)
        val square = android.graphics.Bitmap.createBitmap(bitmap, l, t, s, s)
        val scaled = android.graphics.Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}

/**
 * Switches the home screen icon by enabling one launcher alias (see AndroidManifest.xml) and disabling the
 * rest. DONT_KILL_APP keeps PhotoVault open; the launcher picks up the change on its own.
 */
private class LauncherIconSwitcher(private val context: Context) : AppIconSwitcher {
    private val pm = context.packageManager

    private fun component(choice: AppIconChoice) = component(choice.aliasName)

    private fun component(alias: String) = ComponentName(context, "com.deepak.photovault.$alias")

    private fun state(choice: AppIconChoice) = pm.getComponentEnabledSetting(component(choice))

    /** Shown on the home screen: switched on in Settings, or (never switched) the manifest default. */
    private fun enabled(choice: AppIconChoice) = when (state(choice)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> choice == AppIconChoice()
        else -> false
    }

    // A choice made in Settings wins over the manifest default.
    override fun current(): AppIconChoice =
        AppIconChoice.all.firstOrNull { state(it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED && it != AppIconChoice() }
            ?: AppIconChoice.all.firstOrNull(::enabled)
            ?: AppIconChoice()

    override fun set(choice: AppIconChoice) {
        val changes = AppIconChoice.all.map { component(it) to if (it == choice) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED } +
            AppIconChoice.retired.keys.map { component(it) to PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // One change for all icons, so the home screen never shows two or none.
            pm.setComponentEnabledSettings(changes.map { (c, s) -> PackageManager.ComponentEnabledSetting(c, s, PackageManager.DONT_KILL_APP) })
        } else {
            // Enable the new one first, so there is never a moment with no launcher entry.
            pm.setComponentEnabledSetting(component(choice), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            changes.filter { it.first != component(choice) }.forEach { (c, s) -> pm.setComponentEnabledSetting(c, s, PackageManager.DONT_KILL_APP) }
        }
    }

    /**
     * After an update that changes the default icon, someone who had picked an icon in Settings would see two
     * (theirs and the new default). Keep only theirs.
     */
    fun tidyUp() {
        // Bloom was removed (24 Oct): a phone that used it moves to Four Seasons on the same background.
        AppIconChoice.retired.entries.firstOrNull { pm.getComponentEnabledSetting(component(it.key)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
            ?.let { (_, replacement) ->
                set(replacement)
                return
            }
        val on = AppIconChoice.all.count(::enabled)
        if (on > 1) set(current())
        // Never leave the phone without a home-screen entry.
        if (on == 0) set(AppIconChoice())
    }
}

/** Appearance choices in SharedPreferences. Not secret, so no encryption. */
private class PrefsAppearanceStore(context: Context) : AppearanceStore {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    override fun load() = AppearanceSettings(
        themeId = prefs.getString("theme", null) ?: AppearanceSettings().themeId,
        blurOn = prefs.getBoolean("blur", true),
        reduceMotion = prefs.getBoolean("reduce_motion", false),
        tabletBottomBar = prefs.getBoolean("tablet_bottom_bar", false),
        // Older versions saved the theme on any change, so only a theme picked since 24 Oct counts as chosen.
        themeChosen = prefs.getBoolean("theme_chosen", false),
    )

    override fun save(settings: AppearanceSettings) {
        prefs.edit()
            .putString("theme", settings.themeId)
            .putBoolean("blur", settings.blurOn)
            .putBoolean("reduce_motion", settings.reduceMotion)
            .putBoolean("tablet_bottom_bar", settings.tabletBottomBar)
            .putBoolean("theme_chosen", settings.themeChosen)
            .apply()
    }
}

/** Hidden albums by album (bucket) ID, Settings > Albums. Not secret. */
private class PrefsHiddenAlbumsStore(context: Context) : com.deepak.photovault.ui.shell.HiddenAlbumsStore {
    private val prefs = context.applicationContext.getSharedPreferences("albums", Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()

    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet("hidden", ids).apply()
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
