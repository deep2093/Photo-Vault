package com.deepak.photovault

import android.Manifest
import android.content.BroadcastReceiver
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.ui.shell.DriveMedia
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
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import com.deepak.photovault.ui.shell.FeedbackFile
import com.deepak.photovault.ui.shell.FeedbackOutbox
import com.deepak.photovault.backup.BackupGraph
import android.graphics.BitmapFactory
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android host for the shared PhotoVault shell (the same screens are written to run in a PC app later). */
class MainActivity : ComponentActivity(), MediaActions {
    companion object {
        const val EXTRA_OPEN_BACKUP = "open_backup"
        const val EXTRA_OPEN_FAMILY = "open_family"

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

    /** First-run setup after the tour: photos, then notifications, then running in the background (25 Oct). */
    private var firstRunSetup = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshAccess()
        if (firstRunSetup) askNotificationsInSetup()
    }

    /** Android's own confirmation for Recently deleted, restore and delete forever. Nothing to do on return:
     * the library notices the change by itself. */
    /** Told whether the person agreed to Android's delete or restore prompt (the delete animation waits for it). */
    private var consentDone: ((Boolean) -> Unit)? = null
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        consentDone?.invoke(result.resultCode == RESULT_OK)
        consentDone = null
    }

    /** Remote access: pick a WireGuard file, and Android's one-time "allow VPN" question. */
    private val remote: AndroidRemotePlatform by lazy {
        AndroidRemotePlatform(
            com.deepak.photovault.core.vpn.WireGuardManager.get(this),
            lifecycleScope,
            pickFile = { wireGuardPicker.launch(arrayOf("*/*")) },
            askVpnPermission = { vpnPermission.launch(it) },
        )
    }
    private val wireGuardPicker: androidx.activity.result.ActivityResultLauncher<Array<String>> = registerForActivityResult(ActivityResultContracts.OpenDocument()) { remote.onFilePicked(it) }
    private val vpnPermission: androidx.activity.result.ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        remote.onPermissionAnswer(it.resultCode == RESULT_OK)
    }

    /** Invites: the file picker's answer goes to whoever asked (a WireGuard file, or an invite file). */
    private var onTextFilePicked: ((Uri?) -> Unit)? = null
    private val textFilePicker: androidx.activity.result.ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onTextFilePicked?.invoke(uri)
            onTextFilePicked = null
        }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (firstRunSetup) askBatteryInSetup()
    }

    private fun startFirstRunSetup() {
        firstRunSetup = true
        if (gallery.access == MediaAccess.NONE) requestAccess() else askNotificationsInSetup()
    }

    private fun askNotificationsInSetup() {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)
        if (needed) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else askBatteryInSetup()
    }

    private fun askBatteryInSetup() {
        firstRunSetup = false
        val power = getSystemService(PowerManager::class.java)
        // Its own screen with the steps, after the two Android pop-ups (25 Oct).
        if (power?.isIgnoringBatteryOptimizations(packageName) == false) shell.intro.showBackgroundHelp()
    }

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

    /** Waiting for Android's "allow changes" answer before moving photos to another album. */
    private class PendingMove(val items: List<MediaItem>, val destPath: String?, val newName: String?, val done: (String?) -> Unit)
    private var pendingMove: PendingMove? = null

    private val moveLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val move = pendingMove ?: return@registerForActivityResult
        pendingMove = null
        if (result.resultCode != RESULT_OK) {
            move.done("Not moved.")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) { moveItems(move) }
            gallery.refresh()
            move.done(if (failed == 0) null else "$failed of ${move.items.size} couldn't be moved.")
        }
    }

    /** Send feedback: photos or videos to go with a note (23 Oct). */
    private var onFeedbackFiles: ((List<FeedbackFile>) -> Unit)? = null
    private val feedbackPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        val done = onFeedbackFiles ?: return@registerForActivityResult
        onFeedbackFiles = null
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { describeFeedbackFile(it) } }
            done(files)
        }
    }

    private fun describeFeedbackFile(uri: Uri): FeedbackFile? = runCatching {
        var name = "file"
        var size = 0L
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = c.getLong(1)
            }
        }
        FeedbackFile(uri.toString(), name, size, contentResolver.getType(uri)?.startsWith("video/") == true)
    }.getOrNull()

    override fun pickFeedbackFiles(onPicked: (List<FeedbackFile>) -> Unit) {
        onFeedbackFiles = onPicked
        feedbackPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    override fun openFeedbackFile(file: FeedbackFile): com.deepak.photovault.core.drive.ByteSource? =
        runCatching { contentResolver.openInputStream(Uri.parse(file.uri))?.let { com.deepak.photovault.backup.StreamSource(it) } }.getOrNull()

    override val feedbackOutbox: FeedbackOutbox by lazy { com.deepak.photovault.feedback.FileFeedbackOutbox(this) }

    // ---- Own delete pop-up (24 Oct) ----

    override fun canDeleteWithoutAsking(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { MediaStore.canManageMedia(this) }.getOrDefault(false)

    override fun askDeletePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName"))) }
    }

    override fun deletePermissionOffered(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || getSharedPreferences("albums", MODE_PRIVATE).getBoolean("manage_media_offered", false)

    override fun markDeletePermissionOffered() {
        getSharedPreferences("albums", MODE_PRIVATE).edit().putBoolean("manage_media_offered", true).apply()
    }

    override fun lastCrash(): String? = runCatching { CrashNote.file(application).takeIf { it.exists() }?.readText() }.getOrNull()

    override fun clearLastCrash() {
        runCatching { CrashNote.file(application).delete() }
    }

    override val appVersion: String
        get() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

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
        // Just crashed? Show what went wrong instead, so it can be sent (24 Oct).
        if (savedInstanceState == null && CrashNote.shouldShow(application)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        val state = ShellState(PrefsAppearanceStore(this))
        state.intro = com.deepak.photovault.ui.shell.IntroState(PrefsIntroStore(this))
        state.intro.setUpAccess = { startFirstRunSetup() }
        state.intro.openBackgroundSettings = {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.onFailure { openBatterySettings() }
        }
        shell = state
        state.remote = remote.state
        state.encryption = com.deepak.photovault.ui.shell.EncryptionState(AndroidEncryption(this))
        state.invites = AndroidInvites(this) { answer -> onTextFilePicked = answer; textFilePicker.launch(arrayOf("*/*")) }
        state.restoreNavigation(savedInstanceState?.getString("nav"))
        remote.start()
        gallery = GalleryState(MediaStoreLibrary(this), PrefsFavouritesStore(this), AndroidMediaDetails(this), PrefsHiddenAlbumsStore(this), PrefsAlbumLayoutStore(this), FileDriveRecordCache(this, "drive-records.json"), FileDriveRecordCache(this, "family-album.json"))
        drive = DriveState(AndroidDrivePlatform(this))
        // Private backup: the person's key, when they chose Private and have it on this phone.
        val settingsForVault = com.deepak.photovault.backup.BackupSettingsStore(this)
        drive.vaultProvider = { house, me ->
            if (settingsForVault.load().privacy == com.deepak.photovault.core.backup.BackupPrivacy.PRIVATE) PrivateKeys.vault(this, house, me) else null
        }
        // Photos that are only on the drive are fetched through the same connection the rest of the app uses.
        DriveMedia.install(this) { path, sink -> drive.readDriveFile(path, sink) }
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
            // The back gesture pulls a sliding page along with the finger (24 Oct): it stops where the finger stops,
            // then finishes or slides back from there. Anything else (a selection, the photo viewer) closes as before.
            val backScope = rememberCoroutineScope()
            PredictiveBackHandler(enabled = state.canGoBack) { events ->
                val pagePulled = state.canDragBack
                try {
                    events.collect { if (pagePulled) state.dragBack(it.progress, it.swipeEdge == BackEventCompat.EDGE_LEFT) }
                } catch (e: CancellationException) {
                    if (pagePulled) backScope.launch { state.cancelBack() }
                    throw e
                }
                if (pagePulled) backScope.launch { state.finishBack() } else state.back()
            }
            // What's new and the tour wait until the opening animation has finished.
            androidx.compose.runtime.LaunchedEffect(showIntro, introIcon) { if (!(showIntro && introIcon != null)) state.intro.start() }
            Box(Modifier.fillMaxSize()) {
                PhotoVaultApp(state, gallery, this@MainActivity, drive, backup, signals, iconSwitcher)
                if (showIntro && introIcon != null) {
                    OpeningAnimation(
                        icon = introIcon,
                        glyph = glyph,
                        background = if (dark) androidx.compose.ui.graphics.Color(0xFF0B0E14) else androidx.compose.ui.graphics.Color(0xFFF7F9FC),
                        onDone = { showIntro = false; shell.intro.start() },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::shell.isInitialized) outState.putString("nav", shell.saveNavigation())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    /** A backup notification opens the Backup section. */
    /** Another app opened a photo or video with Pixallery: its own viewer; swipe through the folder if it is in the gallery. */
    private fun openExternal(intent: Intent) {
        val uri = intent.data ?: return
        lifecycleScope.launch {
            // The gallery may still be loading when the app was started by this.
            var waited = 0
            while (gallery.items.isEmpty() && gallery.access != MediaAccess.NONE && waited < 30) {
                kotlinx.coroutines.delay(100)
                waited++
            }
            val mediaId = uri.lastPathSegment?.toLongOrNull()?.takeIf { uri.authority == "media" }
            val known = mediaId?.let(gallery::find)
            shell.overlays.clear()
            if (known != null) {
                val sameFolder = gallery.items.filter { it.albumId == known.albumId }.map { it.id }
                shell.open(Overlay.Viewer(sameFolder.ifEmpty { listOf(known.id) }, known.id))
                return@launch
            }
            val type = contentResolver.getType(uri) ?: intent.type.orEmpty()
            val name = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
                }
            }.getOrNull()
            val id = -(uri.toString().hashCode().toLong().and(0x7fffffffL)) - 1_000_000_000L
            gallery.openExternal(
                MediaItem(
                    id = id, uri = uri.toString(),
                    type = if (type.startsWith("video")) com.deepak.photovault.core.media.MediaType.VIDEO else com.deepak.photovault.core.media.MediaType.PHOTO,
                    displayName = name?.first ?: uri.lastPathSegment.orEmpty().ifBlank { "Photo" },
                    takenAtMillis = System.currentTimeMillis(), sizeBytes = name?.second ?: 0L, width = 0, height = 0,
                    albumId = "external", albumName = "Opened from another app", mimeType = type,
                ),
            )
            shell.open(Overlay.Viewer(listOf(id), id))
        }
    }

    /** A file another app opened with Pixallery that isn't a photo: an invite starts the join screen. */
    private fun openInviteFile(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().decodeToString() }
        }.getOrNull()?.trim()
        when {
            text == null || !com.deepak.photovault.core.household.Invite.looksLikeInvite(text) ->
                toast("That file isn't a Pixallery invite.")
            drive.joined != null -> toast("This phone is already in a family.")
            else -> {
                shell.pendingInvite = text
                shell.overlays.clear()
                shell.open(Overlay.DriveSetup)
            }
        }
    }

    override fun openDefaultAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun openFromIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val type = intent.type ?: contentResolver.getType(intent.data!!).orEmpty()
            if (type.startsWith("image/") || type.startsWith("video/")) openExternal(intent) else openInviteFile(intent.data!!)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_FAMILY, false) == true) {
            shell.overlays.clear()
            shell.destination = Destination.FAMILY
        }
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
        remote.onScreenOn()
        // Settings can change while the app is away: "remove animations", or photo access in app info.
        refreshSignals()
        refreshAccess()
    }

    override fun onStop() {
        foreground = false
        // Away from home the VPN is only on while Pixallery is on screen.
        val vpn = com.deepak.photovault.core.vpn.WireGuardManager.get(this)
        if (isFinishing) vpn.onAppClosed() else vpn.onAppBackgrounded()
        vpn.stopMonitoring()
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

    override fun saveOpenedFile(item: com.deepak.photovault.core.media.MediaItem) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                val src = Uri.parse(item.uri)
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName.ifBlank { "Pixallery-${System.currentTimeMillis()}" })
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (item.isVideo) "Movies" else "Pictures") + "/Pixallery")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val table = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val dest = contentResolver.insert(table, values) ?: error("no destination")
                contentResolver.openInputStream(src)!!.use { input -> contentResolver.openOutputStream(dest)!!.use { input.copyTo(it) } }
                contentResolver.update(dest, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }.isSuccess
            runOnUiThread { toast(if (ok) "Saved to Pictures/Pixallery." else "Couldn't save this file.") }
        }
    }

    override fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Photos that are only on the drive have no phone file to hand over yet; say so instead of failing. */
    private fun phoneOnly(all: List<MediaItem>): List<MediaItem> {
        val phone = all.filterNot { it.isDriveOnly }
        return phone
    }

    override fun share(all: List<MediaItem>) {
        val items = phoneOnly(all)
        if (items.isEmpty()) return
        sendToOtherApps(items, items.map { Uri.parse(it.uri) })
    }

    /** Photos only on the drive: fetched to the phone first (a moment for big videos), then shared like any file. */
    override fun shareDrive(items: List<MediaItem>) {
        android.widget.Toast.makeText(this, "Getting the file from the drive…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val uris = items.mapNotNull { item ->
                val path = com.deepak.photovault.core.backup.DriveTimeline.pathOf(item.uri) ?: return@mapNotNull null
                val source = DriveMedia.file(path) ?: return@mapNotNull null
                runCatching {
                    val dir = java.io.File(cacheDir, "drive-shared").apply { mkdirs() }
                    val copy = java.io.File(dir, item.displayName.replace('/', '_'))
                    source.copyTo(copy, overwrite = true)
                    androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", copy)
                }.getOrNull()
            }
            if (uris.isEmpty()) {
                android.widget.Toast.makeText(this@MainActivity, "Couldn't get that from the drive. Check you're on the home Wi-Fi.", android.widget.Toast.LENGTH_LONG).show()
            } else {
                sendToOtherApps(items, uris)
            }
        }
    }

    private fun sendToOtherApps(items: List<MediaItem>, uris: List<Uri>) {
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

    override fun moveToRecentlyDeleted(items: List<MediaItem>, onDone: (Boolean) -> Unit) = askAndroid(phoneOnly(items), onDone) { uris ->
        MediaStore.createTrashRequest(contentResolver, uris, true)
    }

    override fun restore(items: List<MediaItem>) = askAndroid(items, {}) { uris ->
        MediaStore.createTrashRequest(contentResolver, uris, false)
    }

    override fun deleteForever(items: List<MediaItem>, onDone: (Boolean) -> Unit) = askAndroid(phoneOnly(items), onDone) { uris ->
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

    override fun setWallpaper(item: MediaItem, target: com.deepak.photovault.ui.shell.WallpaperTarget, onDone: (String?) -> Unit) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val wm = android.app.WallpaperManager.getInstance(this@MainActivity)
                    if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return@withContext "This phone doesn't allow changing the wallpaper."
                    val source = DriveMedia.pathOf(item.uri)?.let { path ->
                        android.graphics.ImageDecoder.createSource(DriveMedia.file(path) ?: return@withContext "Couldn't fetch the photo from the drive.")
                    } ?: android.graphics.ImageDecoder.createSource(contentResolver, Uri.parse(item.uri))
                    // Upright (the photo's own rotation is applied) and no bigger than the wallpaper needs.
                    val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        val longSide = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                        if (longSide > 4096) {
                            val scale = 4096f / longSide
                            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                        }
                    }
                    val flags = when (target) {
                        com.deepak.photovault.ui.shell.WallpaperTarget.HOME -> android.app.WallpaperManager.FLAG_SYSTEM
                        com.deepak.photovault.ui.shell.WallpaperTarget.LOCK -> android.app.WallpaperManager.FLAG_LOCK
                        com.deepak.photovault.ui.shell.WallpaperTarget.BOTH -> android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
                    }
                    wm.setBitmap(bitmap, null, true, flags)
                    null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Couldn't set the wallpaper."
                }
            }
            onDone(error)
        }
    }

    private fun cacheFolders() = listOfNotNull(cacheDir, externalCacheDir)

    override fun appCacheBytes(): Long = cacheFolders().sumOf { root -> root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Empties the cache folders but keeps the folders themselves, so the code that writes into them keeps working. */
    override fun clearAppCache() {
        cacheFolders().forEach { root ->
            root.listFiles()?.forEach { child ->
                if (child.isDirectory) child.listFiles()?.forEach { runCatching { it.deleteRecursively() } } else runCatching { child.delete() }
            }
        }
    }

    override fun moveToAlbum(items: List<MediaItem>, albumId: String?, newAlbumName: String?, onDone: (String?) -> Unit) {
        val phone = items.filter { DriveMedia.pathOf(it.uri) == null }
        if (phone.isEmpty()) return onDone("Photos that are only on the drive can't be moved.")
        val destination = albumId?.let { id -> gallery.items.firstOrNull { it.albumId == id && DriveMedia.pathOf(it.uri) == null } }
        val destPath = destination?.let { d ->
            contentResolver.query(Uri.parse(d.uri), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }
        if (albumId != null && destPath == null) return onDone("Couldn't find that album's folder.")
        val todo = phone.filter { albumId == null || it.albumId != albumId }
        if (todo.isEmpty()) return onDone(null)
        pendingMove = PendingMove(todo, destPath, newAlbumName, onDone)
        val request = MediaStore.createWriteRequest(contentResolver, todo.map { Uri.parse(it.uri) })
        moveLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    /** Photos may live under DCIM or Pictures, videos under DCIM or Movies; a folder outside that moves to the right top folder. Returns how many failed. */
    private fun moveItems(move: PendingMove): Int {
        var failed = 0
        for (item in move.items) {
            val allowedRoot = if (item.isVideo) "Movies" else "Pictures"
            val target = when {
                move.newName != null -> "$allowedRoot/${move.newName}/"
                else -> {
                    val path = move.destPath!!.trimEnd('/')
                    val root = path.substringBefore('/')
                    val rest = path.substringAfter('/', "")
                    if (root == "DCIM" || root == allowedRoot) "$path/" else "$allowedRoot/${rest.ifEmpty { root }}/"
                }
            }
            val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }
            try {
                if (contentResolver.update(Uri.parse(item.uri), values, null, null) < 1) failed++
            } catch (e: Exception) {
                failed++
            }
        }
        return failed
    }

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
        lensGlass = prefs.getBoolean("lens_glass", false),
        pinBottomBar = prefs.getBoolean("pin_bottom_bar", false),
    )

    override fun save(settings: AppearanceSettings) {
        prefs.edit()
            .putString("theme", settings.themeId)
            .putBoolean("blur", settings.blurOn)
            .putBoolean("reduce_motion", settings.reduceMotion)
            .putBoolean("tablet_bottom_bar", settings.tabletBottomBar)
            .putBoolean("theme_chosen", settings.themeChosen)
            .putBoolean("lens_glass", settings.lensGlass)
            .putBoolean("pin_bottom_bar", settings.pinBottomBar)
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

/**
 * Album order and covers (24 Oct). Order is one line of album IDs; covers are "albumId=id,id,id" lines. Album IDs
 * are MediaStore bucket IDs (numbers) or the two card names, so they never contain the separators.
 */
private class PrefsAlbumLayoutStore(context: Context) : com.deepak.photovault.ui.shell.AlbumLayoutStore {
    private val prefs = context.applicationContext.getSharedPreferences("albums", Context.MODE_PRIVATE)

    override fun loadOrder(): List<String> = prefs.getString("order", "").orEmpty().split('\n').filter { it.isNotBlank() }

    override fun saveOrder(ids: List<String>) {
        prefs.edit().putString("order", ids.joinToString("\n")).apply()
    }

    override fun loadCovers(): Map<String, List<Long>> =
        prefs.getString("covers", "").orEmpty().split('\n').mapNotNull { line ->
            val id = line.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to line.substringAfter('=').split(',').mapNotNull { it.trim().toLongOrNull() }
        }.toMap()

    override fun saveCovers(covers: Map<String, List<Long>>) {
        prefs.edit().putString("covers", covers.entries.joinToString("\n") { (id, photos) -> "$id=${photos.joinToString(",")}" }).apply()
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

/** The drive's backed-up list as last read, so Photos still shows drive-only photos with no connection. */
private class FileDriveRecordCache(context: Context, private val name: String) : com.deepak.photovault.core.backup.DriveRecordCache {
    private val file = java.io.File(context.applicationContext.filesDir, "backup/$name")
    private val serializer = kotlinx.serialization.builtins.ListSerializer(com.deepak.photovault.core.backup.BackupRecord.serializer())

    override fun load(): List<com.deepak.photovault.core.backup.BackupRecord> =
        runCatching { com.deepak.photovault.backup.backupJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    override fun save(records: List<com.deepak.photovault.core.backup.BackupRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = java.io.File(file.parentFile, "$name.tmp")
            temp.writeText(com.deepak.photovault.backup.backupJson.encodeToString(serializer, records))
            temp.renameTo(file)
        }
    }
}

/**
 * What the intro has shown. Someone whose phone already had settings or a drive connection before the intro existed
 * counts as an existing user: no Welcome tour for them, only What's new.
 */
private class PrefsIntroStore(private val context: Context) : com.deepak.photovault.ui.shell.IntroStore {
    private val prefs = context.applicationContext.getSharedPreferences("intro", Context.MODE_PRIVATE)

    override fun seenId() = prefs.getString("seen_id", "").orEmpty()

    override fun tourDone() = prefs.getBoolean("tour_done", false)

    override fun save(seenId: String, tourDone: Boolean) {
        prefs.edit().putString("seen_id", seenId).putBoolean("tour_done", tourDone).apply()
    }

    override fun existingUser(): Boolean {
        val app = context.applicationContext
        return app.getSharedPreferences("appearance", Context.MODE_PRIVATE).all.isNotEmpty() ||
            app.getSharedPreferences("backup", Context.MODE_PRIVATE).all.isNotEmpty() ||
            app.getSharedPreferences("albums", Context.MODE_PRIVATE).all.isNotEmpty()
    }
}
