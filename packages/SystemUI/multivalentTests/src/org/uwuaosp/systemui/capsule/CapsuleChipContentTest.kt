/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.capsule

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.theme.PlatformTheme
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CapsuleChipContentTest : SysuiTestCase() {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun longNotificationText_firstTapRevealsText_secondTapInvokesNativeAction() {
        val compact = mutableStateOf(true)
        var actionCount = 0
        val text = "Notification text that is much too long to fit inside a status bar capsule"
        val model = OngoingActivityChipModel.Active(
            key = "notification",
            icon = OngoingActivityChipModel.ChipIcon.SingleColorIcon(
                Icon.Resource(R.drawable.ic_screenrecord, null)
            ),
            content = OngoingActivityChipModel.Content.Text(text),
            colors = ColorsModel.SystemThemed,
            clickBehavior = OngoingActivityChipModel.ClickBehavior.ExpandAction { actionCount++ },
        )
        composeRule.setContent {
            PlatformTheme {
                CapsuleActivityChip(
                    model = model,
                    iconViewStore = null,
                    isCompact = compact.value,
                    onExpand = { compact.value = false },
                    isCapsuleSegment = true,
                    capsuleHeight = 28.dp,
                    showCompactContent = false,
                    modifier = Modifier.width(100.dp).testTag("capsule"),
                )
            }
        }
        composeRule.onNodeWithText(text).assertDoesNotExist()
        composeRule.onNodeWithTag("capsule").performClick()
        composeRule.onNodeWithText(text).assertIsDisplayed()
        composeRule.runOnIdle { assertThat(actionCount).isEqualTo(0) }
        composeRule.onNodeWithTag("capsule").performClick()
        composeRule.runOnIdle { assertThat(actionCount).isEqualTo(1) }
    }

    @Test
    fun navigationText_keepsPreferredVersionWithoutEllipsisOrOrdinaryWidthCap() {
        val preferred = "Turn left in 100 metres"
        composeRule.setContent {
            PlatformTheme {
                CapsuleChipContent(
                    content = OngoingActivityChipModel.Content.TextVariants(listOf(preferred, "100 m")),
                    icon = null,
                    colors = ColorsModel.Custom(0xFF1565C0.toInt(), 0xFFFFFFFF.toInt()),
                    showFullText = true,
                    modifier = Modifier.width(300.dp),
                )
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(preferred).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        composeRule.onNodeWithText("100 m").assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(layouts).hasSize(1)
            assertThat(layouts.single().isLineEllipsized(0)).isFalse()
            assertThat(layouts.single().hasVisualOverflow).isFalse()
        }
    }

    @Test
    fun singleLongText_remainsAvailableForEllipsis() {
        assertThat(capsuleTextForWidth(listOf("long notification text"), 3) { it.length })
            .isEqualTo("long notification text")
    }

    @Test
    fun variants_preferFirstFittingVersion() {
        assertThat(capsuleTextForWidth(listOf("preferred", "medium", "tiny"), 6) { it.length })
            .isEqualTo("medium")
    }

    @Test
    fun noVariantFits_keepsNarrowestVersionForEllipsis() {
        assertThat(capsuleTextForWidth(listOf("preferred", "tiny", "medium"), 2) { it.length })
            .isEqualTo("tiny")
    }

    @Test
    fun narrowestVariant_usesMeasuredWidthNotCharacterCount() {
        assertThat(capsuleTextForWidth(listOf("WW", "iii"), 1) {
            if (it == "WW") 20 else 9
        }).isEqualTo("iii")
    }

    @Test
    fun blankVariants_doNotSuppressNonblankContent() {
        assertThat(capsuleTextForWidth(listOf("", " ", "content"), 2) { it.length })
            .isEqualTo("content")
        assertThat(capsuleTextForWidth(listOf("", " "), 2) { it.length }).isNull()
    }
}
