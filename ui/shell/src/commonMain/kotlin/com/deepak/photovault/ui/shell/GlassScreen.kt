package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import com.deepak.photovault.designsystem.GlassIconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.LocalHazeState
import com.deepak.photovault.designsystem.glassChrome
import dev.chrisbanes.haze.hazeSource

/**
 * A screen: scrolling content under a floating glass top bar. The content is the blur source, and the top
 * bar and navigation bar are drawn outside it, so they can blur whatever scrolls beneath them.
 *
 * [content] receives the padding it must apply so nothing hides under the bars.
 */
@Composable
fun GlassScreen(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val insets = LocalScreenInsets.current
    val haze = LocalHazeState.current
    Box(Modifier.fillMaxSize()) {
        val contentPadding = PaddingValues(
            start = insets.calculateStartPadding(LayoutDirection.Ltr) + 16.dp,
            end = insets.calculateEndPadding(LayoutDirection.Ltr) + 16.dp,
            top = insets.calculateTopPadding() + TopBarHeight + 24.dp,
            bottom = insets.calculateBottomPadding() + 24.dp,
        )
        Box(Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier)) {
            content(contentPadding)
        }
        // A soft fade behind the status bar, so the clock and icons stay readable over scrolling photos.
        val theme = LocalGlassTheme.current
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(insets.calculateTopPadding() + 20.dp)
                .background(Brush.verticalGradient(listOf(theme.glassFallback, theme.glassFallback.copy(alpha = 0f)))),
        )
        GlassTopBar(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = insets.calculateStartPadding(LayoutDirection.Ltr) + 12.dp,
                    end = insets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp,
                    top = insets.calculateTopPadding() + 12.dp,
                ),
        )
    }
}

@Composable
private fun GlassTopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier,
) {
    val theme = LocalGlassTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = TopBarHeight)
            .glassChrome(RoundedCornerShape(28.dp), elevation = 12.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = theme.onGlass,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** A vertically scrolling list screen (Backup, Family, Settings). Cards are capped in width on wide windows. */
@Composable
fun GlassListScreen(title: String, subtitle: String?, onBack: (() -> Unit)? = null, items: LazyListScope.() -> Unit) {
    GlassScreen(title, subtitle, onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = items,
        )
    }
}

/** Width cap for cards on tablets and PC windows, so lines stay readable. */
internal val CardMaxWidth = 720.dp

internal fun Modifier.cardWidth(): Modifier = this.widthIn(max = CardMaxWidth).fillMaxWidth()
