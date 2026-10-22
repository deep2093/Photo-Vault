package com.deepak.photovault.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
) {
    val theme = LocalGlassTheme.current
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
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = theme.onGlass),
                cursorBrush = SolidColor(theme.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password && keyboardType == KeyboardType.Text) KeyboardType.Password else keyboardType,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
        if (supporting != null) {
            Text(supporting, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** A glass dialog with a title, content and a row of buttons. */
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
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
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
