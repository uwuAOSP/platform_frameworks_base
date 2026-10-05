/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.capsule

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.android.compose.theme.PlatformTheme
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.statusbar.chips.StatusBarChipsReturnAnimations
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.layout.ui.viewmodel.AppHandlesViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import com.android.systemui.res.R
import com.android.systemui.util.boundsOnScreen
import kotlinx.coroutines.delay

/** A single phone renderer, independent of the Headline feature flag and the left-side row. */
internal fun addOngoingActivityCapsule(
    statusBar: PhoneStatusBarView,
    viewModel: HomeStatusBarViewModel,
    iconViewStore: NotificationIconContainerViewBinder.IconViewStore?,
    appHandles: AppHandlesViewModel,
    clockComposeView: View,
) {
    val state = (viewModel as CapsuleStatusBarHost).capsuleState
    val capsuleView = ComposeView(statusBar.context)
    capsuleView.setContent {
        PlatformTheme {
            val geometry = rememberCapsuleGeometry(statusBar, clockComposeView, capsuleView)
            val model by state.chips.collectAsState(
                com.android.systemui.statusbar.pipeline.shared.ui.model.ChipsVisibilityModel(
                    com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel(),
                    false,
                )
            )
            val chips =
                if (model.areChipsAllowed) model.chips.active.filterNot { it.isHidden }
                else emptyList()
            if (StatusBarChipsReturnAnimations.isEnabled) {
                SideEffect {
                    model.chips.active.forEach { it.transitionManager?.registerTransition?.invoke() }
                    (model.chips.inactive + model.chips.overflow).forEach {
                        it.transitionManager?.unregisterTransition?.invoke()
                    }
                }
            }
            DisposableEffect(viewModel) {
                onDispose { state.onOngoingActivityChipBoundsChanged(Rect()) }
            }
            if (chips.isEmpty()) {
                SideEffect { state.onOngoingActivityChipBoundsChanged(Rect()) }
            } else {
                val obstacles =
                    appHandles.appHandleBounds
                        .filterNot { it.isEmpty }
                        .map { Rect(it).apply { offset(-geometry.screenX, -geometry.screenY) } }
                        .filter { it.top < geometry.height && it.bottom > 0 }
                OngoingActivityCapsule(
                    chips = chips,
                    geometry = geometry,
                    obstacles = obstacles,
                    iconViewStore = iconViewStore,
                    onBoundsChanged = state::onOngoingActivityChipBoundsChanged,
                    onChipBoundsChanged = viewModel::onChipBoundsChanged,
                )
            }
        }
    }
    statusBar.addView(
        capsuleView,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
}

internal data class CapsuleGeometry(
    val width: Int = 0,
    val height: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
    val screenX: Int = 0,
    val screenY: Int = 0,
    val camera: Rect? = null,
    val windowX: Int = 0,
    val windowY: Int = 0,
    val cutoutObstacle: Rect? = null,
)

/** Observe immutable geometry, not a layout counter: identical layouts do not recompose the UI. */
@Composable
private fun rememberCapsuleGeometry(
    statusBar: PhoneStatusBarView,
    clockComposeView: View,
    capsuleView: View,
): CapsuleGeometry {
    fun readGeometry(): CapsuleGeometry {
        // FrameLayout applies PhoneStatusBarView's safe-inset padding to this child. Use the
        // renderer's own origin rather than assuming its origin is the status bar's origin.
        val origin = IntArray(2).also(capsuleView::getLocationOnScreen)
        val contents = statusBar.requireViewById<View>(R.id.status_bar_contents)
        val clock = statusBar.requireViewById<View>(R.id.clock)
        val end = statusBar.requireViewById<View>(R.id.status_bar_end_side_content)
        val isRtl = statusBar.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val contentBounds = contents.boundsOnScreen
        var left = contentBounds.left + contents.paddingLeft - origin[0]
        var right = contentBounds.right - contents.paddingRight - origin[0]
        if (end.isShown) {
            if (isRtl) left = maxOf(left, end.boundsOnScreen.right - origin[0])
            else right = minOf(right, end.boundsOnScreen.left - origin[0])
        }
        listOf(clock, clockComposeView).filter { it.isShown && it.width > 0 }.forEach {
            if (isRtl) right = minOf(right, it.boundsOnScreen.left - origin[0])
            else left = maxOf(left, it.boundsOnScreen.right - origin[0])
        }
        val cutout = statusBar.rootWindowInsets?.displayCutout ?: statusBar.context.display?.cutout
        val camera = cutout?.boundingRectTop?.takeUnless { it.isEmpty }?.let { topBounds ->
            // A cutout's safe rectangle may include the area above a floating punch hole.
            // Restrict the path to the top opening; do not union side/bottom display cutouts.
            val actualBounds = cutout.cutoutPath?.let { source ->
                val path = Path(source)
                val topRegion = Path().apply {
                    addRect(RectF(topBounds), Path.Direction.CW)
                }
                path.op(topRegion, Path.Op.INTERSECT)
                RectF().also { path.computeBounds(it, true) }.takeUnless { it.isEmpty }
                    ?.let { Rect().apply { it.roundOut(this) } }
            } ?: Rect()
            cameraOpeningBounds(topBounds, actualBounds)
                .apply { offset(-origin[0], -origin[1]) }
        }?.takeUnless { it.isEmpty }
        val cutoutObstacle = cutout?.boundingRectTop?.takeUnless { it.isEmpty }?.let {
            Rect(it).apply { offset(-origin[0], -origin[1]) }
        }
        val windowOrigin = IntArray(2).also(capsuleView::getLocationInWindow)
        return CapsuleGeometry(
            width = capsuleView.width,
            height = capsuleView.height,
            left = left,
            right = right,
            screenX = origin[0],
            screenY = origin[1],
            camera = camera,
            windowX = windowOrigin[0],
            windowY = windowOrigin[1],
            cutoutObstacle = cutoutObstacle,
        )
    }
    var geometry by remember(statusBar, clockComposeView, capsuleView) {
        mutableStateOf(readGeometry())
    }
    DisposableEffect(statusBar, clockComposeView, capsuleView) {
        val observer = ViewTreeObserver.OnGlobalLayoutListener { geometry = readGeometry() }
        statusBar.viewTreeObserver.addOnGlobalLayoutListener(observer)
        onDispose {
            if (statusBar.viewTreeObserver.isAlive) {
                statusBar.viewTreeObserver.removeOnGlobalLayoutListener(observer)
            }
        }
    }
    return geometry
}

/** Content slot with an optional camera-facing fixed edge, always in physical coordinates. */
internal data class CapsuleSlot(val left: Int, val right: Int, val cameraOnRight: Boolean? = null) {
    val width: Int get() = (right - left).coerceAtLeast(0)
}

internal fun capsuleSlot(
    width: Int,
    left: Int,
    right: Int,
    camera: Rect?,
    obstacles: List<Rect>,
    minimumWidth: Int,
    gap: Int,
    maxCameraAspectRatio: Float = 2f,
): CapsuleSlot {
    val start = left.coerceIn(0, width.coerceAtLeast(0))
    val end = right.coerceIn(start, width.coerceAtLeast(0))
    var segments = listOf(CapsuleSlot(start, end))
    for (obstacle in obstacles + listOfNotNull(camera)) {
        segments = segments.flatMap { segment ->
            if (obstacle.right <= segment.left || obstacle.left >= segment.right) listOf(segment)
            else buildList {
                if (obstacle.left > segment.left) add(CapsuleSlot(segment.left, obstacle.left))
                if (obstacle.right < segment.right) add(CapsuleSlot(obstacle.right, segment.right))
            }
        }
    }
    // Only join a camera if the chosen segment actually touches it. Never bridge an app handle.
    val joinableCamera = camera?.takeIf {
        it.width() <= maxCameraAspectRatio * it.height() && it.left - gap >= start && it.right + gap <= end &&
            obstacles.none { obstacle -> Rect.intersects(obstacle, it) }
    }
    val before =
        joinableCamera?.let { hole -> segments.firstOrNull { it.right == hole.left } }
            ?.let { CapsuleSlot(it.left, (it.right - gap).coerceAtLeast(it.left), true) }
    val after =
        joinableCamera?.let { hole -> segments.firstOrNull { it.left == hole.right } }
            ?.let { CapsuleSlot((it.left + gap).coerceAtMost(it.right), it.right, false) }
    if (before != null && before.width >= minimumWidth) return before
    if (after != null && after.width >= minimumWidth) return after
    return segments.maxByOrNull { it.width } ?: CapsuleSlot(start, start)
}

internal fun capsuleContentLeft(slot: CapsuleSlot, contentWidth: Int): Int =
    when (slot.cameraOnRight) {
        true -> slot.right - contentWidth
        false -> slot.left
        null -> slot.left + (slot.width - contentWidth) / 2
    }

/** Path bounds already use display pixels; the safe rectangle is not a scaling reference. */
internal fun cameraOpeningBounds(safeBounds: Rect, pathBounds: Rect): Rect {
    // An unknown path is an obstacle, not enough information to size a camera capsule.
    if (safeBounds.isEmpty || pathBounds.isEmpty || pathBounds.width() <= 0) return Rect()
    return Rect(pathBounds)
}

/** Frame 3.svg: a 24-unit camera sits inside a 28-unit capsule including its outline. */
internal fun capsuleHeightForCamera(camera: Rect): Int =
    OngoingActivityCapsuleStyle.heightForCamera(camera.height())

/** Keep native ranking within each role; privacy activities occupy the rear segments. */
internal fun capsuleActivitiesInDisplayOrder(
    chips: List<OngoingActivityChipModel.Active>,
): List<OngoingActivityChipModel.Active> {
    val primary = chips.firstOrNull { !it.isImportantForPrivacy } ?: chips.firstOrNull()
        ?: return emptyList()
    return listOf(primary) + chips.filter { it.key != primary.key }
}

/** Main segment first, followed by outer segments; all rectangles share the same baseline. */
internal fun capsuleSegments(
    slot: CapsuleSlot,
    widths: List<Int>,
    overlap: Int,
    top: Int,
    height: Int,
): List<Rect> {
    if (widths.isEmpty()) return emptyList()
    val contentWidth = (widths.sum() - overlap * (widths.size - 1)).coerceAtLeast(0)
    val left = capsuleContentLeft(slot, contentWidth)
    var cursor = if (slot.cameraOnRight == false) left else left + contentWidth
    return widths.map { width ->
        val x = if (slot.cameraOnRight == false) cursor else cursor - width
        if (slot.cameraOnRight == false) cursor += width - overlap
        else cursor -= width - overlap
        Rect(x, top, x + width, top + height)
    }
}

/** Extend backgrounds beneath the next segment without overlapping content or hit regions. */
internal fun capsuleBackgrounds(
    bounds: List<Rect>,
    camera: Rect?,
    cameraOnRight: Boolean?,
): List<Rect> = bounds.mapIndexed { index, rect ->
    Rect(rect).apply {
        if (index == 0 && camera != null) {
            // Expand vertically and horizontally by the same amount to keep the camera and end
            // cap concentric. These bounds include the outline, which is drawn entirely inside.
            val cameraPadding = (rect.height() - camera.height()).coerceAtLeast(0) / 2
            if (cameraOnRight == true) right = camera.right + cameraPadding
            else left = camera.left - cameraPadding
        } else if (index > 0) {
            val neighbor = bounds[index - 1]
            val radius = neighbor.height() / 2
            if (cameraOnRight == false) left = neighbor.right - radius
            else right = neighbor.left + radius
        }
    }
}

@Composable
private fun OngoingActivityCapsule(
    chips: List<OngoingActivityChipModel.Active>,
    geometry: CapsuleGeometry,
    obstacles: List<Rect>,
    iconViewStore: NotificationIconContainerViewBinder.IconViewStore?,
    onBoundsChanged: (Rect) -> Unit,
    onChipBoundsChanged: (String, RectF) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Icon-to-camera spacing is supplied by the chip's proportional content padding.
    val gap = 0
    val capsuleHeight =
        (geometry.camera?.let(::capsuleHeightForCamera)
            ?: context.resources.getDimensionPixelSize(R.dimen.ongoing_activity_capsule_fallback_height))
            .coerceAtMost(geometry.height)
    val capsuleHeightDp = with(density) { capsuleHeight.toDp() }
    fun compactWidths(activities: List<OngoingActivityChipModel.Active>, cameraAdjacent: Boolean) =
        activities.mapIndexed { index, chip ->
            with(density) {
                OngoingActivityCapsuleStyle.metrics(
                    capsuleHeightDp, chip.icon, index == 0 && cameraAdjacent, context.resources,
                ).compactWidth.roundToPx()
            }
        }
    val requiredWidth = compactWidths(capsuleActivitiesInDisplayOrder(chips), geometry.camera != null).sum()
    val effectiveObstacles =
        obstacles + if (geometry.camera == null) listOfNotNull(geometry.cutoutObstacle) else emptyList()
    val slot =
        capsuleSlot(
            width = geometry.width,
            left = geometry.left,
            right = geometry.right,
            camera = geometry.camera,
            obstacles = effectiveObstacles,
            minimumWidth = requiredWidth,
            gap = gap,
            maxCameraAspectRatio = context.resources.getFraction(
                R.fraction.ongoing_activity_capsule_max_camera_aspect_ratio, 1, 1,
            ),
        )
    val cameraAdjacent = slot.cameraOnRight != null
    val visible = mutableListOf<OngoingActivityChipModel.Active>()
    val allFit = compactWidths(capsuleActivitiesInDisplayOrder(chips), cameraAdjacent).sum() <= slot.width
    val candidates = if (allFit) chips else chips.sortedByDescending { it.isImportantForPrivacy }
    for (chip in candidates) {
        val candidate = capsuleActivitiesInDisplayOrder(visible + chip)
        if (compactWidths(candidate, cameraAdjacent).sum() <= slot.width) visible += chip
    }
    // Preserve the existing privacy affordance even if its content must be constrained to fit.
    if (visible.isEmpty() && slot.width > 0) {
        chips.firstOrNull { it.isImportantForPrivacy }?.let { visible += it }
    }
    if (slot.width <= 0 || visible.isEmpty()) {
        SideEffect { onBoundsChanged(Rect()) }
        return
    }
    val ordered = capsuleActivitiesInDisplayOrder(visible)
    val minimumWidths = compactWidths(ordered, cameraAdjacent)
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val expandedStillActive = ordered.any { it.key == expandedKey }
    val collapseTimeoutMillis = ongoingActivityCapsuleTimeoutMillis()
    LaunchedEffect(expandedKey, expandedStillActive, collapseTimeoutMillis) {
        if (!expandedStillActive) expandedKey = null
        else if (expandedKey != null) {
            delay(collapseTimeoutMillis)
            expandedKey = null
        }
    }
    var segmentBounds by remember { mutableStateOf<List<Rect>>(emptyList()) }
    var occupiedBounds by remember { mutableStateOf(Rect()) }
    SideEffect {
        onBoundsChanged(
            if (occupiedBounds.isEmpty) Rect() else Rect(occupiedBounds).apply {
                offset(geometry.windowX, geometry.windowY)
            }
        )
    }
    // Activity models own semantic colors; presentation must not rewrite them based on RGB role.
    val colors = ordered.map { it.colors }
    val fills = colors.mapIndexed { index, color ->
        if (ordered[index].transitionManager?.hideChipForTransition == true) Color.Transparent
        else Color(color.background(context).defaultColor)
    }
    val outlines = colors.mapIndexed { index, color ->
        if (ordered[index].transitionManager?.hideChipForTransition == true) null
        else color.outline(context)?.let(::Color)
    }
    val outlineWidth =
        context.resources.getDimension(R.dimen.ongoing_activity_chip_outline_width)
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val hole = geometry.camera.takeIf { slot.cameraOnRight != null }
    Layout(
        modifier = Modifier.fillMaxSize().drawBehind {
            // Draw outer segments first; the main segment covers their joining edge without a gap.
            (0 until minOf(segmentBounds.size, fills.size)).reversed().forEach { index ->
                val bounds = segmentBounds[index]
                val size = Size(bounds.width().toFloat(), bounds.height().toFloat())
                // Only the camera-adjacent main segment is a complete pill. Rear segments have
                // square camera-facing edges hidden under the main segment's rounded leading edge.
                fun segmentPath(inset: Float): ComposePath {
                    val radius = CornerRadius((size.height / 2f - inset).coerceAtLeast(0f))
                    val leftRadius =
                        if (index > 0 && slot.cameraOnRight == false) CornerRadius.Zero else radius
                    val rightRadius =
                        if (index > 0 && slot.cameraOnRight != false) CornerRadius.Zero else radius
                    return ComposePath().apply {
                        addRoundRect(
                            RoundRect(
                                left = bounds.left + inset,
                                top = bounds.top + inset,
                                right = bounds.right - inset,
                                bottom = bounds.bottom - inset,
                                topLeftCornerRadius = leftRadius,
                                bottomLeftCornerRadius = leftRadius,
                                topRightCornerRadius = rightRadius,
                                bottomRightCornerRadius = rightRadius,
                            )
                        )
                    }
                }
                drawPath(segmentPath(0f), fills[index])
                outlines[index]?.let { color ->
                    val inset = outlineWidth / 2f
                    drawPath(
                        path = segmentPath(inset),
                        color = color,
                        style = Stroke(outlineWidth),
                    )
                }
            }
        },
        content = {
            ordered.forEachIndexed { index, chip ->
                key(chip.key) {
                    val compact = expandedKey != chip.key
                    CapsuleActivityChip(
                        model = chip,
                        iconViewStore = iconViewStore,
                        isCompact = compact,
                        isCapsuleSegment = true,
                        isCameraAdjacentCapsuleSegment = index == 0 && hole != null,
                        capsuleHeight = capsuleHeightDp,
                        showCompactContent = false,
                        capsuleInteractionSource = interactionSource,
                        onExpand = { expandedKey = chip.key },
                        modifier = Modifier.sysuiResTag(chip.key).onGloballyPositioned { coordinates ->
                            chip.notificationKey?.let { notificationKey ->
                                val bounds = coordinates.boundsInWindow()
                                onChipBoundsChanged(
                                    notificationKey,
                                    RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
                                )
                            }
                        },
                    )
                }
            }
            // Child chips own their hit regions; one shared indication is drawn over the full
            // composite pill so presses on either segment animate across the entire capsule.
            Box(
                Modifier.clip(CircleShape)
                    .indication(interactionSource, indication)
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        var remaining = slot.width
        val chipMeasurables = measurables.take(ordered.size)
        val indicationMeasurable = measurables.last()
        val placeables = chipMeasurables.mapIndexed { index, measurable ->
            val reserve =
                minimumWidths.drop(index + 1).sum()
            val budget = (remaining - reserve).coerceAtLeast(0)
            measurable.measure(
                Constraints(
                    maxWidth = budget,
                    minHeight = capsuleHeight,
                    maxHeight = capsuleHeight,
                )
            ).also {
                remaining = (remaining - it.width).coerceAtLeast(0)
            }
        }
        // Include the SVG's proportional padding around the camera. Expanded content uses this
        // same height, so expanding a segment changes the composite width only.
        val pillHeight = capsuleHeight
        val top =
            ((hole?.centerY() ?: (height / 2)) - pillHeight / 2)
                .coerceIn(0, (height - pillHeight).coerceAtLeast(0))
        val bounds = capsuleSegments(slot, placeables.map { it.width }, 0, top, pillHeight)
        val backgrounds = capsuleBackgrounds(bounds, hole, slot.cameraOnRight)
        segmentBounds = if (slot.width > 0) backgrounds else emptyList()
        occupiedBounds =
            if (slot.width > 0) {
                backgrounds.fold(Rect()) { result, rect -> result.apply { union(rect) } }
            } else Rect()
        val interactionBounds =
            backgrounds.fold(Rect()) { result, rect -> result.apply { union(rect) } }
        val indicationPlaceable =
            indicationMeasurable.measure(
                Constraints.fixed(
                    width = interactionBounds.width().coerceAtLeast(1),
                    height = pillHeight.coerceAtLeast(1),
                )
            )
        layout(width, height) {
            if (slot.width > 0) placeables.forEachIndexed { index, placeable ->
                // Physical placement is intentional: RTL must not invert camera coordinates.
                placeable.place(
                    x = bounds[index].left,
                    y = top + (pillHeight - placeable.height) / 2,
                    zIndex = (placeables.size - index).toFloat(),
                )
            }
            if (slot.width > 0) {
                indicationPlaceable.place(
                    x = interactionBounds.left,
                    y = top,
                    zIndex = (placeables.size + 1).toFloat(),
                )
            }
        }
    }
}
