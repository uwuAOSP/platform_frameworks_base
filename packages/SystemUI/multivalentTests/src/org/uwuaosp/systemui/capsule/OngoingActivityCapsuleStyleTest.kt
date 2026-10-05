/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.capsule

import android.content.res.Resources
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class OngoingActivityCapsuleStyleTest : SysuiTestCase() {
    @Test
    fun compactWidths_followHeightInsteadOfFixedFortyDp() {
        val main = OngoingActivityCapsuleStyle.metrics(28.dp, null, true, context.resources)
        val rear = OngoingActivityCapsuleStyle.metrics(28.dp, null, false, context.resources)
        assertThat(main.compactWidth.value).isWithin(0.001f).of(28f)
        assertThat(rear.compactWidth.value).isWithin(0.001f).of(31f)
        assertThat(OngoingActivityCapsuleStyle.metrics(42.dp, null, true, context.resources).compactWidth.value)
            .isWithin(0.001f).of(42f)
    }

    @Test
    fun unknownSingleColorIcon_doesNotAssumeScreenRecordingViewport() {
        val icon = OngoingActivityChipModel.ChipIcon.SingleColorIcon(Icon.Resource(R.drawable.ic_close, null))
        val metrics = OngoingActivityCapsuleStyle.metrics(28.dp, icon, true, context.resources)
        assertThat(metrics.iconSize.value).isWithin(0.001f).of(18f)
    }

    @Test
    fun screenRecordingIcon_usesItsResourceCompensation() {
        val icon = OngoingActivityChipModel.ChipIcon.SingleColorIcon(Icon.Resource(R.drawable.ic_screenrecord, null))
        val metrics = OngoingActivityCapsuleStyle.metrics(28.dp, icon, true, context.resources)
        val scale = context.resources.getFraction(R.fraction.ongoing_activity_capsule_screenrecord_icon_scale, 1, 1)
        assertThat(metrics.iconSize.value).isWithin(0.001f).of(18f * scale)
        assertThat(metrics.compactWidth).isAtLeast(metrics.iconSize + metrics.sidePadding * 2)
    }

    @Test
    fun notificationIcon_compensationFollowsDimensionResources() {
        val resources = mock<Resources>()
        whenever(resources.getDimension(R.dimen.ongoing_activity_chip_icon_size)).thenReturn(20f)
        whenever(resources.getDimension(R.dimen.ongoing_activity_chip_embedded_padding_icon_size)).thenReturn(30f)
        val icon = OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon(
            notificationKey = "notification", contentDescription = ContentDescription.Loaded("Notification"),
        )
        val metrics = OngoingActivityCapsuleStyle.metrics(28.dp, icon, true, resources)
        assertThat(metrics.iconSize.value).isWithin(0.001f).of(27f)
        assertThat(metrics.sidePadding.value).isWithin(0.001f).of(2f)
        assertThat(metrics.compactWidth.value).isWithin(0.001f).of(31f)
    }

    @Test
    fun defaultAutoCollapseDelay_isThreeSeconds() {
        assertThat(OngoingActivityCapsuleStyle.AUTO_COLLAPSE_DELAY_MILLIS).isEqualTo(3_000L)
    }
}
