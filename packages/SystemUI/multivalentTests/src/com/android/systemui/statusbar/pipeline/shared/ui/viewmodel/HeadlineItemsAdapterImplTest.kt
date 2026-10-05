/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.headline.ui.viewmodel.HeadlineItemKey
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel.ClickBehavior
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel.Content
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModel
import com.android.systemui.statusbar.notification.shared.StatusBarHeadline
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.StatusBarVisibilityInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
class HeadlineItemsAdapterImplTest : SysuiTestCase() {

    @Test
    @EnableFlags(StatusBarHeadline.FLAG_NAME)
    fun headlineItems_excludesHiddenChips() = runTest {
        val visibilityInteractor = mock<StatusBarVisibilityInteractor>()
        whenever(visibilityInteractor.canShowOngoingActivityChips).thenReturn(flowOf(true))
        val chipsViewModel = mock<OngoingActivityChipsViewModel>()
        whenever(chipsViewModel.chips)
            .thenReturn(
                MutableStateFlow(
                    MultipleOngoingActivityChipsModel(
                        active =
                            listOf(
                                chip(key = "hidden", isHidden = true),
                                chip(key = "visible", isHidden = false),
                            )
                    )
                )
            )

        val items =
            HeadlineItemsAdapterImpl(visibilityInteractor, chipsViewModel).headlineItems.first()

        assertThat(items.map { it.key }).containsExactly(HeadlineItemKey("visible"))
    }

    private fun chip(key: String, isHidden: Boolean) =
        OngoingActivityChipModel.Active(
            key = key,
            icon = null,
            content = Content.Text(key),
            colors = ColorsModel.AccentThemed,
            clickBehavior = ClickBehavior.None,
            isHidden = isHidden,
        )
}
