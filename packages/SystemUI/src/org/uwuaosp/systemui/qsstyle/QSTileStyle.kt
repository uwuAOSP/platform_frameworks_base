/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.qsstyle

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Tile rendering style for Quick QS and Quick Settings.
 *
 * The active style is persisted in [android.provider.Settings.Secure.UWU_QS_STYLE] and observed
 * reactively by [QSStyleRepository], so switching styles applies immediately without restarting
 * SystemUI.
 */
enum class QSTileStyle {
    /** Default AOSP style. Kept untouched and used unless the circular style is selected. */
    DEFAULT,

    /** Circular icon tiles, with a single row of five columns in the Quick QS panel. */
    CIRCULAR;

    val isCircular: Boolean
        get() = this == CIRCULAR

    companion object {
        /** `Settings.Secure.UWU_QS_STYLE` value for [DEFAULT]. */
        const val SETTING_DEFAULT = 0

        /** `Settings.Secure.UWU_QS_STYLE` value for [CIRCULAR]. */
        const val SETTING_CIRCULAR = 1

        /** Single cell tiles per row in the circular Quick QS panel. */
        const val CIRCULAR_QQS_COLUMNS = 5

        /** Tile rows shown in the circular Quick QS panel. */
        const val CIRCULAR_QQS_ROWS = 1

        fun fromSetting(value: Int): QSTileStyle =
            if (value == SETTING_CIRCULAR) CIRCULAR else DEFAULT
    }
}

/**
 * Carries the active [QSTileStyle] down to the Quick Settings tile composables.
 *
 * Provided once around the Quick QS / Quick Settings scene content; tiles read it to pick their
 * shape and label behavior.
 */
val LocalQSTileStyle = compositionLocalOf { QSTileStyle.DEFAULT }

/**
 * Corner radius used by the circular style. Corner radii are clamped to half of the shape size, so
 * this renders any tile or icon background as a perfect circle.
 */
val CircularCornerRadius = 1000.dp

/**
 * Tile height used by the circular style. Taller than the default so the circular icon and the two
 * centered label lines below it fit without clipping.
 */
val CircularTileHeight = 92.dp
