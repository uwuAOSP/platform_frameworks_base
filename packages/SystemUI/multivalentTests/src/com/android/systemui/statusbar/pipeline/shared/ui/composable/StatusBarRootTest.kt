/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.pipeline.shared.ui.composable

import android.graphics.Rect
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.flags.Flags as ViewFlags
import android.widget.FrameLayout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.clock.ui.viewmodel.clockViewModelFactory
import org.uwuaosp.systemui.capsule.*
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.plugins.fakeDarkIconDispatcher
import com.android.systemui.scene.ui.view.mockShadeRootView
import com.android.systemui.statusbar.events.domain.interactor.systemStatusEventAnimationInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.connectedDisplaysStatusBarNotificationIconViewStoreFactory
import com.android.systemui.statusbar.phone.ui.DarkIconManager
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.phone.ui.statusBarIconController
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.displayAwareHeadlineViewModelImplFactory
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.defaultDisplayHomeStatusBarViewModelFactory
import com.android.systemui.statusbar.ui.viewmodel.statusBarRegionSamplingViewModelFactory
import com.android.systemui.testKosmosNew
import com.android.wm.shell.scrolltotop.fakeScrollToTop
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@DisableFlags(StatusBarMobileIconKairos.FLAG_NAME)
class StatusBarRootTest : SysuiTestCase() {
    @get:Rule val composeTestRule = createComposeRule()
    private val kosmos = testKosmosNew()
    private val darkIconManagerFactory = mock<DarkIconManager.Factory>()
    private val tintedIconManagerFactory = mock<TintedIconManager.Factory>()
    private val homeStatusBarViewBinder = mock<HomeStatusBarViewBinder>()

    @Before
    fun setUp() {
        whenever(darkIconManagerFactory.create(any(), any(), any())).thenReturn(mock())
        whenever(tintedIconManagerFactory.create(any(), any())).thenReturn(mock())
    }

    @Test
    @EnableFlags(ViewFlags.FLAG_SCROLL_TO_TOP, Flags.FLAG_STATUS_BAR_EVENT_FORWARDING_MODERNIZATION, Flags.FLAG_SCENE_CONTAINER)
    fun tap_triggersScrollToTop() {
        setContent()
        composeTestRule.onNodeWithTag(STATUS_BAR_ROOT_TAG).performClick()
        assertThat(kosmos.fakeScrollToTop.lastScrollToTopDisplayId).isEqualTo(context.displayId)
    }

    @Test
    @EnableFlags(ViewFlags.FLAG_SCROLL_TO_TOP, Flags.FLAG_STATUS_BAR_EVENT_FORWARDING_MODERNIZATION, Flags.FLAG_SCENE_CONTAINER)
    fun swipeDown_doesNotTriggerScrollToTop() {
        setContent()
        composeTestRule.onNodeWithTag(STATUS_BAR_ROOT_TAG).performTouchInput { swipeDown() }
        assertThat(kosmos.fakeScrollToTop.lastScrollToTopDisplayId).isNull()
    }

    @Test
    @EnableFlags(ViewFlags.FLAG_SCROLL_TO_TOP, Flags.FLAG_STATUS_BAR_EVENT_FORWARDING_MODERNIZATION, Flags.FLAG_SCENE_CONTAINER)
    fun longClick_doesNotTriggerScrollToTop() {
        setContent()
        composeTestRule.onNodeWithTag(STATUS_BAR_ROOT_TAG).performTouchInput { longClick() }
        assertThat(kosmos.fakeScrollToTop.lastScrollToTopDisplayId).isNull()
    }

    @Test
    fun capsuleSlot_cameraLeftEdgeStaysFixedDuringWidthChanges() {
        val slot = slot(camera = Rect(450, 10, 490, 50))
        assertThat(slot.cameraOnRight).isTrue()
        assertThat(capsuleContentLeft(slot, 40) + 40).isEqualTo(447)
        assertThat(capsuleContentLeft(slot, 140) + 140).isEqualTo(447)
    }

    @Test
    fun capsuleSlot_leftCameraUsesRightSide() {
        val slot = slot(camera = Rect(120, 10, 160, 50))
        assertThat(slot.cameraOnRight).isFalse()
        assertThat(capsuleContentLeft(slot, 40)).isEqualTo(163)
        assertThat(capsuleContentLeft(slot, 140)).isEqualTo(163)
    }

    @Test
    fun capsuleSlot_noCameraCentersMeasuredGroup() {
        val slot = slot()
        assertThat(slot.cameraOnRight).isNull()
        assertThat(capsuleContentLeft(slot, 80)).isEqualTo(460)
        assertThat(capsuleContentLeft(slot, 200)).isEqualTo(400)
    }

