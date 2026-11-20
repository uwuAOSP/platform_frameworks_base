/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

/**
 * Pure-Java decision policy for Moment AppJump focus/back guarding and same-package SDK bridge
 * orientation protection. Kept free of Android runtime static dependencies for host unit testing.
 */
final class MomentAppJumpBridgePolicy {

    /** Mirrors {@link android.content.pm.ActivityInfo} screen orientation constants. */
    static final int SCREEN_ORIENTATION_UNSET = -2;
    static final int SCREEN_ORIENTATION_UNSPECIFIED = -1;
    static final int SCREEN_ORIENTATION_LANDSCAPE = 0;
    static final int SCREEN_ORIENTATION_PORTRAIT = 1;
    static final int SCREEN_ORIENTATION_BEHIND = 3;
    static final int SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6;
    static final int SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7;
    static final int SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8;
    static final int SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9;
    static final int SCREEN_ORIENTATION_USER_LANDSCAPE = 11;
    static final int SCREEN_ORIENTATION_USER_PORTRAIT = 12;
    static final int SCREEN_ORIENTATION_LOCKED = 14;

    /** Mirrors {@link android.content.res.Configuration} orientation constants. */
    static final int ORIENTATION_UNDEFINED = 0;
    static final int ORIENTATION_PORTRAIT = 1;
    static final int ORIENTATION_LANDSCAPE = 2;

    static final long PHASE_B_TIER1_TIMEOUT_MS = 400L;
    static final long PHASE_B_TIER2_HOLD_MS = 150L;
    static final long POST_SESSION_EXIT_GRACE_MS = 400L;

    enum ProtectionMode {
        NONE,
        TIER1_BEHIND,
        TIER2_DISPLAY_HOLD
    }

    private MomentAppJumpBridgePolicy() {}

    static boolean isFixedOrientationLandscape(int orientation) {
        return orientation == SCREEN_ORIENTATION_LANDSCAPE
                || orientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || orientation == SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                || orientation == SCREEN_ORIENTATION_USER_LANDSCAPE;
    }

    static boolean isFixedOrientationPortrait(int orientation) {
        return orientation == SCREEN_ORIENTATION_PORTRAIT
                || orientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == SCREEN_ORIENTATION_REVERSE_PORTRAIT
                || orientation == SCREEN_ORIENTATION_USER_PORTRAIT;
    }

    static boolean isKnownBridgeComponentName(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        final int lastDot = className.lastIndexOf('.');
        final int lastDollar = className.lastIndexOf('$');
        final int start = Math.max(lastDot, lastDollar) + 1;
        if (start >= className.length()) {
            return false;
        }
        final String simpleName = start > 0 ? className.substring(start) : className;
        if (simpleName.endsWith("AssistActivity")
                || simpleName.equals("WXEntryActivity")
                || simpleName.equals("WXPayEntryActivity")
                || simpleName.equals("AuthActivity")) {
            return true;
        }
        return simpleName.startsWith("Assist")
                && (simpleName.endsWith("PortraitActivity")
                        || simpleName.endsWith("BehindActivity"));
    }

    static boolean isHighConfidenceBridge(boolean styleFillsParent, boolean occludesParent,
            boolean isNoDisplay, String componentClassName) {
        return !styleFillsParent || !occludesParent || isNoDisplay
                || isKnownBridgeComponentName(componentClassName);
    }

    static boolean shouldProtectCandidateOrientation(int rawOrientation,
            boolean providesOrientation) {
        if (isFixedOrientationLandscape(rawOrientation)
                || rawOrientation == SCREEN_ORIENTATION_BEHIND
                || rawOrientation == SCREEN_ORIENTATION_UNSET) {
            return false;
        }
        if (rawOrientation == SCREEN_ORIENTATION_UNSPECIFIED) {
            return providesOrientation;
        }
        return true;
    }

    static boolean isDefiningOrientationCandidate(int rawOrientation,
            boolean providesOrientation) {
        return rawOrientation != SCREEN_ORIENTATION_BEHIND
                && rawOrientation != SCREEN_ORIENTATION_UNSET
                && (rawOrientation != SCREEN_ORIENTATION_UNSPECIFIED || providesOrientation);
    }

    static boolean isLandscapeDefiningActivity(int rawOrientation,
            int requestedConfigOrientation, int currentConfigOrientation, boolean phaseA) {
        if (isFixedOrientationLandscape(rawOrientation)
                || requestedConfigOrientation == ORIENTATION_LANDSCAPE) {
            return true;
        }
        return phaseA && currentConfigOrientation == ORIENTATION_LANDSCAPE
                && !isFixedOrientationPortrait(rawOrientation);
    }

    static ProtectionMode classifyPhaseBBridge(boolean styleFillsParent, boolean occludesParent,
            boolean isNoDisplay, String componentClassName, int rawOrientation,
            boolean providesOrientation, boolean hasLandscapeDefiningBelow) {
        if (!hasLandscapeDefiningBelow
                || !shouldProtectCandidateOrientation(rawOrientation, providesOrientation)) {
            return ProtectionMode.NONE;
        }
        if (isHighConfidenceBridge(styleFillsParent, occludesParent, isNoDisplay,
                componentClassName)) {
            return ProtectionMode.TIER1_BEHIND;
        }
        return ProtectionMode.TIER2_DISPLAY_HOLD;
    }

    static boolean shouldGuardSourceTaskFocus(boolean autoDismiss, boolean dismissRequested,
            boolean momentAttached, boolean momentWindowingMode, boolean momentVisibleRequested,
            boolean sameRootTask, boolean momentCompact, boolean isNonRootSourceBridgeTop) {
        return autoDismiss && !dismissRequested && momentAttached && momentWindowingMode
                && momentVisibleRequested && sameRootTask
                && (!momentCompact || isNonRootSourceBridgeTop);
    }
}
