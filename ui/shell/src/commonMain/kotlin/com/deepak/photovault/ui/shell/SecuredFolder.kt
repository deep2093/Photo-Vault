package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel
import kotlinx.coroutines.launch

/** Pure rules of the Secured folder (Phase 8A, 1 Nov), tested. */
object SecuredRules {
    const val PIN_LENGTH = 6
    const val MIN_CODE_CHARS = 12
    const val MIN_CODE_WORDS = 4

    /** Why [pin] can't be used, in plain words, or null when it is fine. */
    fun pinProblem(pin: String): String? = when {
        pin.length != PIN_LENGTH || !pin.all { it.isDigit() } -> "Use $PIN_LENGTH digits."
        pin.toSet().size == 1 -> "Don't use the same digit every time."
        pin in EASY_PINS -> "That is too easy to guess. Pick another."
        else -> null
    }

    private val EASY_PINS = setOf("123456", "654321", "012345", "123123", "121212", "112233", "000111")
    private val EASY_PARTS = listOf("password", "qwerty", "123456", "abcdef", "letmein")

    /** The words of a typed code with single spaces; the same code always gives the same text. */
    fun words(code: String): List<String> = code.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun normalizedCode(code: String): String = words(code).joinToString(" ")

    /** Why [code] is too weak to be a backup code, or null: at least 12 characters, or four different words. */
    fun codeProblem(code: String): String? {
        val text = normalizedCode(code)
        val w = words(text)
        val lower = text.lowercase()
        if (EASY_PARTS.any { it in lower }) return "That is too easy to guess. Pick something of your own."
        val enoughChars = text.length >= MIN_CODE_CHARS && text.toSet().size >= 6
        val enoughWords = w.size >= MIN_CODE_WORDS && w.all { it.length >= 3 } && w.map { it.lowercase() }.toSet().size == w.size
        return if (enoughChars || enoughWords) null else "Use at least $MIN_CODE_CHARS characters, or $MIN_CODE_WORDS different words."
    }
}

/** How long a wrong PIN makes the person wait: nothing for the first four, then longer and longer. */
object PinGuard {
    fun waitMillis(failed: Int): Long = when {
        failed < 5 -> 0L
        failed == 5 -> 30_000L
        failed == 6 -> 60_000L
        failed == 7 -> 300_000L
        failed == 8 -> 900_000L
        else -> 3_600_000L
    }
}

/** The answer to a PIN: [ok], or how long to wait before trying again ([waitMillis] more than zero). */
class PinOutcome(val ok: Boolean, val waitMillis: Long = 0L)

/** A folder being set up: the 24 words to show (empty when the person chose their own code). */
class SecuredSetup(val words: List<String>)

/** What the phone does for the Secured folder (Phase 8A). Android: Keystore, PBKDF2, the fingerprint prompt. */
interface SecuredPlatform {
    val supported: Boolean get() = false
    fun isSetUp(): Boolean = false
    val biometricReady: Boolean get() = false

    /** Makes the folder's key and its recovery (24 words, or [ownCode]). Nothing is kept until [commit]. */
    suspend fun prepare(pin: String, ownCode: String?): SecuredSetup? = null
    fun commit(): Boolean = false
    fun discard() = Unit

    fun tryPin(pin: String): PinOutcome = PinOutcome(false)
    fun askBiometric(onResult: (Boolean) -> Unit) = onResult(false)

    /** Opens the recovery with the typed words or code and sets [newPin]. Null when it worked, else the problem in plain words. */
    suspend fun resetPin(code: String, newPin: String): String? = "Not available on this device."

    fun saveWordsToPasswordManager(words: List<String>, onResult: (Boolean) -> Unit) = onResult(false)

    /** Hides the screen from screenshots and the recent apps list while it is open. */
    fun protectScreen(on: Boolean) = Unit

    // ---- Photos in the folder (slice 2, 1 Nov) ----

    /** What is in the folder, newest first. Null when the list can't be read (never an empty list by mistake). */
    suspend fun items(): List<SecuredItem>? = emptyList()

    /**
     * Encrypts these phone photos into the folder and checks each copy. Returns the ones that are safely stored;
     * the originals are not touched here.
     */
    suspend fun moveIn(items: List<MediaItem>, onProgress: (Int, Int) -> Unit): List<SecuredStored> = emptyList()

