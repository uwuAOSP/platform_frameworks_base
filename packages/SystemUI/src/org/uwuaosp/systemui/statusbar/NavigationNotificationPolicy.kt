/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.statusbar

/** Dedicated navigation forwarders whose notifications should always enter the live-chip path. */
object NavigationNotificationPolicy {
    // Maintain package names here instead of duplicating allowlists across extraction and rendering.
    val packages: Set<String> = setOf(
        "com.oplus.pantanal.ums",
    )

    fun isNavigationForwarder(packageName: String): Boolean = packageName in packages
}
