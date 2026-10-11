/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade.ui.composable

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.SwipeSource
import com.android.compose.animation.scene.SwipeSourceDetector
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.lifecycle.LaunchedEffectWithLifecycle
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.TransitionKeys.HorizontalShadeSwipe
import com.android.systemui.scene.ui.viewmodel.SceneContainerArea
import com.android.systemui.shade.ui.ShadeSwipeMotion
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView

/** Per-container regions: gestures are hit-tested against the actual header, not a screen band. */
class ShadeHeaderSwipeRegions {
    var containerCoordinates: LayoutCoordinates? = null
    val headers = mutableMapOf<Any, LayoutCoordinates>()

    fun contains(position: IntOffset): Boolean {
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return false
        val inWindow =
            container.positionInWindow() + Offset(position.x.toFloat(), position.y.toFloat())
        return headers.values.any { it.isAttached && it.boundsInWindow().contains(inWindow) }
    }
}

val LocalShadeHeaderSwipeRegions = staticCompositionLocalOf<ShadeHeaderSwipeRegions?> { null }

class ShadeHeaderSwipeSourceDetector(
    private val delegate: SwipeSourceDetector,
    private val regions: ShadeHeaderSwipeRegions,
) : SwipeSourceDetector {
    override fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): SwipeSource.Resolved? {
        return if (orientation == Orientation.Horizontal && regions.contains(position)) {
            SceneContainerArea.Resolved.ShadeHeader
        } else {
            delegate.source(layoutSize, position, density, orientation)
        }
    }
}

@Composable
fun Modifier.shadeHeaderSwipeRegion(enabled: Boolean): Modifier {
    val regions = LocalShadeHeaderSwipeRegions.current ?: return this
    val token = remember { Any() }
    DisposableEffect(regions, token, enabled) { onDispose { regions.headers.remove(token) } }
    return if (enabled) {
        onGloballyPositioned { regions.headers[token] = it }
    } else {
        this
    }
}

@Composable
fun ContentScope.shadeSwipeItem(order: Int): Modifier {
    if (contentKey != Overlays.QuickSettingsShade) return Modifier
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    return Modifier.graphicsLayer {
        val transition = layoutState.transitionState as? TransitionState.Transition
        translationX =
            if (
                transition?.key == HorizontalShadeSwipe &&
                    transition.isTransitioningBetween(
                        Overlays.NotificationsShade,
                        Overlays.QuickSettingsShade,
                    )
            ) {
                val visible =
                    if (transition.toContent == contentKey) transition.progress
                    else 1f - transition.progress
                direction * 48.dp.toPx() * ShadeSwipeMotion.offsetFraction(visible, order)
            } else {
                0f
            }
    }
}

@Composable
fun ContentScope.BindNotificationShadeSwipe(view: NotificationScrollView) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1f else 1f
    DisposableEffect(view) { onDispose { view.setShadeSwipeProgress(1f, 0f) } }
    LaunchedEffectWithLifecycle(view, direction, layoutState) {
        try {
            snapshotFlow {
                    val transition = layoutState.transitionState as? TransitionState.Transition
                    if (
                        transition?.key == HorizontalShadeSwipe &&
                            transition.isTransitioningBetween(
                                Overlays.NotificationsShade,
                                Overlays.QuickSettingsShade,
                            )
                    ) {
                        if (transition.toContent == Overlays.NotificationsShade) transition.progress
                        else 1f - transition.progress
                    } else {
                        1f
                    }
                }
                .collect { view.setShadeSwipeProgress(it.coerceIn(0f, 1f), direction) }
        } finally {
            view.setShadeSwipeProgress(1f, 0f)
        }
    }
}
