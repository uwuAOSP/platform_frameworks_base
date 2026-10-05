/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.IdRes
import android.content.res.ColorStateList
import android.util.Log
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.Expandable
import com.android.compose.modifiers.thenIf
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.StatusBarChipsReturnAnimations
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder
import kotlinx.coroutines.delay
import com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel

@Composable
fun CapsuleActivityChip(
    model: OngoingActivityChipModel.Active,
    iconViewStore: NotificationIconContainerViewBinder.IconViewStore?,
    isCompact: Boolean = false,
    onExpand: (() -> Unit)? = null,
    isCapsuleSegment: Boolean = false,
    isCameraAdjacentCapsuleSegment: Boolean = false,
    capsuleHeight: Dp = dimensionResource(R.dimen.ongoing_activity_capsule_fallback_height),
    showCompactContent: Boolean = true,
    capsuleInteractionSource: MutableInteractionSource? = null,
    modifier: Modifier = Modifier,
) {
    val compactClickPerformed: MutableState<Boolean> =
        remember(model.key, model.instanceId) { mutableStateOf(false) }
    val collapseTimeoutMillis = ongoingActivityCapsuleTimeoutMillis()
    LaunchedEffect(model.key, model.instanceId, compactClickPerformed.value, collapseTimeoutMillis) {
        if (compactClickPerformed.value) {
            // Briefly reveal the recording timer after the compact chip is tapped, then restore
            // the compact affordance instead of leaving the status bar permanently expanded.
            delay(collapseTimeoutMillis)
            compactClickPerformed.value = false
        }
    }
    val contentDescription =
        when (val icon = model.icon) {
            is OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon ->
                icon.contentDescription.load()
            is OngoingActivityChipModel.ChipIcon.SingleColorIcon,
            null -> null
        }

    val borderStroke =
        if (isCapsuleSegment) {
            null
        } else {
            model.colors.outline(LocalContext.current)?.let {
                BorderStroke(
                    dimensionResource(R.dimen.ongoing_activity_chip_outline_width),
                    Color(it),
                )
            }
        }

    val actionOnClick =
        when (val clickBehavior = model.clickBehavior) {
            is OngoingActivityChipModel.ClickBehavior.ExpandAction -> { expandable: Expandable ->
                    clickBehavior.onClick(expandable)
                }
            is OngoingActivityChipModel.ClickBehavior.ShowHeadsUpNotification -> { _ ->
                    clickBehavior.onClick()
                }
            is OngoingActivityChipModel.ClickBehavior.HideHeadsUpNotification -> { _ ->
                    clickBehavior.onClick()
                }
            is OngoingActivityChipModel.ClickBehavior.None -> null
        }
    val expandsOnCompactClick =
        isCompact && onExpand != null &&
            model.content !is OngoingActivityChipModel.Content.Countdown &&
            model.content != OngoingActivityChipModel.Content.IconOnly
    val onClick: ((Expandable) -> Unit)? =
        if (expandsOnCompactClick) {
            null
        } else {
            actionOnClick?.let { action ->
                { expandable: Expandable ->
                    if (isCompact && model.key == ScreenRecordChipViewModel.KEY) {
                        compactClickPerformed.value = true
                    }
                    action(expandable)
                }
            }
        }
    val onClickLabel =
        if (isCompact) {
            null
        } else {
            model.clickBehavior.customOnClickLabel?.let { stringResource(it) }
        }
    val isClickable = onClick != null || expandsOnCompactClick

    val chipSidePaddingTotal = 20.dp
    val timerHiddenUntilCompactClick =
        isCompact && model.content is OngoingActivityChipModel.Content.Timer &&
            (model.key == ScreenRecordChipViewModel.KEY) &&
            !compactClickPerformed.value
    val compactContentVisible =
        model.content is OngoingActivityChipModel.Content.Countdown ||
            ((showCompactContent || compactClickPerformed.value) &&
                model.content is OngoingActivityChipModel.Content.Timer &&
                (model.key != ScreenRecordChipViewModel.KEY || compactClickPerformed.value))
    val contentVisible = !timerHiddenUntilCompactClick && (!isCompact || compactContentVisible)
    val capsuleMetrics = OngoingActivityCapsuleStyle.metrics(
        capsuleHeight,
        model.icon,
        isCameraAdjacentCapsuleSegment,
        LocalContext.current.resources,
    )
    val iconSize =
        if (isCapsuleSegment) {
            capsuleMetrics.iconSize
        } else if (model.icon?.hasEmbeddedPadding == true) {
            dimensionResource(R.dimen.ongoing_activity_chip_embedded_padding_icon_size)
        } else {
            dimensionResource(R.dimen.ongoing_activity_chip_icon_size) + if (isCompact) 2.dp else 0.dp
        }
    val capsuleSidePadding = capsuleMetrics.sidePadding
    val minWidth =
        if (isCompact) {
            if (compactContentVisible) 0.dp
            else if (isCapsuleSegment) {
                capsuleMetrics.compactWidth
            }
            else 40.dp
        } else if (isClickable) {
            dimensionResource(id = R.dimen.min_clickable_item_size)
        } else if (model.icon != null) {
            dimensionResource(id = R.dimen.ongoing_activity_chip_icon_size) + chipSidePaddingTotal
        } else {
            dimensionResource(id = R.dimen.ongoing_activity_chip_min_text_width) +
                chipSidePaddingTotal
        }

    Expandable(
        color =
            if (isCapsuleSegment) {
                Color.Transparent
            } else {
                Color(model.colors.background(LocalContext.current).defaultColor)
            },
        shape =
            RoundedCornerShape(dimensionResource(id = R.dimen.ongoing_activity_chip_corner_radius)),
        modifier =
            modifier
                .wrapContentSize()
                .animateContentSize(
                    animationSpec =
                        if (isCapsuleSegment) {
                            // A new selection retargets immediately, without a spring's settling
                            // tail or overshoot while the neighboring segment is shrinking.
                            tween(durationMillis = OngoingActivityCapsuleStyle.WIDTH_ANIMATION_DURATION_MILLIS)
                        } else {
                            spring(
                                dampingRatio =
                                    if (isCompact) Spring.DampingRatioNoBouncy
                                    else Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            )
                        }
                )
                .thenIf(expandsOnCompactClick) {
                    if (isCapsuleSegment && capsuleInteractionSource != null) {
                        Modifier.clickable(
                            interactionSource = capsuleInteractionSource,
                            indication = null,
                            onClickLabel = onClickLabel,
                        ) { checkNotNull(onExpand).invoke() }
                    } else {
                        Modifier.clickable(onClickLabel = onClickLabel) {
                            checkNotNull(onExpand).invoke()
                        }
                    }
                }
                .semantics {
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                    if (model.content is OngoingActivityChipModel.Content.Countdown) {
                        liveRegion = LiveRegionMode.Assertive
                    }
                }
                .widthIn(min = minWidth)
                // For non-privacy-related chips, only show the chip if there's enough space for at
                // least the minimum width.
                .thenIf(!model.isImportantForPrivacy) {
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (constraints.maxWidth >= minWidth.roundToPx()) {
                                placeable.place(0, 0)
                            }
                        }
                    }
                }
                .graphicsLayer(
                    alpha =
                        if (model.transitionManager?.hideChipForTransition == true) {
                            0f
                        } else {
                            1f
                        }
                ),
        borderStroke = borderStroke,
        onClick = onClick,
        onClickLabel = onClickLabel,
        interactionSource = capsuleInteractionSource.takeIf { isCapsuleSegment },
        useModifierBasedImplementation = StatusBarChipsReturnAnimations.isEnabled,
        // Don't use the default minimum size for 2 reasons:
        //   1. Some chips like the 3-2-1 countdown chip should have a very small width.
        //   2. All chips need a background height that's much smaller than 48dp.
        // For clickable chips, Compose will automatically increase the touch target size outside
        // the bounds of the composable if needed, so the smaller chip size isn't an accessibility
        // concern.
        defaultMinSize = false,
        transitionControllerFactory = model.transitionManager?.controllerFactory,
    ) {
        ChipBody(
            model,
            iconViewStore,
            minWidth = minWidth,
            isCapsuleSegment = isCapsuleSegment,
            capsuleHeight = capsuleHeight,
            iconSize = iconSize,
            capsuleSidePadding = capsuleSidePadding,
            isCompact = isCompact,
            compactContentVisible = compactContentVisible,
            contentVisible = contentVisible,
        )
    }
}

