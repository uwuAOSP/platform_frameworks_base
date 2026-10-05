/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.capsule

import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import org.uwuaosp.systemui.statusbar.OngoingActivityPresentation
import org.uwuaosp.systemui.statusbar.OngoingActivityPresentationConfig

/** Only the camera presentation enters this implementation; other styles keep native paths. */
object CapsuleStatusBarIntegration {
    fun isEnabled(viewModel: HomeStatusBarViewModel): Boolean =
        when (OngoingActivityPresentationConfig.presentation) {
            OngoingActivityPresentation.CAMERA_CAPSULE ->
                !viewModel.useDesktopStatusBar && viewModel is CapsuleStatusBarHost
            OngoingActivityPresentation.NATIVE -> false
            // Reserved for the other designer's renderer. Use native UI until it is implemented.
            OngoingActivityPresentation.STATUS_BAR_CARD -> false
        }
}
