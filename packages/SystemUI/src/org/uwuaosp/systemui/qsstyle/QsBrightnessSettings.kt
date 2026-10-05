/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.qsstyle

import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Placement and visibility of the Quick Settings brightness slider.
 *
 * Ported from the LineageOS change "SystemUI: Allow to change brightness slider positioning in new
 * compose QS" (LineageOS/android_frameworks_base change 494272). The tunables live in
 * [Settings.Secure] instead of `LineageSettings.Secure`, because this tree does not include
 * lineage-sdk.
 */
data class QsBrightnessSettings(
    /**
     * Whether the slider sits above the tiles (the stock position). When
     * [Settings.Secure.UWU_QS_BRIGHTNESS_SLIDER_POSITION] has never been set, this follows the
     * tile style: the circular style places the slider below the tiles.
     */
    val sliderAtTop: Boolean,
    /** `0` = hidden, `1` = Quick Settings only, `2` = Quick Settings and Quick Quick Settings. */
    val showSlider: Int,
)

/**
 * Observes [Settings.Secure.UWU_QS_BRIGHTNESS_SLIDER_POSITION],
 * [Settings.Secure.UWU_QS_SHOW_BRIGHTNESS_SLIDER] and [Settings.Secure.UWU_QS_STYLE], recomposing
 * the caller when any of them changes.
 */
@Composable
fun rememberQsBrightnessSettings(): QsBrightnessSettings {
    val context = LocalContext.current
    val cr = remember { context.contentResolver }

    fun readCurrent(): QsBrightnessSettings {
        val position =
            runCatching {
                    Settings.Secure.getIntForUser(
                        cr,
                        Settings.Secure.UWU_QS_BRIGHTNESS_SLIDER_POSITION,
                        // Unset: fall back to the position implied by the tile style.
                        POSITION_UNSET,
                        UserHandle.USER_CURRENT,
                    )
                }
                .getOrElse { POSITION_UNSET }

        val sliderAtTop =
            when (position) {
                POSITION_TOP -> true
                POSITION_BOTTOM -> false
                else -> {
                    val style =
                        runCatching {
                                Settings.Secure.getIntForUser(
                                    cr,
                                    Settings.Secure.UWU_QS_STYLE,
                                    QSTileStyle.SETTING_DEFAULT,
                                    UserHandle.USER_CURRENT,
                                )
                            }
                            .getOrElse { QSTileStyle.SETTING_DEFAULT }
                    // The circular style puts the slider below the tiles. The default style keeps
                    // the stock position above them.
                    !QSTileStyle.fromSetting(style).isCircular
                }
            }

        val showSlider =
            runCatching {
                    Settings.Secure.getIntForUser(
                        cr,
                        Settings.Secure.UWU_QS_SHOW_BRIGHTNESS_SLIDER,
                        // Show the slider wherever the hosting surface used to show it.
                        DEFAULT_SHOW_SLIDER,
                        UserHandle.USER_CURRENT,
                    )
                }
                .getOrElse { DEFAULT_SHOW_SLIDER }

        return QsBrightnessSettings(sliderAtTop = sliderAtTop, showSlider = showSlider)
    }

    var state by remember { mutableStateOf(readCurrent()) }

    DisposableEffect(cr) {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { state = readCurrent() }
                }
            }

        cr.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.UWU_QS_BRIGHTNESS_SLIDER_POSITION),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        cr.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.UWU_QS_SHOW_BRIGHTNESS_SLIDER),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        cr.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.UWU_QS_STYLE),
            false,
            observer,
            UserHandle.USER_ALL,
        )

        onDispose { cr.unregisterContentObserver(observer) }
    }

    return state
}

/**
 * Default for [Settings.Secure.UWU_QS_SHOW_BRIGHTNESS_SLIDER]. Surfaces that predate the setting
 * keep showing the slider where they used to, while `0` still allows hiding it.
 */
private const val DEFAULT_SHOW_SLIDER = 2

/** [Settings.Secure.UWU_QS_BRIGHTNESS_SLIDER_POSITION] value that keeps the stock position. */
private const val POSITION_TOP = 0

/** [Settings.Secure.UWU_QS_BRIGHTNESS_SLIDER_POSITION] value that moves the slider below tiles. */
private const val POSITION_BOTTOM = 1

/** Returned by the settings provider when the position has never been set. */
private const val POSITION_UNSET = -1
