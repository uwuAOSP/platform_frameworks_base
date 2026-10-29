/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.ui.composable

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Let an intentional horizontal shade swipe win over a header's embedded clickable Android views.
 * Taps are untouched. [blockVerticalScroll] prevents otherwise useless vertical drags in static
 * clock/metadata regions from scrolling the shade. Only apply this to shade headers and media
 * metadata, never to notification rows, tiles, or sliders. On media, [diagonalOnly] leaves
 * horizontal carousel gestures to the card while allowing diagonal gestures to switch panels.
 */
@Composable
fun Modifier.shadePanelSwipe(
    enabled: Boolean,
    diagonalOnly: Boolean = false,
    blockVerticalScroll: Boolean = false,
): Modifier {
    if (!enabled) return this

    val dispatcher = remember { NestedScrollDispatcher() }
    val connection = remember { object : NestedScrollConnection {} }
    return nestedScroll(connection, dispatcher).pointerInput(
        dispatcher,
        diagonalOnly,
        blockVerticalScroll,
    ) {
        val gestureContext = currentCoroutineContext()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var dragging = false
            var blockingDrag = false
            var velocity = Velocity.Zero
            var released = false
            var consumedX = 0f
            fun scroll(delta: Float): Float {
                val available = Offset(delta, 0f)
                val preConsumed =
                    dispatcher.dispatchPreScroll(available, NestedScrollSource.UserInput)
                val postConsumed =
                    dispatcher.dispatchPostScroll(
                        consumed = Offset.Zero,
                        available = available - preConsumed,
                        source = NestedScrollSource.UserInput,
                    )
                return preConsumed.x + postConsumed.x
            }
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPointerInputChange(change)
                    if (!change.pressed) {
                        if (dragging || blockingDrag) {
                            change.consume()
                        }
                        released = true
                        if (dragging) {
                            val maximumVelocity = viewConfiguration.maximumFlingVelocity
                            velocity =
                                tracker.calculateVelocity(
                                    Velocity(maximumVelocity, maximumVelocity)
                                )
                        }
                        break
                    }
                    if (blockingDrag) {
                        change.consume()
                        continue
                    }

                    var delta = change.positionChangeIgnoreConsumed().x
                    if (!dragging) {
                        val displacement = change.position - down.position
                        val x = abs(displacement.x)
                        val y = abs(displacement.y)
                        val distance = hypot(x, y)
                        if (distance <= viewConfiguration.touchSlop) continue
                        // 方向边界与FalsingDataProvider一致，避免同一斜滑被判成两个方向
                        if (x <= y) {
                            if (!blockVerticalScroll) break
                            blockingDrag = true
                            change.consume()
                            continue
                        }
                        if (diagonalOnly && y < x * 0.3f) break
                        delta = displacement.x * (1f - viewConfiguration.touchSlop / distance)
                    }

                    consumedX += scroll(delta)
                    dragging = true
                    // Initial-pass consumption cancels the clock's click before it sees this move.
                    change.consume()
                }
            } finally {
                if (dragging) {
                    val horizontalVelocity = Velocity(velocity.x, 0f)
                    val returnDrag = !released && gestureContext.isActive
                    // 取消或多指打断时归还拖动量，停止任务随父级dispatcher完成
                    dispatcher.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        if (returnDrag) scroll(-consumedX)
                        val consumed = dispatcher.dispatchPreFling(horizontalVelocity)
                        dispatcher.dispatchPostFling(Velocity.Zero, horizontalVelocity - consumed)
                    }
                }
            }
        }
    }
}
