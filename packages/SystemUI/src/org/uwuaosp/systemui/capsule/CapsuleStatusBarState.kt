/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.capsule

import android.graphics.Rect
import android.view.View
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.pipeline.shared.ui.model.ChipsVisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Optional host bridge. The native HomeStatusBarViewModel contract stays unchanged. */
interface CapsuleStatusBarHost {
    val capsuleState: CapsuleStatusBarState
}

/** Presentation-only state; native chip content, ranking and actions are not rewritten. */
class CapsuleStatusBarState(
    activities: Flow<MultipleOngoingActivityChipsModel>,
    canShow: Flow<Boolean>,
    shouldShowStatusBar: Flow<Boolean>,
    clockAllowed: Flow<VisibilityModel>,
    notificationIconsAllowed: Flow<VisibilityModel>,
) {
    val chips: Flow<ChipsVisibilityModel> =
        combine(activities, canShow) { chips, allowed -> ChipsVisibilityModel(chips, allowed) }
    val ongoingActivityChipBounds = MutableStateFlow(Rect())
    val isLyricStarted = MutableStateFlow(false)
    val isAnyChipVisible = chips.map { it.areChipsAllowed && it.chips.active.any { !it.isHidden } }
    val isClockVisible: Flow<VisibilityModel> =
        combine(shouldShowStatusBar, clockAllowed, isAnyChipVisible, isLyricStarted,
            ongoingActivityChipBounds) { statusBar, allowed, chip, lyric, bounds ->
            VisibilityModel(if (statusBar && allowed.visibility == View.VISIBLE &&
                !((chip || !bounds.isEmpty) && lyric)) View.VISIBLE else View.INVISIBLE,
                allowed.shouldAnimateChange)
        }
    val isNotificationIconContainerVisible: Flow<VisibilityModel> =
        combine(shouldShowStatusBar, notificationIconsAllowed, isAnyChipVisible,
            ongoingActivityChipBounds) { statusBar, allowed, chip, bounds ->
            VisibilityModel(if (statusBar && allowed.visibility == View.VISIBLE && !chip && bounds.isEmpty)
                View.VISIBLE else View.GONE, allowed.shouldAnimateChange)
        }
    val isLyricVisible: Flow<VisibilityModel> =
        combine(shouldShowStatusBar, notificationIconsAllowed, isAnyChipVisible,
            ongoingActivityChipBounds) { statusBar, allowed, chip, bounds ->
            VisibilityModel(if (statusBar && allowed.visibility == View.VISIBLE && (!chip || !bounds.isEmpty))
                View.VISIBLE else View.GONE, false)
        }

    fun onOngoingActivityChipBoundsChanged(bounds: Rect) {
        ongoingActivityChipBounds.value = Rect(bounds)
    }

    fun onLyricStartedChanged(started: Boolean) {
        isLyricStarted.value = started
    }
}