    /** The small picture of a locked photo, decrypted. */
    suspend fun thumbnail(id: String): ByteArray? = null

    /** A locked photo, decrypted in memory, for the viewer. */
    suspend fun photo(id: String): ByteArray? = null

    /** Puts photos back in the phone's gallery (Pictures/Pixallery), checks them, then removes them from the folder. Returns how many. */
    suspend fun moveOut(ids: List<String>): Int = 0

    /** Deletes locked photos for good. */
    suspend fun remove(ids: List<String>) = Unit

    /** A locked video decrypted to a short-lived file for playing: its file:// address, or null. Erased by [clearTemp]. */
    suspend fun videoFile(id: String): String? = null

    /** Opens Android's share sheet with these locked photos or videos (decrypted to short-lived files). False if it can't. */
    suspend fun share(ids: List<String>): Boolean = false

    /** Erases the short-lived playing files. Called when the folder is left or locked. */
    fun clearTemp() = Unit

    /** Changes the PIN. Null when it worked, else the problem in plain words. */
    suspend fun changePin(oldPin: String, newPin: String): String? = "Not available on this device."
}

/** A photo in the Secured folder. */
class SecuredItem(
    val id: String,
    val name: String,
    val takenAtMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val isVideo: Boolean = false,
    val durationMillis: Long = 0,
)

/** A phone photo now safely kept in the folder as [id]. */
class SecuredStored(val item: MediaItem, val id: String)

object NoSecuredPlatform : SecuredPlatform

@Stable
class SecuredState(val platform: SecuredPlatform = NoSecuredPlatform) {
    var setUp by mutableStateOf(platform.isSetUp())
    var unlocked by mutableStateOf(false)

    /** Photos picked inside the folder, and the one open in its viewer (so the back gesture closes them first). */
    var selected by mutableStateOf<Set<String>>(emptySet())
    var viewing by mutableStateOf<String?>(null)

    fun lock() {
        unlocked = false
        selected = emptySet()
        viewing = null
        platform.clearTemp()
    }
    fun refresh() { setUp = platform.isSetUp() }
}

/** The Secured folder: set up once, then a PIN (or fingerprint) every time it is opened. */
@Composable
fun SecuredFolderScreen(state: ShellState) {
    val sec = state.secured
    DisposableEffect(Unit) {
        sec.platform.protectScreen(true)
        onDispose {
            sec.platform.protectScreen(false)
            sec.lock()
        }
    }
    when {
        !sec.setUp -> SecuredSetupScreen(state, sec)
        !sec.unlocked -> SecuredLockScreen(state, sec)
        else -> SecuredContentScreen(state, sec)
    }
}

