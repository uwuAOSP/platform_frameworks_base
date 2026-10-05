/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.statusbar

/** Independent phone presentations; each owns its layout and interaction policy. */
enum class OngoingActivityPresentation {
    NATIVE,
    CAMERA_CAPSULE,
    STATUS_BAR_CARD,
}

/**
 * Single selection point, intentionally not a resource flag or a user setting yet.
 * Set before creating the status bar; a future style switch can recreate it after changing this.
 */
object OngoingActivityPresentationConfig {
    var presentation: OngoingActivityPresentation = OngoingActivityPresentation.CAMERA_CAPSULE
}