internal fun capsuleIconContentSize(capsuleHeight: Dp): Dp =
    OngoingActivityCapsuleStyle.iconContentSize(capsuleHeight)

@Composable
private fun ChipBody(
    model: OngoingActivityChipModel.Active,
    iconViewStore: NotificationIconContainerViewBinder.IconViewStore?,
    minWidth: Dp,
    isCapsuleSegment: Boolean,
    capsuleHeight: Dp,
    iconSize: Dp,
    capsuleSidePadding: Dp,
    isCompact: Boolean,
    compactContentVisible: Boolean,
    contentVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .then(
                    if (isCapsuleSegment) Modifier.height(capsuleHeight)
                    else Modifier.heightIn(min = dimensionResource(R.dimen.ongoing_appops_chip_height))
                )
                // Set the minWidth here as well as on the Expandable so that the content within
                // this row is still centered correctly horizontally
                .widthIn(min = minWidth)
                .padding(
                    // Always keep start & end padding the same so that if the text has to hide for
                    // some reason, the content is still centered
                    horizontal =
                        if (isCapsuleSegment) {
                            capsuleSidePadding
                        } else if (isCompact) {
                            8.dp
                        } else if (model.icon?.hasEmbeddedPadding == true) {
                            dimensionResource(
                                R.dimen.ongoing_activity_chip_side_padding_for_embedded_padding_icon
                            )
                        } else {
                            6.dp
                        }
                ),
    ) {
        model.icon?.let {
            ChipIcon(
                viewModel = it,
                iconViewStore = iconViewStore,
                colors = model.colors,
                iconSize = iconSize,
            )
        }

        val isIconOnly = model.content is OngoingActivityChipModel.Content.IconOnly
        if (!isIconOnly) {
            val content: @Composable () -> Unit = {
                CapsuleChipContent(
                    content = model.content,
                    icon = model.icon,
                    colors = model.colors,
                    showFullText = model.isNavigationActivity,
                    modifier = Modifier.sysuiResTag(STATUS_BAR_CHIP_CONTENT_ID),
                )
            }
            if (isCapsuleSegment) {
                // AnimatedVisibility retains outgoing content in measurement until fadeOut ends.
                // Remove it immediately so both segments start resizing on the selection frame.
                if (contentVisible) content()
            } else {
                AnimatedVisibility(
                    visible = contentVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    content()
                }
            }
        }

        if (isCompact && model.icon == null && !compactContentVisible) {
            Box(
                Modifier.size(6.dp)
                    .background(
                        Color(model.colors.text(LocalContext.current)),
                        RoundedCornerShape(50),
                    )
            )
        }

        model.decorativeIcon?.let {
            val context = LocalContext.current
            Box(
                modifier =
                    modifier
                        .size(width = 24.dp, height = 16.dp)
                        .background(
                            color = Color(it.colors.background(context).defaultColor),
                            shape = it.backgroundShape,
                        )
            ) {
                Icon(
                    icon = it.icon,
                    tint = Color(it.colors.text(context)),
                    modifier = Modifier.align(Alignment.Center).size(14.dp),
                )
            }

            Spacer(modifier.width(4.dp))
        }
    }
}

