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

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MOMENT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for automatic Moment launches. Run with atest WmTests:MomentAppJumpTests. */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class MomentAppJumpTests extends WindowTestsBase {
    private static final String SOURCE = "com.test.game";
    private static final String TARGET = "com.test.share";
    private static final int SOURCE_UID = 10001;
    private static final int TARGET_UID = 10002;
    private MomentController mMoment;
    private ActivityRecord mSource;
    private ActivityInfo mTarget;

    @Before
    public void setUp() {
        mMoment = mWm.mMomentController;
        mMoment.setEnabledFromSettings(true);
        mMoment.setAppJumpSettingsFromSettings(0, true, SOURCE);
        mSource = new ActivityBuilder(mAtm).setCreateTask(true).setVisible(true)
                .setUid(SOURCE_UID).setComponent(new ComponentName(SOURCE, SOURCE + ".Game"))
                .build();
        mSource.getTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        mTarget = target(TARGET, TARGET + ".Share");
    }

    @Test
    public void selectedSourceLaunchesMomentWithoutChangingOriginalOptions() {
        final ActivityOptions original = ActivityOptions.makeBasic();
        final MomentController.AppJumpLaunch launch = prepare(mTarget, mSource, SOURCE,
                SOURCE_UID, original);
        assertNotNull(launch);
        assertSame(mSource.getTask(), launch.sourceTask);
        assertEquals(WINDOWING_MODE_MOMENT, launch.options.getLaunchWindowingMode());
        assertEquals(0, original.getLaunchWindowingMode());
        assertEquals(WINDOWING_MODE_FULLSCREEN, mSource.getTask().getWindowingMode());
    }

    @Test
    public void disabledOrUnselectedSourceDoesNotLaunchMoment() {
        mMoment.setAppJumpSettingsFromSettings(0, false, SOURCE);
        assertNull(prepare(mTarget));
        mMoment.setAppJumpSettingsFromSettings(0, true, "com.test.other");
        assertNull(prepare(mTarget));
        mMoment.setAppJumpSettingsFromSettings(0, true, SOURCE);
        mMoment.setEnabledFromSettings(false);
        assertNull(prepare(mTarget));
    }

    @Test
    public void samePackageAndSystemAppsAreExcluded() {
        assertNull(prepare(target(SOURCE, SOURCE + ".Details")));
        mTarget.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        assertNull(prepare(mTarget));
        mTarget.applicationInfo.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        assertNull(prepare(mTarget));
    }

    @Test
    public void systemMediatorsAreExcludedEvenWithoutSystemFlag() {
        for (ActivityInfo mediator : new ActivityInfo[] {
                target("com.android.intentresolver", "com.android.intentresolver.ChooserActivity"),
                target("com.android.permissioncontroller", "GrantPermissionsActivity"),
                target("com.android.vpndialogs", "ConfirmDialog"),
                target("com.android.providers.media.module",
                        "com.android.providers.media.photopicker.PhotoPickerActivity"),
                target("com.android.documentsui", "com.android.documentsui.picker.PickActivity")
        }) {
            assertNull(prepare(mediator));
        }
    }

    @Test
    public void chooserSelectionUsesVisibleFullscreenAppBelowIt() {
        final ActivityRecord chooser = new ActivityBuilder(mAtm).setCreateTask(true)
                .setVisible(true).setUid(10002)
                .setComponent(new ComponentName("com.android.intentresolver",
                        "com.android.intentresolver.ChooserActivity")).build();
        mSource.setVisibleRequested(true);
        final MomentController.AppJumpLaunch launch = prepare(mTarget, chooser,
                chooser.packageName, chooser.getUid(), null);
        assertNotNull(launch);
        assertSame(mSource.getTask(), launch.sourceTask);
    }

    @Test
    public void mismatchedCallerAndOtherUserAreExcluded() {
        assertNull(prepare(mTarget, mSource, SOURCE, 10003, null));
        mMoment.setAppJumpSettingsFromSettings(10, true, SOURCE);
        assertNull(prepare(mTarget));
    }

    @Test
    public void landscapeUsesPortraitLaunchBounds() {
        doReturn(new Rect(0, 0, 2000, 1000)).when(mSource.getDisplayContent()).getBounds();
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        assertEquals(new Rect(0, 0, 1000, 2000), launch.options.getLaunchBounds());
    }

    @Test
    public void explicitTaskOrHomeLaunchIsExcluded() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskId(mSource.getTask().mTaskId);
        assertNull(prepare(mTarget, mSource, SOURCE, SOURCE_UID, options));
        final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        assertNull(mMoment.prepareAppJumpMomentLaunchLocked(home, mTarget, mSource,
                SOURCE, SOURCE_UID, 0, null, null, null));
    }

    @Test
    public void sourceTaskAndUntrackedMomentTaskCannotBeReused() {
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        assertFalse(mMoment.canReuseAppJumpTaskLocked(launch, mSource.getTask()));
        final ActivityRecord other = new ActivityBuilder(mAtm).setCreateTask(true)
                .setComponent(new ComponentName(TARGET, TARGET + ".Main")).build();
        other.getTask().setWindowingMode(WINDOWING_MODE_MOMENT);
        assertFalse(mMoment.canReuseAppJumpTaskLocked(launch, other.getTask()));
    }

    @Test
    public void failedLaunchRestoresExistingTaskWithoutFinishingItsHistory() {
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        final ActivityRecord main = new ActivityBuilder(mAtm).setCreateTask(true)
                .setOnTop(false).setComponent(new ComponentName(TARGET, TARGET + ".Main"))
                .build();
        final Task task = main.getTask();
        final ActivityRecord history = new ActivityBuilder(mAtm).setTask(task).setOnTop(false)
                .setComponent(new ComponentName(TARGET, TARGET + ".Chat")).build();
        assertTrue(mMoment.canReuseAppJumpTaskLocked(launch, task));
        mMoment.onAppJumpTaskResolvedLocked(launch, task);
        assertTrue(launch.originalActivities.contains(main));
        assertTrue(launch.originalActivities.contains(history));

        task.setWindowingMode(WINDOWING_MODE_MOMENT);
        mMoment.onAppJumpLaunchFailedLocked(launch);

        assertTrue(task.isAttached());
        assertEquals(WINDOWING_MODE_FULLSCREEN, task.getWindowingMode());
        assertSame(task, main.getTask());
        assertSame(task, history.getTask());
        assertFalse(main.finishing);
        assertFalse(history.finishing);
    }

    @Test
    public void callbackUsesOriginalFullscreenTaskAndStopsAfterManualExpansion() {
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        final ActivityRecord entry = new ActivityBuilder(mAtm).setCreateTask(true)
                .setComponent(new ComponentName(TARGET, TARGET + ".Share")).build();
        final Task task = entry.getTask();
        task.setWindowingMode(WINDOWING_MODE_MOMENT);
        mMoment.onAppJumpLaunchSucceededLocked(launch, entry);

        final ActivityInfo callback = target(SOURCE, SOURCE + ".wxapi.WXEntryActivity");
        final MomentController.AppJumpLaunch returning = prepare(callback, entry,
                TARGET, entry.getUid(), null);
        assertNotNull(returning);
        assertTrue(returning.isReturnToSource());
        assertSame(mSource.getTask(), returning.sourceTask);
        assertEquals(WINDOWING_MODE_FULLSCREEN, returning.options.getLaunchWindowingMode());
        doReturn(task).when(mRootWindowContainer).getTopDisplayFocusedRootTask();
        final MomentController.AppJumpLaunch contextCallback = prepare(callback, null,
                TARGET, entry.getUid(), null);
        assertNotNull(contextCallback);
        assertSame(mSource.getTask(), contextCallback.sourceTask);

        mMoment.onTaskLeftMomentWindowingMode(task);
        assertNull(prepare(callback, entry, TARGET, entry.getUid(), null));
    }

    @Test
    public void onlyEmptyCanceledEntryResultIsDeferred() {
        final ActivityRecord entry = createMomentEntry();
        entry.resultTo = mSource;
        entry.requestCode = 10103;
        assertFalse(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_OK, null));
        assertFalse(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_CANCELED,
                new Intent("com.test.ACTUAL_RESULT")));
        final ActivityRecord child = new ActivityBuilder(mAtm).setTask(entry.getTask())
                .setComponent(new ComponentName(TARGET, TARGET + ".SharePage")).build();
        child.resultTo = mSource;
        assertFalse(mMoment.deferAppJumpCanceledResultLocked(child, RESULT_CANCELED, null));
        assertTrue(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_CANCELED, null));
    }

    @Test
    public void manualExpansionKeepsPendingSdkCallbackWithoutClosingFullscreenTask() {
        final ActivityRecord entry = createMomentEntry();
        final Task task = entry.getTask();
        entry.resultTo = mSource;
        entry.requestCode = 10103;
        assertTrue(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_CANCELED, null));

        mMoment.exitMomentTask(task.mTaskId);

        assertEquals(WINDOWING_MODE_FULLSCREEN, task.getWindowingMode());
        final ActivityInfo callback = target(SOURCE, SOURCE + ".SdkCallbackActivity");
        final MomentController.AppJumpLaunch returning = prepare(callback, entry,
                TARGET, entry.getUid(), null);
        assertNotNull(returning);
        assertTrue(returning.isReturnToSource());
        mMoment.onAppJumpLaunchSucceededLocked(returning, mSource);
        assertTrue(task.isAttached());
        assertEquals(WINDOWING_MODE_FULLSCREEN, task.getWindowingMode());
        assertNull(prepare(callback, entry, TARGET, entry.getUid(), null));
    }

    @Test
    public void emptyTaskWaitsForHandoffUntilSessionIsReleased() {
        final ActivityRecord entry = createMomentEntry();
        final Task task = entry.getTask();
        assertFalse(mMoment.shouldDeferEmptyAppJumpTaskRemovalLocked(task));
        entry.makeFinishingLocked();
        assertTrue(mMoment.shouldDeferEmptyAppJumpTaskRemovalLocked(task));
        mMoment.setEnabledFromSettings(false);
        assertFalse(mMoment.shouldDeferEmptyAppJumpTaskRemovalLocked(task));
    }

    @Test
    public void contextSuccessPageMigratesToExistingTaskAndKeepsSourceCallback() {
        final ActivityRecord entry = createMomentEntry();
        final Task originalTask = entry.getTask();
        entry.resultTo = mSource;
        assertTrue(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_CANCELED, null));
        entry.makeFinishingLocked();
        doReturn(mSource.getTask()).when(mRootWindowContainer).getTopDisplayFocusedRootTask();
        final MomentController.AppJumpLaunch handoff = prepareContextHandoff(TARGET, TARGET_UID);
        assertNotNull(handoff);
        assertFalse(handoff.isReturnToSource());
        assertEquals(WINDOWING_MODE_MOMENT, handoff.options.getLaunchWindowingMode());

        final ActivityRecord main = new ActivityBuilder(mAtm).setCreateTask(true).setUid(TARGET_UID)
                .setOnTop(false).setComponent(new ComponentName(TARGET, TARGET + ".Main")).build();
        final Task task = main.getTask();
        assertTrue(mMoment.canReuseAppJumpTaskLocked(handoff, task));
        mMoment.onAppJumpTaskResolvedLocked(handoff, task);
        task.setWindowingMode(WINDOWING_MODE_MOMENT);
        mMoment.onAppJumpLaunchSucceededLocked(handoff, main);
        mMoment.onAppJumpActivityLaunchedLocked(main);

        assertFalse(mMoment.shouldDeferEmptyAppJumpTaskRemovalLocked(originalTask));
        assertFalse(main.finishing);
        final MomentController.AppJumpLaunch callback = prepare(
                target(SOURCE, SOURCE + ".SdkCallbackActivity"), main, TARGET, TARGET_UID, null);
        assertNotNull(callback);
        assertTrue(callback.isReturnToSource());
        assertSame(mSource.getTask(), callback.sourceTask);
    }

    @Test
    public void failedSuccessPageLaunchKeepsOriginalSessionAndRestoresExistingTask() {
        final ActivityRecord entry = createMomentEntry();
        entry.makeFinishingLocked();
        doReturn(mSource.getTask()).when(mRootWindowContainer).getTopDisplayFocusedRootTask();
        final MomentController.AppJumpLaunch handoff = prepareContextHandoff(TARGET, TARGET_UID);
        assertNotNull(handoff);
        final ActivityRecord main = new ActivityBuilder(mAtm).setCreateTask(true).setUid(TARGET_UID)
                .setOnTop(false).setComponent(new ComponentName(TARGET, TARGET + ".Main")).build();
        mMoment.onAppJumpTaskResolvedLocked(handoff, main.getTask());
        main.getTask().setWindowingMode(WINDOWING_MODE_MOMENT);
        mMoment.onAppJumpLaunchFailedLocked(handoff);
        assertEquals(WINDOWING_MODE_FULLSCREEN, main.getTask().getWindowingMode());
        assertFalse(main.finishing);
        assertTrue(mMoment.shouldDeferEmptyAppJumpTaskRemovalLocked(entry.getTask()));
    }

    @Test
    public void contextHandoffRejectsOtherPackagesAndUids() {
        final ActivityRecord entry = createMomentEntry();
        entry.makeFinishingLocked();
        doReturn(mSource.getTask()).when(mRootWindowContainer).getTopDisplayFocusedRootTask();
        assertNull(prepareContextHandoff("com.test.other", TARGET_UID));
        assertNull(prepareContextHandoff(TARGET, TARGET_UID + 1));
    }

    @Test
    public void contextHandoffDoesNotGuessBetweenConcurrentFlows() {
        createMomentEntry().makeFinishingLocked();
        createMomentEntry().makeFinishingLocked();
        doReturn(mSource.getTask()).when(mRootWindowContainer).getTopDisplayFocusedRootTask();
        assertNull(prepareContextHandoff(TARGET, TARGET_UID));
    }

    @Test
    public void activePageAndManualExpansionDoNotEnableContextTaskHandoff() {
        final ActivityRecord entry = createMomentEntry();
        doReturn(mSource.getTask()).when(mRootWindowContainer).getTopDisplayFocusedRootTask();
        assertNull(prepareContextHandoff(TARGET, TARGET_UID));
        mMoment.exitMomentTask(entry.getTask().mTaskId);
        entry.makeFinishingLocked();
        assertNull(prepareContextHandoff(TARGET, TARGET_UID));
    }

    @Test
    public void sourceTaskTapOutsideKeepsFocusOnMomentAndBlocksBridgeFocusSteal() {
        final ActivityRecord assist = new ActivityBuilder(mAtm)
                .setTask(mSource.getTask())
                .setUid(SOURCE_UID)
                .setVisible(true)
                .setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(new ComponentName(SOURCE, SOURCE + ".AssistActivity"))
                .build();
        final WindowState assistWin = newWindowBuilder("assistWin", TYPE_BASE_APPLICATION)
                .setWindowToken(assist).build();
        final ActivityRecord momentEntry = createMomentEntryFrom(assist);
        final Task momentTask = momentEntry.getTask();
        final WindowState momentWin = newWindowBuilder("momentWin", TYPE_BASE_APPLICATION)
                .setWindowToken(momentEntry).build();

        mDisplayContent.setFocusedApp(momentEntry);
        mWm.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateInputWindows */);
        assertSame(momentWin, mDisplayContent.mCurrentFocus);
        assertSame(momentTask, mMoment.getAppJumpFocusGuardMomentTaskLocked(
                mSource.getTask(), assist));

        spyOn(mAtm);
        mWm.handleTaskFocusChange(mSource.getTask(), assist);
        verify(mAtm, never()).setFocusedTask(eq(mSource.getTask().mTaskId), any());
        assertSame(momentWin, mDisplayContent.mCurrentFocus);

        // Even if mFocusedApp drifts to AssistActivity, DisplayContent keeps focus on Moment.
        mDisplayContent.setFocusedApp(assist);
        assertTrue(mMoment.isAppJumpFocusGuardedAppLocked(momentTask, assist));
        mWm.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateInputWindows */);
        assertSame(momentWin, mDisplayContent.mCurrentFocus);

        // If focus temporarily pointed at AssistActivity, tapping sourceTask redirects back to Moment.
        clearInvocations(mAtm);
        mDisplayContent.mCurrentFocus = assistWin;
        mWm.handleTaskFocusChange(mSource.getTask(), assist);
        verify(mAtm, never()).setFocusedTask(eq(mSource.getTask().mTaskId), any());
        verify(mAtm).setFocusedTask(momentTask.mTaskId, momentEntry);
    }

    @Test
    public void asyncHandoffGapKeepsSourceTaskFocusGuardActive() {
        final ActivityRecord assist = new ActivityBuilder(mAtm)
                .setTask(mSource.getTask())
                .setUid(SOURCE_UID)
                .setVisible(true)
                .setComponent(new ComponentName(SOURCE, SOURCE + ".AssistActivity"))
                .build();
        final ActivityRecord momentEntry = createMomentEntryFrom(assist);
        final Task momentTask = momentEntry.getTask();

        // Simulate the 72ms gap after JumpActivity finishes and before the next page launches.
        momentEntry.makeFinishingLocked();
        momentTask.setVisibleRequested(false);
        assertNull(momentTask.getTopNonFinishingActivity());

        assertSame(momentTask, mMoment.getAppJumpFocusGuardMomentTaskLocked(
                mSource.getTask(), assist));
        assertTrue(mMoment.isAppJumpFocusGuardedAppLocked(momentTask, assist));
    }

    @Test
    public void compactMomentReleasesRootSourceGuardButKeepsNonRootBridgeGuard() {
        final ActivityRecord rootMoment = createMomentEntryFrom(mSource);
        final Task rootMomentTask = rootMoment.getTask();
        spyOn(mMoment);
        doReturn(true).when(mMoment).isMomentCompact(rootMomentTask);

        assertNull(mMoment.getAppJumpFocusGuardMomentTaskLocked(mSource.getTask(), mSource));
        assertFalse(mMoment.isAppJumpFocusGuardedAppLocked(rootMomentTask, mSource));

        mMoment.onTaskRemoved(rootMomentTask);

        final ActivityRecord assist = new ActivityBuilder(mAtm)
                .setTask(mSource.getTask())
                .setUid(SOURCE_UID)
                .setVisible(true)
                .setComponent(new ComponentName(SOURCE, SOURCE + ".AssistActivity"))
                .build();
        final ActivityRecord bridgeMoment = createMomentEntryFrom(assist);
        final Task bridgeMomentTask = bridgeMoment.getTask();
        doReturn(true).when(mMoment).isMomentCompact(bridgeMomentTask);

        assertSame(bridgeMomentTask, mMoment.getAppJumpFocusGuardMomentTaskLocked(
                mSource.getTask(), assist));
        assertTrue(mMoment.isAppJumpFocusGuardedAppLocked(bridgeMomentTask, assist));
    }

    @Test
    public void escapePathsHomeThirdAppManualExpandAndCleanFinishReleaseFocusGuard() {
        final ActivityRecord assist = new ActivityBuilder(mAtm)
                .setTask(mSource.getTask())
                .setUid(SOURCE_UID)
                .setVisible(true)
                .setComponent(new ComponentName(SOURCE, SOURCE + ".AssistActivity"))
                .build();
        final WindowState assistWin = newWindowBuilder("assistWin", TYPE_BASE_APPLICATION)
                .setWindowToken(assist).build();
        final ActivityRecord momentEntry = createMomentEntryFrom(assist);
        final Task momentTask = momentEntry.getTask();
        newWindowBuilder("momentWin", TYPE_BASE_APPLICATION).setWindowToken(momentEntry).build();

        final Task homeTask = createTask(mDisplayContent, WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_HOME);
        assertNull(mMoment.getAppJumpFocusGuardMomentTaskLocked(homeTask, null));

        final ActivityRecord thirdApp = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setVisible(true)
                .setUid(10099)
                .setComponent(new ComponentName("com.test.third", "com.test.third.Main"))
                .build();
        assertNull(mMoment.getAppJumpFocusGuardMomentTaskLocked(thirdApp.getTask(), thirdApp));

        spyOn(mAtm);
        mWm.handleTaskFocusChange(homeTask, null);
        verify(mAtm).setFocusedTask(homeTask.mTaskId, null);

        clearInvocations(mAtm);
        mWm.handleTaskFocusChange(thirdApp.getTask(), thirdApp);
        verify(mAtm).setFocusedTask(thirdApp.getTask().mTaskId, thirdApp);

        // Manual expand ends ownership and lets sourceTask regain window focus.
        mMoment.exitMomentTask(momentTask.mTaskId);
        assertNull(mMoment.getAppJumpFocusGuardMomentTaskLocked(mSource.getTask(), assist));
        assertFalse(mMoment.isAppJumpFocusGuardedAppLocked(momentTask, assist));
        mSource.getTask().moveToFront("test");
        mDisplayContent.setFocusedApp(assist);
        mWm.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateInputWindows */);
        assertSame(assistWin, mDisplayContent.mCurrentFocus);

        // Clean return to source clears focus guard immediately.
        final ActivityRecord momentEntry2 = createMomentEntryFrom(assist);
        assertSame(momentEntry2.getTask(), mMoment.getAppJumpFocusGuardMomentTaskLocked(
                mSource.getTask(), assist));
        final ActivityInfo callback = target(SOURCE, SOURCE + ".wxapi.WXEntryActivity");
        final MomentController.AppJumpLaunch returning = prepare(callback, momentEntry2,
                TARGET, momentEntry2.getUid(), null);
        assertNotNull(returning);
        mMoment.onAppJumpLaunchSucceededLocked(returning, assist);
        assertNull(mMoment.getAppJumpFocusGuardMomentTaskLocked(mSource.getTask(), assist));
        mMoment.onTaskRemoved(momentEntry2.getTask());

        // Clean Task removal clears focus guard immediately.
        final ActivityRecord momentEntry3 = createMomentEntryFrom(assist);
        assertSame(momentEntry3.getTask(), mMoment.getAppJumpFocusGuardMomentTaskLocked(
                mSource.getTask(), assist));
        mMoment.onTaskRemoved(momentEntry3.getTask());
        assertNull(mMoment.getAppJumpFocusGuardMomentTaskLocked(mSource.getTask(), assist));
    }

    @Test
    public void landscapeSourceTranslucentPortraitBridgeStaysLandscapeAcrossPhaseBPhaseAAndExitGrace() {
        final String pkg = mContext.getPackageName();
        mMoment.setAppJumpSettingsFromSettings(0, true, pkg);
        final ActivityRecord landscapeGame = createLandscapeGame(pkg);

        // Call setActivityTheme before setVisible(false) so AttributeCache resolves Theme_Translucent
        // on the real test package without keeping mStyleFillsParent=true.
        final ActivityRecord translucentBridge = new ActivityBuilder(mAtm)
                .setTask(landscapeGame.getTask())
                .setActivityTheme(android.R.style.Theme_Translucent)
                .setUid(SOURCE_UID)
                .setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .setVisible(false)
                .build();
        assertFalse(translucentBridge.mStyleFillsParent);
        assertFalse(translucentBridge.occludesParent());

        mMoment.onAppJumpCandidateActivityStartingLocked(translucentBridge);
        makeBridgeVisible(translucentBridge);
        mDisplayContent.updateOrientation();

        assertFalse(mMoment.shouldHoldBridgeRotationLocked(translucentBridge));
        assertEquals(SCREEN_ORIENTATION_BEHIND, translucentBridge.getOverrideOrientation());
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());

        // Phase A: Moment session active.
        final ActivityRecord momentEntry = createMomentEntryFrom(translucentBridge);
        assertEquals(SCREEN_ORIENTATION_BEHIND, translucentBridge.getOverrideOrientation());
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());

        // Returning to source ends Moment session and arms 400ms post-session exit grace.
        final ActivityInfo callback = target(pkg, pkg + ".wxapi.WXEntryActivity");
        final MomentController.AppJumpLaunch returning = prepare(callback, momentEntry,
                TARGET, momentEntry.getUid(), null);
        assertNotNull(returning);
        mMoment.onAppJumpLaunchSucceededLocked(returning, translucentBridge);

        assertEquals(SCREEN_ORIENTATION_BEHIND, translucentBridge.getOverrideOrientation());
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());

        translucentBridge.makeFinishingLocked();
        mDisplayContent.updateOrientation();
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());
    }

    @Test
    public void landscapeSourceOpaqueAssistPortraitAndUnspecifiedStayLandscapeThroughExitGrace() {
        final ActivityRecord landscapeGame = createLandscapeGame(SOURCE);

        for (int bridgeOrientation : new int[] {
                SCREEN_ORIENTATION_PORTRAIT,
                SCREEN_ORIENTATION_UNSPECIFIED
        }) {
            final ActivityRecord assist = createBridgeInTask(landscapeGame.getTask(),
                    SOURCE + ".AssistActivity", bridgeOrientation);
            mMoment.onAppJumpCandidateActivityStartingLocked(assist);
            makeBridgeVisible(assist);
            mDisplayContent.updateOrientation();

            assertEquals(SCREEN_ORIENTATION_BEHIND, assist.getOverrideOrientation());
            assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());

            final ActivityRecord moment = createMomentEntryFrom(assist);
            assertEquals(SCREEN_ORIENTATION_BEHIND, assist.getOverrideOrientation());
            assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());

            mMoment.onTaskRemoved(moment.getTask());
            assertEquals(SCREEN_ORIENTATION_BEHIND, assist.getOverrideOrientation());
            assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());

            assist.makeFinishingLocked();
            mDisplayContent.updateOrientation();
            assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());
        }
    }

    @Test
    public void unmatchedOpaquePortraitSubActivityReleasesTier2HoldAfter150MsAndRotates() {
        final ActivityRecord landscapeGame = createLandscapeGame(SOURCE);

        final ActivityRecord subActivity = createBridgeInTask(landscapeGame.getTask(),
                SOURCE + ".SourceSubActivity", SCREEN_ORIENTATION_PORTRAIT);
        mMoment.onAppJumpCandidateActivityStartingLocked(subActivity);
        makeBridgeVisible(subActivity);

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, subActivity.getOverrideOrientation());
        assertTrue(mMoment.shouldHoldBridgeRotationLocked(subActivity));
        assertFalse(mDisplayContent.updateOrientation());

        mDisplayContent.setFixedRotationLaunchingApp(subActivity, Surface.ROTATION_0);
        mDisplayContent.continueUpdateOrientationForDiffOrienLaunchingApp();
        assertTrue(mDisplayContent.isFixedRotationLaunchingApp(subActivity));

        SystemClock.sleep(180);
        waitUntilHandlersIdle();

        assertFalse(mMoment.shouldHoldBridgeRotationLocked(subActivity));
        assertFalse(mDisplayContent.isFixedRotationLaunchingApp(subActivity));
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, mDisplayContent.getOrientation());
    }

    @Test
    public void tier2HoldUpgradesToPhaseAAndClearsFixedRotationWhenMomentLaunches() {
        final ActivityRecord landscapeGame = createLandscapeGame(SOURCE);

        final ActivityRecord obfuscatedBridge = createBridgeInTask(landscapeGame.getTask(),
                SOURCE + ".ObfuscatedBridgeActivity", SCREEN_ORIENTATION_PORTRAIT);
        mMoment.onAppJumpCandidateActivityStartingLocked(obfuscatedBridge);
        makeBridgeVisible(obfuscatedBridge);
        mDisplayContent.setFixedRotationLaunchingApp(obfuscatedBridge, Surface.ROTATION_0);
        assertTrue(mMoment.shouldHoldBridgeRotationLocked(obfuscatedBridge));
        assertTrue(mDisplayContent.isFixedRotationLaunchingApp(obfuscatedBridge));

        createMomentEntryFrom(obfuscatedBridge);

        assertFalse(mMoment.shouldHoldBridgeRotationLocked(obfuscatedBridge));
        assertFalse(mDisplayContent.isFixedRotationLaunchingApp(obfuscatedBridge));
        assertEquals(SCREEN_ORIENTATION_BEHIND, obfuscatedBridge.getOverrideOrientation());
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());
    }

    @Test
    public void setRequestedOrientationBehindWhileProtectedUpdatesRawOverrideOrientation() {
        final ActivityRecord landscapeGame = createLandscapeGame(SOURCE);

        final ActivityRecord assistPortrait = createBridgeInTask(landscapeGame.getTask(),
                SOURCE + ".AssistActivity", SCREEN_ORIENTATION_PORTRAIT);
        mMoment.onAppJumpCandidateActivityStartingLocked(assistPortrait);
        makeBridgeVisible(assistPortrait);

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, assistPortrait.getRawOverrideOrientationLocked());
        assertEquals(SCREEN_ORIENTATION_BEHIND, assistPortrait.getOverrideOrientation());

        assistPortrait.setRequestedOrientation(SCREEN_ORIENTATION_BEHIND);

        assertEquals(SCREEN_ORIENTATION_BEHIND, assistPortrait.getRawOverrideOrientationLocked());
        assertEquals(SCREEN_ORIENTATION_BEHIND, assistPortrait.getRequestedOrientation());

        mMoment.setEnabledFromSettings(false);
        assertEquals(SCREEN_ORIENTATION_BEHIND, assistPortrait.getOverrideOrientation());
    }

    @Test
    public void chooserVisibleExtendsPhaseBBridgeProtection() {
        final ActivityRecord landscapeGame = createLandscapeGame(SOURCE);

        final ActivityRecord assistPortrait = createBridgeInTask(landscapeGame.getTask(),
                SOURCE + ".AssistActivity", SCREEN_ORIENTATION_PORTRAIT);
        mMoment.onAppJumpCandidateActivityStartingLocked(assistPortrait);
        makeBridgeVisible(assistPortrait);

        final ActivityRecord chooser = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setVisible(true)
                .setActivityTheme(android.R.style.Theme_Translucent)
                .setComponent(new ComponentName("android",
                        "com.android.internal.app.ResolverActivity"))
                .build();
        assertTrue(chooser.isResolverOrDelegateActivity());
        assertFalse(chooser.providesOrientation());

        SystemClock.sleep(430);
        waitUntilHandlersIdle();

        assertEquals(SCREEN_ORIENTATION_BEHIND, assistPortrait.getOverrideOrientation());
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, mDisplayContent.getOrientation());

        chooser.makeFinishingLocked();
        SystemClock.sleep(430);
        waitUntilHandlersIdle();

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, assistPortrait.getOverrideOrientation());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, mDisplayContent.getOrientation());
    }

    private ActivityRecord createLandscapeGame(String pkg) {
        mDisplayContent.setIgnoreOrientationRequest(false);
        resizeDisplay(mDisplayContent, 2000, 1000);
        final ActivityRecord landscapeGame = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setVisible(true)
                .setUid(SOURCE_UID)
                .setScreenOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                .setComponent(new ComponentName(pkg, pkg + ".LandscapeGame"))
                .build();
        landscapeGame.getTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        return landscapeGame;
    }

    private ActivityRecord createBridgeInTask(Task task, String className, int screenOrientation) {
        return new ActivityBuilder(mAtm)
                .setTask(task)
                .setUid(SOURCE_UID)
                .setComponent(new ComponentName(SOURCE, className))
                .setScreenOrientation(screenOrientation)
                .setVisible(false)
                .build();
    }

    private static void makeBridgeVisible(ActivityRecord bridge) {
        bridge.setVisibility(true);
        bridge.setVisibleRequested(true);
        bridge.setVisible(true);
    }

    private MomentController.AppJumpLaunch prepareContextHandoff(String callingPackage,
            int callingUid) {
        final ActivityInfo successPage = target(TARGET, TARGET + ".Main");
        successPage.applicationInfo.uid = TARGET_UID;
        successPage.launchMode = ActivityInfo.LAUNCH_SINGLE_TOP;
        final Intent intent = new Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return mMoment.prepareAppJumpMomentLaunchLocked(intent, successPage, null,
                callingPackage, callingUid, 0, null, null, null);
    }

    private ActivityRecord createMomentEntry() {
        return createMomentEntryFrom(mSource);
    }

    private ActivityRecord createMomentEntryFrom(ActivityRecord source) {
        final MomentController.AppJumpLaunch launch = prepare(
                mTarget, source, source.packageName, source.getUid(), null);
        assertNotNull(launch);
        final ActivityRecord entry = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setVisible(true)
                .setUid(TARGET_UID)
                .setComponent(new ComponentName(TARGET, TARGET + ".Share"))
                .build();
        entry.getTask().setWindowingMode(WINDOWING_MODE_MOMENT);
        entry.getTask().moveToFront("createMomentEntryFrom");
        mMoment.onAppJumpLaunchSucceededLocked(launch, entry);
        return entry;
    }

    private MomentController.AppJumpLaunch prepare(ActivityInfo target) {
        return prepare(target, mSource, SOURCE, SOURCE_UID, null);
    }

    private MomentController.AppJumpLaunch prepare(ActivityInfo target, ActivityRecord source,
            String callingPackage, int callingUid, ActivityOptions options) {
        return mMoment.prepareAppJumpMomentLaunchLocked(new Intent(), target, source,
                callingPackage, callingUid, 0, options, null, null);
    }

    private static ActivityInfo target(String packageName, String className) {
        final ActivityInfo target = new ActivityInfo();
        target.packageName = packageName;
        target.name = className;
        target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = packageName;
        return target;
    }
}
