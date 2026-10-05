/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2023 The Android Open Source Project
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

package org.uwuaosp.systemui.capsule

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.systemui.clock.ClockModernization
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingIn
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingOut
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.RunningChipAnim
import org.uwuaosp.systemui.lyric.LyricViewController
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import kotlinx.coroutines.launch
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarVisibilityChangeListener
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarOperatorNameViewBinder

/** Phone capsule integration, including its full-width lyric overlay and visibility rules. */
class CapsuleHomeStatusBarViewBinder : HomeStatusBarViewBinder {
    override fun bind(
        displayId: Int,
        view: View,
        viewModel: HomeStatusBarViewModel,
        systemEventChipAnimateIn: ((View) -> Unit)?,
        systemEventChipAnimateOut: ((View) -> Unit)?,
        listener: StatusBarVisibilityChangeListener?,
    ) {
        // Set some top-level views to gone before we get started
        val systemInfoView = view.requireViewById<View>(R.id.status_bar_end_side_content)
        val capsuleState = (viewModel as CapsuleStatusBarHost).capsuleState
        val clockView = view.requireViewById<View>(R.id.clock)
        val notificationIconsArea = view.requireViewById<View>(R.id.notification_icon_area)
        val networkTrafficView = view.requireViewById<View>(R.id.network_traffic_holder)

        // GONE because this shouldn't take space in the layout
        systemInfoView.hideInitially()
        clockView.hideInitially()
        notificationIconsArea.hideInitially()

        view.repeatWhenAttached {
            val lyricController = LyricController(view, viewModel).also { it.hideInitially() }
            try {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                listener?.let { listener ->
                    launch {
                        viewModel.isTransitioningFromLockscreenToOccluded.collect {
                            listener.onStatusBarVisibilityMaybeChanged()
                        }
                    }
                }

                listener?.let { listener ->
                    launch {
                        viewModel.transitionFromLockscreenToDreamStartedEvent.collect {
                            listener.onTransitionFromLockscreenToDreamStarted()
                        }
                    }
                }

                val lightsOutView: View = view.requireViewById(R.id.notification_lights_out)
                launch {
                    viewModel.areNotificationsLightsOut.collect { show ->
                        animateLightsOutView(lightsOutView, show)
                    }
                }

                if (com.android.media.projection.flags.Flags.showStopDialogPostCallEnd()) {
                    launch {
                        viewModel.mediaProjectionStopDialogDueToCallEndedState.collect { stopDialog
                            ->
                            if (stopDialog is MediaProjectionStopDialogModel.Shown) {
                                stopDialog.createAndShowDialog()
                            }
                        }
                    }
                }

                if (SceneContainerFlag.isEnabled) {
                    listener?.let { listener ->
                        launch {
                            viewModel.isHomeStatusBarAllowed.collect {
                                listener.onIsHomeStatusBarAllowedBySceneChanged(it)
                            }
                        }
                    }
                }

                // TODO(b/393445203): figure out the best story for this stub view. This crashes
                // if we move it up to the top of [bind]
                val operatorNameView = view.requireViewById<View>(R.id.operator_name_frame)
                operatorNameView.isVisible = false

                StatusBarOperatorNameViewBinder.bind(
                    operatorNameView,
                    viewModel.operatorNameViewModel,
                    viewModel.areaTint,
                )
                launch {
                    viewModel.shouldShowOperatorNameView.collect { operatorNameView.isVisible = it }
                }

                if (!ClockModernization.isEnabled) {
                    launch {
                        capsuleState.isClockVisible.collect { lyricController.updateClockVisibility(it) }
                    }
                }

                launch {
                    capsuleState.isNotificationIconContainerVisible.collect {
                        lyricController.updateNotificationIconsVisibility(it)
                    }
                }

                launch { viewModel.isLyricEnabled.collect { lyricController.isEnabled = it } }
                launch {
                    capsuleState.ongoingActivityChipBounds.collect {
                        lyricController.updateOngoingChipBounds(it)
                    }
                }
                launch {
                    capsuleState.isAnyChipVisible.collect { lyricController.setOngoingChipVisible(it) }
                }
                launch {
                    viewModel.isLyricClockRightMode.collect {
                        lyricController.setLyricPosition(
                            if (it) LyricViewController.LYRIC_POSITION_CLOCK_RIGHT
                            else LyricViewController.LYRIC_POSITION_OVERLAY
                        )
                    }
                }
                launch {
                    viewModel.isLyricClockRightHideIcon.collect {
                        lyricController.setHideIconOnClockRight(it)
                    }
                }
                launch {
                    viewModel.isLyricTranslationEnabled.collect {
                        lyricController.setShowTranslation(it)
                    }
                }
                launch {
                    viewModel.isLyricWordTimingEnabled.collect {
                        lyricController.setWordTimingEnabled(it)
                    }
                }
                launch { capsuleState.isLyricVisible.collect { lyricController.adjustVisibility(it) } }

                launch {
                    viewModel.systemInfoCombinedVis.collect { (baseVis, animState) ->
                        // Broadly speaking, the baseVis controls the view.visibility, and
                        // the animation state uses only alpha to achieve its effect. This
                        // means that we can always modify the visibility, and if we're
                        // animating we can use the animState to handle it. If we are not
                        // animating, then we can use the baseVis default animation
                        if (animState.isAnimatingChip()) {
                            // Just apply the visibility of the view, but don't animate
                            networkTrafficView.visibility = baseVis.visibility
                            systemInfoView.visibility = baseVis.visibility
                            // Now apply the animation state, with its animator
                            when (animState) {
                                AnimatingIn -> {
                                    systemEventChipAnimateIn?.invoke(networkTrafficView)
                                    systemEventChipAnimateIn?.invoke(systemInfoView)
                                }
                                AnimatingOut -> {
                                    systemEventChipAnimateOut?.invoke(networkTrafficView)
                                    systemEventChipAnimateOut?.invoke(systemInfoView)
                                }
                                else -> {
                                    // Nothing to do here
                                }
                            }
                        } else {
                            networkTrafficView.adjustVisibility(baseVis)
                            systemInfoView.adjustVisibility(baseVis)
                        }
                    }
                }
                }
            } finally {
                lyricController.releaseViews()
                lyricController.destroy()
            }
        }
    }

    private fun SystemEventAnimationState.isAnimatingChip() =
        when (this) {
            AnimatingIn,
            AnimatingOut,
            RunningChipAnim -> true
            else -> false
        }

    private fun animateLightsOutView(view: View, visible: Boolean) {
        view.animate().cancel()

        val alpha = if (visible) 1f else 0f
        val duration = if (visible) 750L else 250L
        val visibility = if (visible) View.VISIBLE else View.GONE

        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }

        view
            .animate()
            .alpha(alpha)
            .setDuration(duration)
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.alpha = alpha
                        view.visibility = visibility
                        // Unset the listener, otherwise this may persist for
                        // another view property animation
                        view.animate().setListener(null)
                    }
                }
            )
            .start()
    }

    private fun View.adjustVisibility(model: VisibilityModel) {
        if (model.visibility == View.VISIBLE) {
            this.show(model.shouldAnimateChange)
        } else {
            this.hide(model.visibility, model.shouldAnimateChange)
        }
    }

    /**
     * Hide the view for initialization, but skip if it's already hidden and does not cancel
     * animations.
     */
    private fun View.hideInitially(state: Int = View.INVISIBLE) {
        if (visibility == View.INVISIBLE || visibility == View.GONE) {
            return
        }
        alpha = 0f
        visibility = state
    }

    // See CollapsedStatusBarFragment#hide.
    private fun View.hide(state: Int = View.INVISIBLE, shouldAnimateChange: Boolean) {
        animate().cancel()

        if (
            (visibility == View.INVISIBLE && state == View.INVISIBLE) ||
                (visibility == View.GONE && state == View.GONE)
        ) {
            return
        }
        val isAlreadyHidden = visibility == View.INVISIBLE || visibility == View.GONE
        if (!shouldAnimateChange || isAlreadyHidden) {
            alpha = 0f
            visibility = state
            return
        }

        animate()
            .alpha(0f)
            .setDuration(FADE_OUT_DURATION.toLong())
            .setStartDelay(0)
            .setInterpolator(Interpolators.ALPHA_OUT)
            .withEndAction { visibility = state }
    }

    // See CollapsedStatusBarFragment#show.
    private fun View.show(shouldAnimateChange: Boolean) {
        animate().cancel()
        if (visibility == View.VISIBLE && alpha >= 1f) {
            return
        }
        visibility = View.VISIBLE
        if (!shouldAnimateChange) {
            alpha = 1f
            return
        }
        animate()
            .alpha(1f)
            .setDuration(FADE_IN_DURATION.toLong())
            .setInterpolator(Interpolators.ALPHA_IN)
            .setStartDelay(FADE_IN_DELAY.toLong())
            // We need to clean up any pending end action from animateHide if we call both hide and
            // show in the same frame before the animation actually gets started.
            // cancel() doesn't really remove the end action.
            .withEndAction(null)

        // TODO(b/364360986): Synchronize the motion with the Keyguard fading if necessary.
    }

    inner class LyricController(
        val statusBar: View,
        viewModel: HomeStatusBarViewModel,
    ) :
        LyricViewController(statusBar.context, statusBar, statusBar.findViewById(R.id.clock)) {
        private val capsuleState = (viewModel as CapsuleStatusBarHost).capsuleState
        private val leftSide: View by lazy {
            statusBar.findViewById(R.id.status_bar_start_side_except_heads_up)
        }
        private val notificationIconArea: View by lazy {
            statusBar.findViewById(R.id.notification_icon_area)
        }
        private var canShowNotificationIcons = false
        private var canShowLyric = false
        private var previousLeftSideVisibility = View.VISIBLE
        private var hidingLeftSide = false
        private var hidingNotificationIcons = false
        private val clockView: View by lazy { statusBar.findViewById(R.id.clock) }
        private var clockVisibilityModel = VisibilityModel(View.GONE, false)
        private var ongoingChipBounds = android.graphics.Rect()
        private var hasOngoingChip = false
        private var ongoingChipRequestedVisible = false
        private var availableLyricWidth = 0
        private val statusBarLayoutListener =
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateLyricBounds()
                reconcileLyricVisibility()
            }
        private val statusBarGlobalLayoutListener =
            ViewTreeObserver.OnGlobalLayoutListener {
                updateLyricBounds()
                reconcileLyricVisibility()
            }

        init {
            statusBar.addOnLayoutChangeListener(statusBarLayoutListener)
            statusBar.viewTreeObserver.addOnGlobalLayoutListener(statusBarGlobalLayoutListener)
        }

        fun hideInitially() {
            // GONE because this shouldn't take space in the layout
            overlayLyricView.hideInitially(state = View.GONE)
            inlineLyricView?.hideInitially(state = View.GONE)
        }

        override fun onLyricStartedChanged(started: Boolean) {
            capsuleState.onLyricStartedChanged(
                started && shouldShowLyricNow() && canShowLyric && canPlaceLyric()
            )
        }

        fun adjustVisibility(model: VisibilityModel) {
            canShowLyric = model.visibility == View.VISIBLE
            if (model.visibility == View.VISIBLE) {
                showLyricView(model.shouldAnimateChange)
            } else {
                hideLyricView(model.shouldAnimateChange)
            }
            updateClockVisibility()
        }

        fun updateClockVisibility(model: VisibilityModel) {
            clockVisibilityModel = model
            updateClockVisibility()
        }

        fun updateOngoingChipBounds(bounds: android.graphics.Rect) {
            ongoingChipBounds = android.graphics.Rect(bounds)
            hasOngoingChip = ongoingChipRequestedVisible || !ongoingChipBounds.isEmpty
            updateLyricBounds()
            reconcileLyricVisibility()
            updateClockVisibility()
        }

        fun setOngoingChipVisible(visible: Boolean) {
            ongoingChipRequestedVisible = visible
            hasOngoingChip = visible || !ongoingChipBounds.isEmpty
            updateLyricBounds()
            reconcileLyricVisibility()
            updateClockVisibility()
        }

        private fun reconcileLyricVisibility() {
            val shouldShow = canShowLyric && shouldShowLyricNow() && canPlaceLyric()
            val currentView = if (isClockRightMode()) inlineLyricView else overlayLyricView
            val isVisible = currentView?.visibility == View.VISIBLE
            if (shouldShow && !isVisible) {
                showLyricView(false)
            } else if (!shouldShow && isVisible) {
                hideLyricView(false)
            }
        }

        private fun canPlaceLyric(): Boolean =
            availableLyricWidth > 0 && (!hasOngoingChip || !ongoingChipBounds.isEmpty)

        private fun updateClockVisibility() {
            if (!ClockModernization.isEnabled) {
                clockView.adjustVisibility(clockVisibilityModel)
            }
        }

        private fun updateLyricBounds() {
            val overlayView = overlayLyricView
            val parent = overlayView.parent as? ViewGroup ?: return
            val inlineView = inlineLyricView

            val parentLocation = IntArray(2)
            parent.getLocationInWindow(parentLocation)
            val contentLeft = parentLocation[0] + parent.paddingLeft
            val contentWidth = parent.width - parent.paddingLeft - parent.paddingRight
            if (contentWidth <= 0) {
                availableLyricWidth = 0
                listOfNotNull(overlayView, inlineView).forEach { view ->
                    val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
                    params.width = 0
                    params.leftMargin = 0
                    params.rightMargin = 0
                    params.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
                    view.layoutParams = params
                }
                return
            }
            val cutoutLeftInContent =
                (statusBar.rootWindowInsets?.displayCutout ?: statusBar.context.display?.cutout)
                    ?.boundingRectTop
                    ?.takeUnless { it.isEmpty }
                    ?.let { cutoutBounds ->
                        val parentScreenLocation = IntArray(2)
                        parent.getLocationOnScreen(parentScreenLocation)
                        displayXToContentX(
                            displayX = cutoutBounds.left,
                            parentScreenX = parentScreenLocation[0],
                            parentWindowX = parentLocation[0],
                            contentWindowX = contentLeft,
                        )
                    }

            val rightLimitView =
                statusBar.findViewById<View>(R.id.status_bar_end_side_content)
            val rightLimitLocation = IntArray(2)
            rightLimitView.getLocationInWindow(rightLimitLocation)
            val isRtl = statusBar.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val leftLimit =
                if (isRtl) {
                    (rightLimitLocation[0] + rightLimitView.width - contentLeft)
                        .coerceIn(0, contentWidth)
                } else {
                    0
                }
            val rightLimit =
                if (isRtl) {
                    contentWidth
                } else {
                    (rightLimitLocation[0] - contentLeft).coerceIn(0, contentWidth)
                }

            val (left, width) =
                if (hasOngoingChip && ongoingChipBounds.isEmpty) {
                    leftLimit to 0
                } else if (hasOngoingChip) {
                    // Lyrics are intentionally kept on the physical left of the live chip. Do not
                    // move them to the right-side remainder when the left region is too narrow;
                    // hiding is preferable to reversing the requested left-shift or overlapping.
                    lyricRegionBeforeChip(
                        contentWidth = contentWidth,
                        leftLimit = leftLimit,
                        rightLimit = rightLimit,
                        chipLeft = ongoingChipBounds.left - contentLeft,
                        cutoutLeft = cutoutLeftInContent,
                    )
                } else if (isClockRightMode()) {
                    val clockLocation = IntArray(2)
                    clockView.getLocationInWindow(clockLocation)
                    val start =
                        (clockLocation[0] + clockView.width - contentLeft)
                            .coerceIn(leftLimit, rightLimit)
                    lyricRegionBeforeChip(
                        contentWidth = contentWidth,
                        leftLimit = start,
                        rightLimit = rightLimit,
                        cutoutLeft = cutoutLeftInContent,
                    )
                } else {
                    // Apply the cutout bound even when there is no live chip. Otherwise the
                    // ordinary lyric overlay can continue through a left-side camera cutout.
                    lyricRegionBeforeChip(
                        contentWidth = contentWidth,
                        leftLimit = leftLimit,
                        rightLimit = rightLimit,
                        cutoutLeft = cutoutLeftInContent,
                    )
                }
            availableLyricWidth = width

            // Visibility must be reconciled separately so hiding lyrics also restores the status
            // bar content that was suppressed while they were shown.
            fun updateViewBounds(view: View?) {
                if (view == null) return
                val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
                val gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
                if (
                    params.width != width ||
                        params.height != ViewGroup.LayoutParams.MATCH_PARENT ||
                        params.leftMargin != left ||
                        params.rightMargin != 0 ||
                        params.gravity != gravity
                ) {
                    params.width = width
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    params.leftMargin = left
                    params.rightMargin = 0
                    params.gravity = gravity
                    view.layoutParams = params
                }
            }

            updateViewBounds(overlayView)
            updateViewBounds(inlineView)
        }

        fun updateNotificationIconsVisibility(model: VisibilityModel) {
            canShowNotificationIcons = model.visibility == View.VISIBLE
            if (!hidingNotificationIcons) {
                notificationIconArea.adjustVisibility(model)
            }
        }

        override fun showLyricView(animate: Boolean) {
            if (shouldShowLyricNow() && canShowLyric && canPlaceLyric()) {
                if (isClockRightMode()) {
                    if (!hidingNotificationIcons) {
                        hidingNotificationIcons = true
                    }
                    notificationIconArea.visibility = View.GONE
                } else {
                    if (!hidingLeftSide) {
                        previousLeftSideVisibility = leftSide.visibility
                        hidingLeftSide = true
                    }
                    leftSide.visibility = View.INVISIBLE
                }
                lyricView.show(animate)
                capsuleState.onLyricStartedChanged(true)
            }
        }

        override fun hideLyricView(animate: Boolean) {
            capsuleState.onLyricStartedChanged(false)
            val hiddenState = if (isClockRightMode()) View.GONE else View.INVISIBLE
            lyricView.hide(state = hiddenState, shouldAnimateChange = animate)
            if (isClockRightMode()) {
                restoreNotificationIcons(animate)
            } else {
                restoreLeftSide(animate)
            }
        }

        override fun onLyricPositionChanged() {
            capsuleState.onLyricStartedChanged(false)
            overlayLyricView.hide(state = View.GONE, shouldAnimateChange = false)
            inlineLyricView?.hide(state = View.GONE, shouldAnimateChange = false)
            restoreLeftSide(false)
            restoreNotificationIcons(false)
            if (shouldShowLyricNow() && canShowLyric) {
                showLyricView(false)
            }
        }

        fun releaseViews() {
            capsuleState.onLyricStartedChanged(false)
            statusBar.removeOnLayoutChangeListener(statusBarLayoutListener)
            if (statusBar.viewTreeObserver.isAlive) {
                statusBar.viewTreeObserver.removeOnGlobalLayoutListener(
                    statusBarGlobalLayoutListener
                )
            }
            overlayLyricView.visibility = View.GONE
            inlineLyricView?.visibility = View.GONE
            restoreLeftSide(false)
            restoreNotificationIcons(false)
        }

        private fun restoreLeftSide(animate: Boolean) {
            if (!hidingLeftSide) return
            hidingLeftSide = false
            leftSide.visibility = previousLeftSideVisibility
        }

        private fun restoreNotificationIcons(animate: Boolean) {
            if (!hidingNotificationIcons) return
            hidingNotificationIcons = false
            notificationIconArea.visibility =
                if (canShowNotificationIcons) View.VISIBLE else View.GONE
        }
    }

    companion object {
        /** Animation durations for status bar. Used to be defined in the fragment */
        const val FADE_IN_DURATION = 320
        const val FADE_OUT_DURATION = 160
        const val FADE_IN_DELAY = 50
    }
}

/** Returns a left-aligned lyric region whose right edge cannot cross the live chip's left edge. */
internal fun lyricRegionBeforeChip(
    contentWidth: Int,
    leftLimit: Int,
    rightLimit: Int,
    chipLeft: Int? = null,
    cutoutLeft: Int? = null,
): Pair<Int, Int> {
    val boundedContentWidth = contentWidth.coerceAtLeast(0)
    val usableLeft = leftLimit.coerceIn(0, boundedContentWidth)
    val usableRight = maxOf(usableLeft, rightLimit.coerceIn(0, boundedContentWidth))
    val lyricRight =
        minOf(chipLeft ?: usableRight, cutoutLeft ?: usableRight)
            .coerceIn(usableLeft, usableRight)
    return usableLeft to lyricRight - usableLeft
}

/** Converts a DisplayCutout x-coordinate to the status-bar content's local coordinate space. */
internal fun displayXToContentX(
    displayX: Int,
    parentScreenX: Int,
    parentWindowX: Int,
    contentWindowX: Int,
): Int = parentWindowX + displayX - parentScreenX - contentWindowX