    @Test
    fun capsuleSlot_neverBridgesAnAppHandle() {
        val slot = slot(camera = Rect(450, 10, 490, 50), obstacles = listOf(Rect(400, 0, 460, 60)))
        assertThat(slot.cameraOnRight).isNull()
        assertThat(slot.left).isEqualTo(490)
    }

    @Test
    fun capsuleSlot_emptyAvailableRegionHasZeroWidth() {
        assertThat(capsuleSlot(1000, 900, 100, null, emptyList(), 40, 3).width).isEqualTo(0)
    }

    @Test
    fun capsuleSlot_usesTheSideThatFitsBothActivities() {
        val slot = capsuleSlot(1000, 400, 900, Rect(450, 10, 490, 50), emptyList(), 72, 3)
        assertThat(slot.cameraOnRight).isFalse()
        assertThat(slot.left).isEqualTo(493)
    }

    @Test
    fun capsuleSlot_wideNotchRemainsAnObstacleNotACapsule() {
        assertThat(slot(camera = Rect(350, 0, 650, 50)).cameraOnRight).isNull()
    }

    @Test
    fun capsuleSegments_twoActivitiesJoinAndShareTheBaseline() {
        val bounds = capsuleSegments(CapsuleSlot(100, 447, true), listOf(40, 40), 8, 10, 28)
        assertThat(bounds).containsExactly(Rect(407, 10, 447, 38), Rect(375, 10, 415, 38)).inOrder()
        assertThat(Rect.intersects(bounds[0], bounds[1])).isTrue()
    }

    @Test
    fun cameraOpeningBounds_doesNotScalePathToSafeRectangle() {
        assertThat(cameraOpeningBounds(Rect(582, 0, 682, 130), Rect(597, 44, 667, 114)))
            .isEqualTo(Rect(597, 44, 667, 114))
    }

    @Test
    fun cameraOpeningBounds_preservesMatchingPhysicalPath() {
        assertThat(cameraOpeningBounds(Rect(582, 0, 682, 130), Rect(582, 30, 682, 130)))
            .isEqualTo(Rect(582, 30, 682, 130))
    }

    @Test
    fun cameraOpeningBounds_missingPathDoesNotUseSafeRectangleAsCamera() {
        assertThat(cameraOpeningBounds(Rect(582, 0, 682, 130), Rect()).isEmpty).isTrue()
    }

    @Test
    fun capsuleActivitiesInDisplayOrder_usesPrivacyRoleNotColor() {
        val icon = OngoingActivityChipModel.ChipIcon.SingleColorIcon(Icon.Resource(R.drawable.ic_screenrecord, null))
        val privacy = OngoingActivityChipModel.Active(
            key = "privacy", isImportantForPrivacy = true, icon = icon,
            content = OngoingActivityChipModel.Content.IconOnly,
            colors = ColorsModel.SystemThemed,
            clickBehavior = OngoingActivityChipModel.ClickBehavior.None,
        )
        val neutral = privacy.copy(key = "neutral", isImportantForPrivacy = false, colors = ColorsModel.Red)
        assertThat(capsuleActivitiesInDisplayOrder(listOf(privacy, neutral)))
            .containsExactly(neutral, privacy).inOrder()
        assertThat(neutral.colors).isEqualTo(ColorsModel.Red)
        assertThat(privacy.colors).isEqualTo(ColorsModel.SystemThemed)
    }

    @Test
    fun capsuleIconContentSize_scalesWithHeightUsingSvgRatio() {
        assertThat(capsuleIconContentSize(28.dp).value).isWithin(0.001f).of(18f)
        assertThat(capsuleIconContentSize(42.dp).value).isWithin(0.001f).of(27f)
    }

    @Test
    fun capsuleSegments_expansionKeepsCameraFacingEdgeFixed() {
        val slot = CapsuleSlot(100, 447, true)
        val bounds = capsuleSegments(slot, listOf(100, 40), 8, 10, 28)
        assertThat(bounds[0].right).isEqualTo(447)
        assertThat(bounds[1].right - bounds[0].left).isEqualTo(8)
    }

    @Test
    fun capsuleBackgrounds_extendsRearBackgroundWithoutMovingContent() {
        val content = capsuleSegments(CapsuleSlot(100, 447, true), listOf(100, 80), 0, 30, 100)
        val backgrounds = capsuleBackgrounds(content, Rect(456, 30, 556, 130), true)
        assertThat(content).containsExactly(Rect(347, 30, 447, 130), Rect(267, 30, 347, 130)).inOrder()
        assertThat(backgrounds)
            .containsExactly(Rect(347, 30, 556, 130), Rect(267, 30, 397, 130)).inOrder()
        assertThat(backgrounds[0].right - backgrounds[0].height() / 2).isEqualTo(506)
    }