@Composable
private fun ChipIcon(
    viewModel: OngoingActivityChipModel.ChipIcon,
    iconViewStore: NotificationIconContainerViewBinder.IconViewStore?,
    colors: ColorsModel,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    when (viewModel) {
        is OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon -> {
            check(iconViewStore != null)

            StatusBarIcon(colors, viewModel.notificationKey, iconSize, modifier) {
                iconViewStore.iconView(viewModel.notificationKey)
            }
        }

        is OngoingActivityChipModel.ChipIcon.SingleColorIcon -> {
            Icon(
                icon = viewModel.impl,
                tint = Color(colors.text(context)),
                modifier =
                    modifier.size(iconSize),
            )
        }
    }
}

/** A Compose wrapper around [StatusBarIconView]. */
@Composable
private fun StatusBarIcon(
    colors: ColorsModel,
    notificationKey: String?,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    iconFactory: () -> StatusBarIconView?,
) {
    val context = LocalContext.current
    val colorTintList = ColorStateList.valueOf(colors.text(context))

    val iconSizePx =
        context.resources.getDimensionPixelSize(
            R.dimen.ongoing_activity_chip_embedded_padding_icon_size
        )
    val scale = with(LocalDensity.current) { iconSize.toPx() / iconSizePx }
    AndroidView(
        modifier = modifier.size(iconSize),
        factory = { _ ->
            // Use a wrapper frame layout so that we still return a view even if the icon is null
            val wrapperFrameLayout = FrameLayout(context)

            val icon = iconFactory.invoke()
            if (icon == null) {
                Log.e(TAG, "Missing StatusBarIconView for $notificationKey")
            } else {
                icon.apply {
                    id = CUSTOM_ICON_VIEW_ID
                    layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
                }
                // If needed, remove the icon from its old parent (views can only be attached
                // to 1 parent at a time)
                (icon.parent as? ViewGroup)?.apply {
                    this.removeView(icon)
                    this.removeTransientView(icon)
                }
                wrapperFrameLayout.addView(icon)
            }

            wrapperFrameLayout
        },
        update = { frameLayout ->
            frameLayout.findViewById<StatusBarIconView>(CUSTOM_ICON_VIEW_ID)?.apply {
                this.imageTintList = colorTintList
                // Scale the original icon view rather than only enlarging its container:
                // StatusBarIconView has its own resource-based drawable scale.
                scaleX = scale
                scaleY = scale
            }
        },
        onRelease = { frameLayout ->
            frameLayout.findViewById<StatusBarIconView>(CUSTOM_ICON_VIEW_ID)?.apply {
                scaleX = 1f
                scaleY = 1f
            }
        },
    )
}

private const val TAG = "OngoingActivityChip"
// Used for end-to-end tests - if changing this, be sure to change the status bar e2e tests also.
private const val STATUS_BAR_CHIP_CONTENT_ID = "ongoing_activity_chip_content"
@IdRes private val CUSTOM_ICON_VIEW_ID = R.id.ongoing_activity_chip_custom_icon
