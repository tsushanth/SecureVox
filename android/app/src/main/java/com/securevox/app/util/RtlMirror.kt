package com.securevox.app.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.Composable

/**
 * Flips a directional icon (back arrow, forward chevron) in right-to-left layouts such as Arabic.
 * The pinned Compose icon set predates Icons.AutoMirrored, so this does the same job.
 */
@Composable
fun Modifier.mirrorInRtl(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) this.graphicsLayer(scaleX = -1f) else this
