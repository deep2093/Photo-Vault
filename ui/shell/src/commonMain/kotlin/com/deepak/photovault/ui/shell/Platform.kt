package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext

/**
 * The few pieces that differ per platform. Android implements them in androidMain; the paused PC app will
 * add its own when it resumes.
 */

/** Plays a video with standard controls. Released when it leaves the screen. */
@Composable
expect fun VideoPlayer(uri: String, modifier: Modifier = Modifier)

/** The image loader, with the platform's fast thumbnail source for [com.deepak.photovault.core.media.Thumb]. */
expect fun createImageLoader(context: PlatformContext): ImageLoader

/** Main colour of a loaded thumbnail for adaptive themes (ARGB), or null if it can't be read. */
expect fun mainColorOf(image: Image): Int?
