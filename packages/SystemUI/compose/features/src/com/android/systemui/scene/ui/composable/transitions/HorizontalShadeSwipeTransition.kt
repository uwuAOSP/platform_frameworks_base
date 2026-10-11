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

package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.UserActionDistance
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.notifications.ui.composable.NotificationsShade
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.shade.ui.composable.OverlayShade

fun TransitionBuilder.horizontalShadeSwipeTransition(isRtl: Boolean) {
    val direction = if (isRtl) -1f else 1f
    intrinsicDirection = SwipeDirection.Start
    spec = spring(dampingRatio = 0.85f, stiffness = 500f)
    distance = UserActionDistance { fromContent, _, _ ->
        minOf((fromContent.targetSize()?.width ?: 0) * 0.4f, 240.dp.toPx())
    }

    // Keep the shared scrim steady; only the two panels and their contents change.
    sharedElement(OverlayShade.Elements.Scrim, enabled = true)
    sharedElement(Notifications.Elements.StackPlaceholder, enabled = false)

    translate(NotificationsShade.Elements.Panel, x = (-64).dp * direction)
    translate(QuickSettingsShade.Elements.Panel, x = 64.dp * direction)
    translate(NotificationsShade.Elements.StatusBar, x = (-24).dp * direction)
    translate(QuickSettingsShade.Elements.StatusBar, x = 24.dp * direction)
    fractionRange(end = 0.6f) {
        fade(NotificationsShade.Elements.Panel)
        fade(NotificationsShade.Elements.StatusBar)
        fade(Notifications.Elements.StackPlaceholder)
    }
    fractionRange(start = 0.15f) {
        fade(QuickSettingsShade.Elements.Panel)
        fade(QuickSettingsShade.Elements.StatusBar)
    }
}
