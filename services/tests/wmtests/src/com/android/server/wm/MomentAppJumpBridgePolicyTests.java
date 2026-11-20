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

import static com.android.server.wm.MomentAppJumpBridgePolicy.ORIENTATION_LANDSCAPE;
import static com.android.server.wm.MomentAppJumpBridgePolicy.ORIENTATION_PORTRAIT;
import static com.android.server.wm.MomentAppJumpBridgePolicy.ORIENTATION_UNDEFINED;
import static com.android.server.wm.MomentAppJumpBridgePolicy.PHASE_B_TIER1_TIMEOUT_MS;
import static com.android.server.wm.MomentAppJumpBridgePolicy.PHASE_B_TIER2_HOLD_MS;
import static com.android.server.wm.MomentAppJumpBridgePolicy.POST_SESSION_EXIT_GRACE_MS;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_BEHIND;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_LANDSCAPE;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_LOCKED;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_PORTRAIT;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_UNSET;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_UNSPECIFIED;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_USER_LANDSCAPE;
import static com.android.server.wm.MomentAppJumpBridgePolicy.SCREEN_ORIENTATION_USER_PORTRAIT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.server.wm.MomentAppJumpBridgePolicy.ProtectionMode;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Host JUnit4 tests for {@link MomentAppJumpBridgePolicy}. */
@RunWith(JUnit4.class)
public class MomentAppJumpBridgePolicyTests {

    @Test
    public void timeoutConstantsMatchDesignContract() {
        assertEquals(400L, PHASE_B_TIER1_TIMEOUT_MS);
        assertEquals(150L, PHASE_B_TIER2_HOLD_MS);
        assertEquals(400L, POST_SESSION_EXIT_GRACE_MS);
    }

