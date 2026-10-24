package com.deepak.photovault.ui.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.LocalGlassTheme

/**
 * Pixallery's own delete pop-up (24 Oct), in the app's theme, closed by a back swipe like any pop-up.
 *
 * Android normally asks with its own grey pop-up, which can't be changed. When Pixallery is allowed to "manage
 * media" (asked once), Android lets it delete without that pop-up, so Pixallery asks with this one instead.
 * Without that permission, Android's pop-up is used as before. Nothing is ever deleted without asking.
 */
@Stable
class DeletePrompt {
    internal class Request(val title: String, val body: String, val confirm: String, val cancel: String = "Cancel", val onAnswer: (Boolean) -> Unit)

    internal var request by mutableStateOf<Request?>(null)
        private set

    internal fun answer(yes: Boolean) {
        val r = request ?: return
        request = null
        r.onAnswer(yes)
    }

    /** Move [items] to Recently deleted ("the bin"), asking first. [onDone] gets whether they went. */
    fun trash(actions: MediaActions, items: List<MediaItem>, onDone: (Boolean) -> Unit) {
        if (items.isEmpty()) return
        ask(actions, DeleteText.trash(items), DeleteText.trashBody(items.size), "Move to bin", onDone) {
            actions.moveToRecentlyDeleted(items, onDone)
        }
    }

    /** Delete [items] for good (from Recently deleted), asking first. */
    fun deleteForever(actions: MediaActions, items: List<MediaItem>, onDone: (Boolean) -> Unit) {
        if (items.isEmpty()) return
        ask(actions, DeleteText.forever(items), "This can't be undone.", "Delete", onDone) {
            actions.deleteForever(items, onDone)
        }
    }

    private fun ask(actions: MediaActions, title: String, body: String, confirm: String, onDone: (Boolean) -> Unit, go: () -> Unit) {
        when {
            actions.canDeleteWithoutAsking() -> request = Request(title, body, confirm) { yes -> if (yes) go() else onDone(false) }
            !actions.deletePermissionOffered() -> request = Request(
                title = "Nicer delete pop-ups?",
                body = "Android asks with its own grey pop-up. Allow Pixallery to manage media and it asks with its own " +
                    "pop-up instead, in your theme, and a back swipe cancels. Nothing is deleted without asking.",
                confirm = "Allow",
                cancel = "Not now",
            ) { allow ->
                actions.markDeletePermissionOffered()
                if (allow) {
                    actions.askDeletePermission()
                    onDone(false)
                } else {
                    go()
                }
            }
            else -> go()
        }
    }
}

val LocalDeletePrompt = staticCompositionLocalOf { DeletePrompt() }

/** The pop-up's words (24 Oct, Deepak's wording), tested. */
object DeleteText {
    fun trash(items: List<MediaItem>): String = "Move ${what(items)} to the bin?"

    fun forever(items: List<MediaItem>): String = "Delete ${what(items)} for good?"

    fun trashBody(count: Int): String =
        if (count == 1) "You can put it back from Recently deleted for 30 days." else "You can put them back from Recently deleted for 30 days."

    private fun what(items: List<MediaItem>): String {
        val videos = items.count { it.isVideo }
        return when {
            items.size == 1 -> if (videos == 1) "this video" else "this photo"
            videos == items.size -> "these ${items.size} videos"
            videos == 0 -> "these ${items.size} photos"
            else -> "these ${items.size} items"
        }
    }
}

/** Shows the pop-up when one is waiting. Placed once, above every page. */
@Composable
fun DeletePromptHost(prompt: DeletePrompt) {
    val r = prompt.request ?: return
    val theme = LocalGlassTheme.current
    GlassDialog(
        title = r.title,
        onDismiss = { prompt.answer(false) },
        buttons = {
            GlassButton(r.cancel, onClick = { prompt.answer(false) })
            GlassButton(r.confirm, primary = true, onClick = { prompt.answer(true) })
        },
    ) {
        Text(r.body, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}
