package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.LocalGlassTheme
import kotlin.math.roundToInt

/**
 * Crop a one-photo album cover (31 Oct): the whole photo with a frame in the card's shape. Drag to move the frame,
 * pinch or drag the corner dot to resize it. Apply keeps it; Cancel leaves the cover as it was.
 */
@Composable
fun CoverCropScreen(page: Overlay.CropCover, state: ShellState, gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    val item = remember(page.photoId, gallery.items) { gallery.find(page.photoId) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // The photo's own size, learned when it loads (the picture is already turned the right way up).
    var photoW by remember { mutableFloatStateOf(0f) }
    var photoH by remember { mutableFloatStateOf(0f) }
    // The frame, in photo pixels.
    var left by remember { mutableFloatStateOf(0f) }
    var top by remember { mutableFloatStateOf(0f) }
    var side by remember { mutableFloatStateOf(0f) }

    val ready = photoW > 0f && photoH > 0f && area.width > 0 && area.height > 0
    val fit = if (ready) minOf(area.width / photoW, area.height / photoH) else 1f
    val shownW = photoW * fit
    val shownH = photoH * fit
    val originX = (area.width - shownW) / 2f
    val originY = (area.height - shownH) / 2f
    val fitNow by rememberUpdatedState(fit)

    fun place(l: Float, t: Float, s: Float) {
        val (cl, ct, cs) = CoverCrop.clamp(l, t, s, photoW, photoH)
        left = cl
        top = ct
        side = cs
    }

    GlassScreen(
        title = "Crop cover",
        subtitle = page.title,
        onBack = { state.back() },
        pinned = true,
        bottomActions = {
            GlassButton("Cancel", onClick = { state.back() })
            GlassButton("Apply", primary = true, onClick = {
                if (ready && side > 0f) {
                    gallery.setCover(page.cardId, listOf(page.photoId), CoverCrop.toCover(left, top, side, photoW, photoH))
                } else {
                    gallery.setCover(page.cardId, listOf(page.photoId))
                }
                state.back()
            })
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = 64.dp)
                .onSizeChanged { area = it }
                .pointerInput(ready) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (photoW <= 0f) return@detectTransformGestures
                        val k = fitNow
                        val grown = side * zoom
                        // Pinching resizes about the middle of the frame; dragging moves it.
                        place(left + pan.x / k - (grown - side) / 2f, top + pan.y / k - (grown - side) / 2f, grown)
                    }
                },
        ) {
            if (item != null) {
                AsyncImage(
                    model = Thumb(item.uri, false, 1024),
                    contentDescription = "Photo to crop",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { success ->
                        val size = success.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f && photoW <= 0f) {
                            photoW = size.width
                            photoH = size.height
                            // Start on the middle square, which is what the card shows now.
                            val s = minOf(size.width, size.height)
                            left = (size.width - s) / 2f
                            top = (size.height - s) / 2f
                            side = s
                        }
                    },
                )
            }
            if (ready && side > 0f) {
                val fl = originX + left * fit
                val ft = originY + top * fit
                val fs = side * fit
                Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                    drawRect(Color.Black.copy(alpha = 0.55f))
                    val radius = CornerRadius(fs * 0.09f)
                    drawRoundRect(Color.Black, topLeft = Offset(fl, ft), size = Size(fs, fs), cornerRadius = radius, blendMode = BlendMode.Clear)
                    // White line with a dark edge, so it shows on light and dark photos and in every theme.
                    drawRoundRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(fl, ft), size = Size(fs, fs), cornerRadius = radius, style = Stroke(width = 5.dp.toPx()))
                    drawRoundRect(Color.White, topLeft = Offset(fl, ft), size = Size(fs, fs), cornerRadius = radius, style = Stroke(width = 2.5.dp.toPx()))
                    for (n in 1..2) {
                        val d = fs * n / 3f
                        drawLine(Color.White.copy(alpha = 0.6f), Offset(fl + d, ft), Offset(fl + d, ft + fs), strokeWidth = 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = 0.6f), Offset(fl, ft + d), Offset(fl + fs, ft + d), strokeWidth = 1.dp.toPx())
                    }
                }
                // The corner dot: drag it to make the frame bigger or smaller (the top-left corner stays put).
                Box(
                    Modifier
                        .offset { IntOffset((fl + fs).roundToInt() - 14.dp.roundToPx(), (ft + fs).roundToInt() - 14.dp.roundToPx()) }
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
                        .pointerInput(ready) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val k = fitNow
                                place(left, top, side + (drag.x + drag.y) / 2f / k)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {}
            }
        }
    }
}
