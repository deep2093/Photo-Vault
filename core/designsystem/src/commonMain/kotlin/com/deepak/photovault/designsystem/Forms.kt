package com.deepak.photovault.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/** A labelled text field on glass. */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
    onDone: (() -> Unit)? = null,
    /** More than 1 for a box that grows as you write (Send feedback). */
    minLines: Int = 1,
    /**
     * Capital letters as you type (6 Nov): the first letter of each sentence by default, each word for names, none for
     * addresses, usernames and recovery words. Password and number boxes never get them.
     */
    capitals: androidx.compose.ui.text.input.KeyboardCapitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
    /** Given: the box takes the focus (and the keyboard opens) when asked (7 Nov: "Add a name"). */
    focus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val theme = LocalGlassTheme.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = theme.onGlass, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.padding(top = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clip(RoundedCornerShape(16.dp))
                .background(theme.onGlass.copy(alpha = 0.07f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = if (minLines > 1) Alignment.TopStart else Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = minLines == 1,
                minLines = minLines,
                maxLines = if (minLines == 1) 1 else minLines * 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = theme.onGlass),
                cursorBrush = SolidColor(theme.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password && keyboardType == KeyboardType.Text) KeyboardType.Password else keyboardType,
                    autoCorrectEnabled = minLines > 1,
                    capitalization = if (password || keyboardType != KeyboardType.Text) androidx.compose.ui.text.input.KeyboardCapitalization.None else capitals,
                    imeAction = if (minLines == 1) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onDone?.invoke()
                }),
                modifier = Modifier.fillMaxWidth()
                    .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                    .semantics { contentDescription = label },
            )
        }
        if (supporting != null) {
            Text(supporting, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** A glass dialog with a title, content and a row of buttons. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalGlassTheme.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(theme.glassFallback.compositeOver(theme.wallpaper.first()))
                .glassRim(RoundedCornerShape(28.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
            // Buttons wrap onto a second line when they don't fit (10 Nov: three long choices ran off the edge).
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                buttons()
            }
        }
    }
}

/**
 * A settings section that opens and closes (feedback 22 Oct: keep Settings compact). Remembers whether it was
 * open across rotation.
 */
@Composable
fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    summary: String? = null,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, motionSpec())
    Column(modifier.glassPanel(GlassCardShape)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    haptics.tap()
                    open = !open
                }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIcon(icon, tint = theme.accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (summary != null) {
                    Text(summary, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            GlassIcon(Icons.Rounded.ExpandMore, tint = theme.onGlassSecondary, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(motionSpec()) + fadeIn(motionSpec()),
            exit = shrinkVertically(motionSpec()) + fadeOut(motionSpec()),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}

/**
 * A floating sheet (15 Nov): a rounded glass card that rises from the bottom of a phone screen (centred on a tablet),
 * with a grab line, an icon badge, a title with one line under it and a close button. For pop-ups that hold a small
 * page of their own (Remove phone copies), where [GlassDialog]'s plain box looked dated. Tapping outside, the close
 * button and the back gesture all close it. With Reduce motion it appears in place.
 */
@Composable
fun GlassSheet(
    title: String,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalGlassTheme.current
    val still = LocalGlassSettings.current.reduceMotion
    val shape = RoundedCornerShape(32.dp)
    val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown.animateTo(1f, androidx.compose.animation.core.tween(280, easing = PageEasing)) }
    // Pulling the top of the sheet down closes it; let go early and it springs back.
    val pull = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dismissNow by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            val wide = maxWidth > 600.dp
            Column(
                Modifier
                    .align(if (wide) Alignment.Center else Alignment.BottomCenter)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = (1f - shown.value) * 56.dp.toPx() + pull.value
                        alpha = shown.value
                    }
                    .clip(shape)
                    .background(theme.glassFallback.compositeOver(theme.wallpaper.first()))
                    .glassRim(shape)
                    // Taps on the sheet itself stay on the sheet.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (pull.value > 96.dp.toPx()) dismissNow() else scope.launch { pull.animateTo(0f) }
                            },
                            onDragCancel = { scope.launch { pull.animateTo(0f) } },
                        ) { change, dy ->
                            change.consume()
                            val to = (pull.value + dy).coerceAtLeast(0f)
                            scope.launch { pull.snapTo(to) }
                        }
                    },
                ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(theme.onGlass.copy(alpha = 0.22f)),
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(
                            Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(theme.onGlass.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(icon, contentDescription = null, tint = theme.onGlass, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            color = theme.onGlass,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (subtitle != null) {
                            Text(subtitle, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    GlassIconButton(androidx.compose.material.icons.Icons.Rounded.Close, "Close", onClick = onDismiss)
                }
                }
                content()
            }
        }
    }
}

/** A number with a short label under it, in a soft tile; a row of these sums up a page at a glance (15 Nov). */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val theme = LocalGlassTheme.current
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (strong) theme.accent else theme.onGlass.copy(alpha = 0.07f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        Text(
            value,
            color = if (strong) readableOn(theme.accent) else theme.onGlass,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            label,
            color = if (strong) readableOn(theme.accent).copy(alpha = 0.82f) else theme.onGlassSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
