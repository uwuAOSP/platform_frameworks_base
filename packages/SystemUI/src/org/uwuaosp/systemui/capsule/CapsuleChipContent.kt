/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.capsule

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.compose.ChipContent
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel

/** Expanded capsule text must not disappear just because its full version does not fit. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CapsuleChipContent(
    content: OngoingActivityChipModel.Content,
    icon: OngoingActivityChipModel.ChipIcon?,
    colors: ColorsModel,
    showFullText: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val variants = when (content) {
        is OngoingActivityChipModel.Content.Text -> listOf(content.text)
        is OngoingActivityChipModel.Content.TextVariants -> content.textVariants
        else -> {
            // Preserve native timer/countdown formatting and time sources.
            ChipContent(viewModel = content, icon = icon, colors = colors, modifier = modifier)
            return
        }
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLargeEmphasized
    val measurer = rememberTextMeasurer()
    val startPadding = if (icon != null && !icon.hasEmbeddedPadding) {
        dimensionResource(R.dimen.ongoing_activity_chip_icon_text_padding)
    } else 0.dp
    val endPadding = if (icon?.hasEmbeddedPadding == true) {
        dimensionResource(R.dimen.ongoing_activity_chip_text_end_padding_for_embedded_padding_icon)
    } else 0.dp
    if (showFullText) {
        // Navigation text is expected to fit the available status-bar region. Keep the app's
        // preferred version and natural width; do not apply the ordinary chip cap or ellipsis.
        variants.firstOrNull { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                color = Color(colors.text(context)),
                style = style,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = modifier.padding(start = startPadding, end = endPadding),
            )
        }
        return
    }
    val maxTextWidth = dimensionResource(R.dimen.ongoing_activity_chip_max_text_width)

    BoxWithConstraints(
        modifier = modifier.widthIn(max = maxTextWidth)
            .padding(start = startPadding, end = endPadding),
    ) {
        val availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            with(density) { maxTextWidth.roundToPx() }
        }
        val text = capsuleTextForWidth(variants, availableWidth) { candidate ->
            measurer.measure(text = candidate, style = style, softWrap = false).size.width
        }
        if (text != null) {
            Text(
                text = text,
                color = Color(colors.text(context)),
                style = style,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Respect app preference when possible, otherwise ellipsize the narrowest nonblank variant. */
internal fun capsuleTextForWidth(
    variants: List<String>,
    availableWidth: Int,
    measureWidth: (String) -> Int,
): String? {
    val measured = variants.filter { it.isNotBlank() }.map { it to measureWidth(it) }
    return measured.firstOrNull { it.second <= availableWidth }?.first
        ?: measured.minByOrNull { it.second }?.first
}