    @Test
    public void knownBridgeComponentNamesMatchSdksAndRejectNormalActivities() {
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.tencent.connect.common.AssistActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.taptap.sdk.TapAssistActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.Outer$AssistActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.game.wxapi.WXEntryActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.game.wxapi.WXPayEntryActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.tencent.tauth.AuthActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "AssistActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.uwuaosp.momenttest.source.AssistTranslucentPortraitActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.uwuaosp.momenttest.source.AssistOpaquePortraitActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.uwuaosp.momenttest.source.AssistTranslucentBehindActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.uwuaosp.momenttest.source.AssistOpaqueBehindActivity"));

        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(null));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(""));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName("com.example."));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName("com.example.Outer$"));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.game.LoginActivity"));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.game.MainActivity"));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.game.SourceSubActivity"));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.game.WXEntryActivityHelper"));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.game.AuthActivityExtra"));
        assertFalse(MomentAppJumpBridgePolicy.isKnownBridgeComponentName(
                "com.example.game.AssistMainActivity"));
    }

    @Test
    public void highConfidenceBridgeDetectionCoversStyleAndKnownNames() {
        assertTrue(MomentAppJumpBridgePolicy.isHighConfidenceBridge(
                false /* styleFillsParent */, true /* occludesParent */, false /* isNoDisplay */,
                "com.example.game.CustomActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isHighConfidenceBridge(
                true /* styleFillsParent */, false /* occludesParent */, false /* isNoDisplay */,
                "com.example.game.CustomActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isHighConfidenceBridge(
                true /* styleFillsParent */, true /* occludesParent */, true /* isNoDisplay */,
                "com.example.game.CustomActivity"));
        assertTrue(MomentAppJumpBridgePolicy.isHighConfidenceBridge(
                true /* styleFillsParent */, true /* occludesParent */, false /* isNoDisplay */,
                "com.tencent.connect.common.AssistActivity"));

        assertFalse(MomentAppJumpBridgePolicy.isHighConfidenceBridge(
                true /* styleFillsParent */, true /* occludesParent */, false /* isNoDisplay */,
                "com.example.game.SourceSubActivity"));
    }

    @Test
    public void phaseBClassificationSplitsTier1Tier2AndExclusions() {
        // Tier 1: translucent/floating/NoDisplay or known SDK bridge over landscape below.
        assertEquals(ProtectionMode.TIER1_BEHIND,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        false, false, false, "com.example.game.UnknownPortrait",
                        SCREEN_ORIENTATION_PORTRAIT, false, true));
        assertEquals(ProtectionMode.TIER1_BEHIND,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        true, false, false, "com.example.game.FloatingPortrait",
                        SCREEN_ORIENTATION_PORTRAIT, true, true));
        assertEquals(ProtectionMode.TIER1_BEHIND,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        true, true, true, "com.example.game.NoDisplayBridge",
                        SCREEN_ORIENTATION_PORTRAIT, true, true));
        assertEquals(ProtectionMode.TIER1_BEHIND,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        true, true, false, "com.tencent.connect.common.AssistActivity",
                        SCREEN_ORIENTATION_PORTRAIT, true, true));
        assertEquals(ProtectionMode.TIER1_BEHIND,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        true, true, false, "com.example.game.wxapi.WXEntryActivity",
                        SCREEN_ORIENTATION_UNSPECIFIED, true, true));

        // Tier 2: opaque unknown activity with portrait or UNSPECIFIED orientation.
        assertEquals(ProtectionMode.TIER2_DISPLAY_HOLD,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        true, true, false, "com.example.game.SourceSubActivity",
                        SCREEN_ORIENTATION_PORTRAIT, true, true));
        assertEquals(ProtectionMode.TIER2_DISPLAY_HOLD,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        true, true, false, "com.example.game.LoginActivity",
                        SCREEN_ORIENTATION_UNSPECIFIED, true, true));

        // Excluded: no landscape below, landscape orientation, BEHIND, UNSET, or non-providing UNSPECIFIED.
        assertEquals(ProtectionMode.NONE,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        false, false, false, "com.tencent.connect.common.AssistActivity",
                        SCREEN_ORIENTATION_PORTRAIT, false, false));
        for (int landscape : new int[] {
                SCREEN_ORIENTATION_LANDSCAPE,
                SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                SCREEN_ORIENTATION_USER_LANDSCAPE
        }) {
            assertEquals(ProtectionMode.NONE,
                    MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                            false, false, false, "com.tencent.connect.common.AssistActivity",
                            landscape, false, true));
        }
        assertEquals(ProtectionMode.NONE,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        false, false, false, "com.tencent.connect.common.AssistActivity",
                        SCREEN_ORIENTATION_BEHIND, false, true));
        assertEquals(ProtectionMode.NONE,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        true, true, false, "com.tencent.connect.common.AssistActivity",
                        SCREEN_ORIENTATION_UNSET, true, true));
        assertEquals(ProtectionMode.NONE,
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        false, false, false, "com.tencent.connect.common.AssistActivity",
                        SCREEN_ORIENTATION_UNSPECIFIED, false, true));
    }

    @Test
    public void orientationHelpersHandlePhaseBPhaseAAndMixedStacks() {
        assertTrue(MomentAppJumpBridgePolicy.isFixedOrientationLandscape(
                SCREEN_ORIENTATION_LANDSCAPE));
        assertTrue(MomentAppJumpBridgePolicy.isFixedOrientationLandscape(
                SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
        assertTrue(MomentAppJumpBridgePolicy.isFixedOrientationLandscape(
                SCREEN_ORIENTATION_REVERSE_LANDSCAPE));
        assertTrue(MomentAppJumpBridgePolicy.isFixedOrientationLandscape(
                SCREEN_ORIENTATION_USER_LANDSCAPE));
        assertFalse(MomentAppJumpBridgePolicy.isFixedOrientationLandscape(
                SCREEN_ORIENTATION_PORTRAIT));
        assertFalse(MomentAppJumpBridgePolicy.isFixedOrientationLandscape(
                SCREEN_ORIENTATION_UNSPECIFIED));

        assertTrue(MomentAppJumpBridgePolicy.isFixedOrientationPortrait(
                SCREEN_ORIENTATION_PORTRAIT));
        assertTrue(MomentAppJumpBridgePolicy.isFixedOrientationPortrait(
                SCREEN_ORIENTATION_SENSOR_PORTRAIT));
        assertTrue(MomentAppJumpBridgePolicy.isFixedOrientationPortrait(
                SCREEN_ORIENTATION_REVERSE_PORTRAIT));
        assertTrue(MomentAppJumpBridgePolicy.isFixedOrientationPortrait(
                SCREEN_ORIENTATION_USER_PORTRAIT));
        assertFalse(MomentAppJumpBridgePolicy.isFixedOrientationPortrait(
                SCREEN_ORIENTATION_LANDSCAPE));
        assertFalse(MomentAppJumpBridgePolicy.isFixedOrientationPortrait(
                SCREEN_ORIENTATION_LOCKED));

        // Mixed stack defining candidates: skip BEHIND, UNSET, and translucent UNSPECIFIED;
        // stop at concrete defining activities (e.g. sensorLandscape Game above portrait Main).
        assertFalse(MomentAppJumpBridgePolicy.isDefiningOrientationCandidate(
                SCREEN_ORIENTATION_BEHIND, true));
        assertFalse(MomentAppJumpBridgePolicy.isDefiningOrientationCandidate(
                SCREEN_ORIENTATION_UNSET, true));
        assertFalse(MomentAppJumpBridgePolicy.isDefiningOrientationCandidate(
                SCREEN_ORIENTATION_UNSPECIFIED, false));
        assertTrue(MomentAppJumpBridgePolicy.isDefiningOrientationCandidate(
                SCREEN_ORIENTATION_UNSPECIFIED, true));
        assertTrue(MomentAppJumpBridgePolicy.isDefiningOrientationCandidate(
                SCREEN_ORIENTATION_PORTRAIT, false));
        assertTrue(MomentAppJumpBridgePolicy.isDefiningOrientationCandidate(
                SCREEN_ORIENTATION_SENSOR_LANDSCAPE, true));

        // Phase B requires fixed/requested landscape; Phase A also covers dynamic UNSPECIFIED in landscape.
        assertTrue(MomentAppJumpBridgePolicy.isLandscapeDefiningActivity(
                SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ORIENTATION_UNDEFINED, ORIENTATION_UNDEFINED,
                false));
        assertTrue(MomentAppJumpBridgePolicy.isLandscapeDefiningActivity(
                SCREEN_ORIENTATION_UNSPECIFIED, ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT,
                false));
        assertFalse(MomentAppJumpBridgePolicy.isLandscapeDefiningActivity(
                SCREEN_ORIENTATION_UNSPECIFIED, ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE,
                false));
        assertTrue(MomentAppJumpBridgePolicy.isLandscapeDefiningActivity(
                SCREEN_ORIENTATION_UNSPECIFIED, ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE,
                true));
        // Portrait activity must not be misclassified as landscape even if current config lags.
        assertFalse(MomentAppJumpBridgePolicy.isLandscapeDefiningActivity(
                SCREEN_ORIENTATION_PORTRAIT, ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE,
                true));
        assertFalse(MomentAppJumpBridgePolicy.isLandscapeDefiningActivity(
                SCREEN_ORIENTATION_PORTRAIT, ORIENTATION_PORTRAIT, ORIENTATION_PORTRAIT,
                true));
    }

    @Test
    public void candidateOrientationProtectionExcludesLandscapeBehindUnsetAndTranslucentUnspecified() {
        assertFalse(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_LANDSCAPE, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_SENSOR_LANDSCAPE, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_BEHIND, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_UNSET, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, false));

        assertTrue(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, true));
        assertTrue(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_PORTRAIT, false));
        assertTrue(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_SENSOR_PORTRAIT, true));
        assertTrue(MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                SCREEN_ORIENTATION_LOCKED, true));
    }

    @Test
    public void sourceTaskFocusGuardRespectsCompactBridgeHandoffAndDismissStates() {
        // Active Moment session (normal size) guards sourceTask.
        assertTrue(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true /* autoDismiss */, false /* dismissRequested */,
                true /* momentAttached */, true /* momentWindowingMode */,
                true /* momentVisibleOrHandoff */, true /* sameRootTask */,
                false /* momentCompact */, false /* isNonRootSourceBridgeTop */));

        // Compact mode releases guard for root source activity, but keeps it for non-root bridge.
        assertFalse(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true, false, true, true, true, true,
                true /* momentCompact */, false /* isNonRootSourceBridgeTop */));
        assertTrue(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true, false, true, true, true, true,
                true /* momentCompact */, true /* isNonRootSourceBridgeTop */));

        // Async handoff keeps momentVisibleOrHandoff=true -> remains guarded until dismiss/exit.
        assertTrue(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true, false, true, true, true, true, false, false));

        // Any termination, manual expansion, detachment, or unrelated task disables guard.
        assertFalse(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                false /* autoDismiss */, false, true, true, true, true, false, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true, true /* dismissRequested */, true, true, true, true, false, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true, false, false /* momentAttached */, true, true, true, false, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true, false, true, false /* momentWindowingMode */, true, true, false, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true, false, true, true, false /* momentVisibleOrHandoff */, true, false, true));
        assertFalse(MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                true, false, true, true, true, false /* sameRootTask */, false, true));
    }
}
