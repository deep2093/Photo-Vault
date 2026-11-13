package com.deepak.photovault.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A soft shape that stands in for something still loading (13 Nov), instead of a line of text like "Reading the
 * drive...". It breathes slowly in the theme's own colour; with Reduce motion it stays still.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val theme = LocalGlassTheme.current
    val still = LocalGlassSettings.current.reduceMotion
    if (still) {
        Box(modifier.clip(shape).background(theme.onGlass.copy(alpha = 0.12f)))
    } else {
        // The breathing is read while drawing only, so a page of placeholders costs almost nothing.
        val pulse = rememberInfiniteTransition(label = "skeleton")
        val a = pulse.animateFloat(0.07f, 0.17f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeletonAlpha")
        val ink = theme.onGlass
        Box(modifier.clip(shape).drawBehind { drawRect(ink, alpha = a.value) })
    }
}

/** A few placeholder rows (a round picture and two lines), for a list that is still loading. [label] is read aloud. */
@Composable
fun SkeletonRows(count: Int = 3, modifier: Modifier = Modifier, label: String = "Loading") {
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(count) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Skeleton(Modifier.size(40.dp), CircleShape)
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(Modifier.width(if (i % 2 == 0) 160.dp else 120.dp).height(14.dp))
                    Skeleton(Modifier.width(if (i % 2 == 0) 96.dp else 140.dp).height(10.dp))
                }
            }
        }
    }
}

/** A short placeholder for one line of text or a number still being worked out. */
@Composable
fun SkeletonLine(width: Dp = 72.dp, height: Dp = 12.dp, label: String = "Loading") {
    Skeleton(Modifier.width(width).height(height).clearAndSetSemantics { contentDescription = label })
}

/**
 * The one look for "nothing here yet" inside a page (13 Nov): a glass card with a soft round icon, a title, a short
 * line and, when there is something to do about it, a button.
 */
@Composable
fun EmptyCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val theme = LocalGlassTheme.current
    GlassCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(theme.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = theme.accent, modifier = Modifier.size(28.dp))
            }
            Text(
                title,
                color = theme.onGlass,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                body,
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp),
            )
            if (action != null) {
                Box(Modifier.padding(top = 14.dp)) { action() }
            }
        }
    }
}