    @Test
    fun capsuleBackgrounds_mirrorsBackgroundExtensionOnTheRight() {
        val content = capsuleSegments(CapsuleSlot(493, 900, false), listOf(100, 80), 0, 30, 100)
        assertThat(capsuleBackgrounds(content, Rect(384, 30, 484, 130), false))
            .containsExactly(Rect(384, 30, 593, 130), Rect(543, 30, 673, 130)).inOrder()
    }

    @Test
    fun capsuleHeightForCamera_matchesSvgOuterHeightRatio() {
        assertThat(capsuleHeightForCamera(Rect(225, 230, 249, 254))).isEqualTo(28)
        assertThat(capsuleHeightForCamera(Rect(582, 30, 682, 130))).isEqualTo(117)
        assertThat(capsuleHeightForCamera(Rect(597, 44, 667, 114))).isEqualTo(82)
    }

    @Test
    fun capsuleBackgrounds_svgCameraHasTwoUnitsOfOuterPadding() {
        val camera = Rect(225, 230, 249, 254)
        val content = listOf(Rect(196, 228, 222, 256))
        val background = capsuleBackgrounds(content, camera, true).single()
        assertThat(background.right - camera.right).isEqualTo(2)
        assertThat(camera.top - background.top).isEqualTo(2)
        assertThat(background.bottom - camera.bottom).isEqualTo(2)
        assertThat(background.right - background.height() / 2).isEqualTo(camera.centerX())
    }

    @Test
    fun capsuleBackgrounds_svgCameraPaddingMirrorsOnTheLeft() {
        val camera = Rect(225, 230, 249, 254)
        val background = capsuleBackgrounds(listOf(Rect(252, 228, 278, 256)), camera, false).single()
        assertThat(camera.left - background.left).isEqualTo(2)
        assertThat(background.left + background.height() / 2).isEqualTo(camera.centerX())
    }

    @Test
    fun capsuleSegments_rightSideUsesPhysicalCoordinates() {
        val slot = CapsuleSlot(493, 900, false)
        val bounds = capsuleSegments(slot, listOf(40, 40), 8, 10, 28)
        assertThat(bounds).containsExactly(Rect(493, 10, 533, 38), Rect(525, 10, 565, 38)).inOrder()
    }

    @Test
    fun capsuleSlot_cameraOutsideAvailableRegionIsNotJoined() {
        assertThat(slot(camera = Rect(950, 10, 990, 50)).cameraOnRight).isNull()
    }

    @Test
    fun lyricRegion_stopsAtTheCapsuleAndCutout() {
        assertThat(lyricRegionBeforeChip(1000, 0, 900, chipLeft = 640)).isEqualTo(0 to 640)
        assertThat(lyricRegionBeforeChip(1000, 0, 900, chipLeft = 640, cutoutLeft = 420)).isEqualTo(0 to 420)
        assertThat(lyricRegionBeforeChip(1000, 80, 900, cutoutLeft = 40)).isEqualTo(80 to 0)
        assertThat(lyricRegionBeforeChip(1000, 180, 1000, chipLeft = 700)).isEqualTo(180 to 520)
    }

    @Test
    fun displayXToContentX_accountsForAllOrigins() {
        assertThat(displayXToContentX(420, 300, 20, 36)).isEqualTo(104)
    }

    private fun slot(camera: Rect? = null, obstacles: List<Rect> = emptyList()) =
        capsuleSlot(1000, 100, 900, camera, obstacles, 40, 3)

    private fun setContent() {
        composeTestRule.setContent {
            StatusBarRoot(
                parent = FrameLayout(context),
                shadeWindowRootView = kosmos.mockShadeRootView,
                statusBarViewModelFactory = kosmos.defaultDisplayHomeStatusBarViewModelFactory,
                statusBarViewBinder = homeStatusBarViewBinder,
                notificationIconsBinder = mock(),
                iconViewStoreFactory = kosmos.connectedDisplaysStatusBarNotificationIconViewStoreFactory,
                clockViewModelFactory = kosmos.clockViewModelFactory,
                darkIconManagerFactory = darkIconManagerFactory,
                tintedIconManagerFactory = tintedIconManagerFactory,
                headlineViewModelFactory = kosmos.displayAwareHeadlineViewModelImplFactory,
                headlineComposer = mock(),
                iconController = kosmos.statusBarIconController,
                darkIconDispatcher = kosmos.fakeDarkIconDispatcher,
                eventAnimationInteractor = kosmos.systemStatusEventAnimationInteractor,
                statusBarRegionSamplingViewModelFactory = kosmos.statusBarRegionSamplingViewModelFactory,
                onViewCreated = {},
                modifier = Modifier.testTag(STATUS_BAR_ROOT_TAG),
            )
        }
    }

    companion object {
        private const val STATUS_BAR_ROOT_TAG = "StatusBarRoot"
    }
}