@Composable
internal fun PinEntry(pin: String, onChange: (String) -> Unit, fingerprint: (() -> Unit)? = null) {
    val theme = LocalGlassTheme.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun tick() = haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            repeat(SecuredRules.PIN_LENGTH) { i ->
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (i < pin.length) theme.onGlass else theme.onGlass.copy(alpha = 0.2f)),
                )
            }
        }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("fp", "0", "del"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { key ->
                    Box(
                        Modifier
                            .size(68.dp)
                            .then(if (key == "fp" && fingerprint == null) Modifier else Modifier.clip(CircleShape).glassPanel(CircleShape))
                            .then(
                                when {
                                    key == "fp" && fingerprint == null -> Modifier
                                    key == "fp" -> Modifier.clickable(onClickLabel = "Use fingerprint") { tick(); fingerprint?.invoke() }
                                    key == "del" -> Modifier.clickable(onClickLabel = "Delete the last digit") { if (pin.isNotEmpty()) { tick(); onChange(pin.dropLast(1)) } }
                                    else -> Modifier.clickable { if (pin.length < SecuredRules.PIN_LENGTH) { tick(); onChange(pin + key) } }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            "fp" -> if (fingerprint != null) Icon(Icons.Rounded.Fingerprint, "Fingerprint", tint = theme.onGlass, modifier = Modifier.size(28.dp))
                            "del" -> Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = theme.onGlass, modifier = Modifier.size(24.dp))
                            else -> Text(key, color = theme.onGlass, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecuredLockScreen(state: ShellState, sec: SecuredState) {
    val theme = LocalGlassTheme.current
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var forgot by rememberSaveable { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val buzzScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (sec.platform.biometricReady) sec.platform.askBiometric { ok -> if (ok) sec.unlocked = true }
    }
    if (forgot) {
        SecuredForgotScreen(state, sec, onDone = { forgot = false }, onCancel = { forgot = false })
        return
    }
    GlassListScreen(title = "Secured folder", subtitle = "Locked", onBack = { state.back() }) {
        item {
            GlassCard(Modifier.cardWidth()) {
                Text("Enter your PIN", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                PinEntry(
                    pin = pin,
                    onChange = { next ->
                        pin = next
                        if (next.length == SecuredRules.PIN_LENGTH) {
                            val r = sec.platform.tryPin(next)
                            pin = ""
                            if (r.ok) {
                                sec.unlocked = true
                            } else {
                                // A wrong PIN: three firm buzzes in a row.
                                buzzScope.launch {
                                    repeat(3) {
                                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        kotlinx.coroutines.delay(110)
                                    }
                                }
                                message = if (r.waitMillis > 0) "Too many tries. Wait ${(r.waitMillis + 999) / 1000} seconds." else "That PIN isn't right."
                            }
                        }
                    },
                    fingerprint = if (sec.platform.biometricReady) ({ sec.platform.askBiometric { ok -> if (ok) sec.unlocked = true } }) else null,
                )
                message?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        item { GlassButton("I forgot my PIN", onClick = { forgot = true }) }
    }
}

/** Forgot the PIN: the 24 words (or your own code) open the recovery, then a new PIN is chosen. */
@Composable
private fun SecuredForgotScreen(state: ShellState, sec: SecuredState, onDone: () -> Unit, onCancel: () -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val choosing = first == null
    GlassListScreen(title = "Secured folder", subtitle = "New PIN", onBack = onCancel) {
        item {
            GlassCard(Modifier.cardWidth()) {
                Text("Type your recovery words or your own code", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                GlassTextField(code, { code = it }, "Recovery words or code", minLines = 3)
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                Text(if (choosing) "Choose a new PIN" else "Type the new PIN again", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                PinEntry(pin, onChange = { next ->
                    pin = next
                    if (next.length == SecuredRules.PIN_LENGTH) {
                        val firstPin = first
                        if (firstPin == null) {
                            val bad = SecuredRules.pinProblem(next)
                            if (bad != null) { problem = bad } else { first = next; problem = null }
                            pin = ""
                        } else if (firstPin == next) {
                            if (working) return@PinEntry
                            working = true
                            scope.launch {
                                val failed = sec.platform.resetPin(code, next)
                                working = false
                                if (failed == null) { sec.unlocked = true; onDone() } else { problem = failed; first = null; pin = "" }
                            }
                        } else {
                            problem = "The two PINs are different. Start again."
                            first = null
                            pin = ""
                        }
                    }
                })
                problem?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecuredSetupScreen(state: ShellState, sec: SecuredState) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var chosenPin by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf<SecuredSetup?>(null) }
    var own by remember { mutableStateOf(false) }
    var ownCode by remember { mutableStateOf("") }
    var ownAgain by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    // After a screen turn the typed PIN is gone from memory; start again.
    LaunchedEffect(chosenPin == null && step in 2..3) { if (chosenPin == null && step in 2..3) step = 0 }
    fun leave() { sec.platform.discard(); state.back() }

    GlassListScreen(title = "Secured folder", subtitle = "Step ${step + 1} of 4", onBack = { leave() }) {
        when (step) {
            0 -> {
                item {
                    GlassCard(Modifier.cardWidth()) {
                        Text("Before you start", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        listOf(
                            "Photos you move here are locked with a PIN (or your fingerprint) and scrambled on this phone. They disappear from Photos, Albums and other gallery apps.",
                            "You will choose a PIN and get a recovery code (24 words, or a code of your own). The code is the only way back in if you forget the PIN.",
                            "For now locked photos stay on this phone only; they are not backed up to the drive yet. If you lose the phone, they are gone.",
                        ).forEach { Text("• $it", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
                item { GlassButton("Choose my PIN", primary = true, onClick = { step = 1 }) }
            }
            1 -> {
                item {
                    GlassCard(Modifier.cardWidth()) {
                        Text(if (firstPin == null) "Choose a ${SecuredRules.PIN_LENGTH}-digit PIN" else "Type it again", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        PinEntry(pin, onChange = { next ->
                            pin = next
                            if (next.length == SecuredRules.PIN_LENGTH) {
                                val one = firstPin
                                if (one == null) {
                                    val bad = SecuredRules.pinProblem(next)
                                    if (bad != null) problem = bad else { firstPin = next; problem = null }
                                    pin = ""
                                } else if (one == next) {
                                    chosenPin = next
                                    problem = null
                                    pin = ""
                                    working = true
                                    scope.launch {
                                        setup = sec.platform.prepare(next, null)
                                        working = false
                                        if (setup == null) { problem = "This phone can't make the key."; chosenPin = null; firstPin = null } else step = 2
                                    }
                                } else {
                                    problem = "The two PINs are different. Start again."
                                    firstPin = null
                                    pin = ""
                                }
                            }
                        })
                        problem?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                        if (working) Text("One moment...", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            2 -> {
                item {
                    GlassCard(Modifier.cardWidth()) {
                        if (!own) {
                            Text("Your 24 recovery words", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Write them down in order and keep the paper safe, or save them in your password manager. They are the only way back in if you forget your PIN.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                setup?.words.orEmpty().forEachIndexed { i, w ->
                                    Text("${i + 1}. $w", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 110.dp))
                                }
                            }
                        } else {
                            Text("Your own recovery code", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Use at least ${SecuredRules.MIN_CODE_CHARS} characters, or ${SecuredRules.MIN_CODE_WORDS} different words. A long phrase of your own is best. Anyone who gets the locked key file could try guesses on their own computer, so don't use anything easy.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                            GlassTextField(ownCode, { ownCode = it }, "Your code", password = true)
                            GlassTextField(ownAgain, { ownAgain = it }, "Type it again", password = true)
                        }
                        problem?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                if (!own) {
                    item {
                        GlassCard(Modifier.cardWidth()) {
                            Text("Keep a copy in your password manager?", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                            Text("Your phone may offer Google Password Manager, Samsung Pass or another manager. It can say no; writing them down always works.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                            GlassButton("Save to password manager", onClick = { setup?.words?.let { w -> sec.platform.saveWordsToPasswordManager(w) { ok -> saved = ok } } })
                            saved?.let { Text(if (it) "Saved. Still write them down too." else "Your phone didn't save them. Write them down.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                    item { GlassButton("I've kept my words safe", primary = true, onClick = { step = 3 }) }
                    item { GlassButton("Use my own code instead", onClick = { own = true; problem = null }) }
                } else {
                    item {
                        GlassButton(if (working) "One moment..." else "Use this code", primary = true, onClick = {
                            val bad = SecuredRules.codeProblem(ownCode)
                            when {
                                working -> Unit
                                bad != null -> problem = bad
                                SecuredRules.normalizedCode(ownCode) != SecuredRules.normalizedCode(ownAgain) -> problem = "The two codes are different."
                                else -> scope.launch {
                                    working = true
                                    val made = sec.platform.prepare(chosenPin.orEmpty(), ownCode)
                                    working = false
                                    if (made == null) problem = "This phone couldn't use that code." else { setup = made; problem = null; step = 3 }
                                }
                            }
                        })
                    }
                    item { GlassButton("Back to the 24 words", onClick = { own = false; problem = null; scope.launch { setup = sec.platform.prepare(chosenPin.orEmpty(), null) } }) }
                }
            }
            else -> {
                item {
                    GlassCard(Modifier.cardWidth()) {
                        Text("Almost done", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (own) "Your own code is the only way back in if you forget your PIN. Nobody can reset it, not even the family owner."
                            else "Your 24 words are the only way back in if you forget your PIN. Nobody can reset them, not even the family owner.",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        problem?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                item {
                    GlassButton("Make the Secured folder", primary = true, onClick = {
                        if (sec.platform.commit()) {
                            sec.refresh()
                            sec.unlocked = true
                        } else problem = "The key couldn't be kept on this phone. Nothing was changed."
                    })
                }
            }
        }
    }
}

@Composable
private fun SecuredContentScreen(state: ShellState, sec: SecuredState) = SecuredFolderContent(state, sec)
