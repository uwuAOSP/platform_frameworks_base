/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.capsule

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.unit.Dp
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import kotlin.math.roundToInt

/** Shared geometry tokens from Frame 3.svg, in design units rather than device pixels. */
internal object OngoingActivityCapsuleStyle {
    private const val CAMERA_DIAMETER = 24f
    private const val OUTER_HEIGHT = 28f
    private const val ICON_CONTENT_HEIGHT = 18f
    private const val SIDE_PADDING = 5f
    private const val PADDED_ICON_SIDE_PADDING = 2f
    private const val REAR_SEGMENT_WIDTH = 31f

    const val WIDTH_ANIMATION_DURATION_MILLIS = 180
    const val AUTO_COLLAPSE_DELAY_MILLIS = 3_000L

    fun heightForCamera(cameraHeight: Int): Int =
        (cameraHeight * OUTER_HEIGHT / CAMERA_DIAMETER).roundToInt()

    fun iconContentSize(height: Dp): Dp = height * (ICON_CONTENT_HEIGHT / OUTER_HEIGHT)

    fun metrics(
        height: Dp,
        icon: OngoingActivityChipModel.ChipIcon?,
        cameraAdjacent: Boolean,
        resources: Resources,
    ): CapsuleChipMetrics {
        val hasEmbeddedPadding = icon?.hasEmbeddedPadding == true
        val iconScale =
            if (hasEmbeddedPadding) {
                resources.getDimension(R.dimen.ongoing_activity_chip_embedded_padding_icon_size) /
                    resources.getDimension(R.dimen.ongoing_activity_chip_icon_size)
            } else if (
                icon is OngoingActivityChipModel.ChipIcon.SingleColorIcon &&
                    (icon.impl as? Icon.Resource)?.resId == R.drawable.ic_screenrecord
            ) {
                // Only this known vector has a verified 20/24 visible-content viewport.
                resources.getFraction(R.fraction.ongoing_activity_capsule_screenrecord_icon_scale, 1, 1)
            } else {
                1f
            }
        val iconSize = iconContentSize(height) * iconScale
        val sidePadding =
            height *
                ((if (hasEmbeddedPadding) PADDED_ICON_SIDE_PADDING else SIDE_PADDING) / OUTER_HEIGHT)
        return CapsuleChipMetrics(
            iconSize = iconSize,
            sidePadding = sidePadding,
            compactWidth = maxOf(
                iconSize + sidePadding * 2,
                height * (if (cameraAdjacent) 1f else REAR_SEGMENT_WIDTH / OUTER_HEIGHT),
            ),
        )
    }
}

internal data class CapsuleChipMetrics(val iconSize: Dp, val sidePadding: Dp, val compactWidth: Dp)

/** Three seconds by default; accessibility services may request more time to use the controls. */
@Composable
internal fun ongoingActivityCapsuleTimeoutMillis(): Long =
    LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = OngoingActivityCapsuleStyle.AUTO_COLLAPSE_DELAY_MILLIS,
        containsIcons = true,
        containsText = true,
        containsControls = true,
    )?.coerceAtLeast(OngoingActivityCapsuleStyle.AUTO_COLLAPSE_DELAY_MILLIS)
        ?: OngoingActivityCapsuleStyle.AUTO_COLLAPSE_DELAY_MILLIS
