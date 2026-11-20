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
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MOMENT;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;

import static com.android.server.wm.MomentGeometry.COMPACT_DISMISS_TARGET_SIZE_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_AREA_HEIGHT_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_MENU_TOP_INSET_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_MENU_WIDTH_DP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.policy.ScreenDecorationsUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Predicate;

final class MomentController {

    private static final String TAG = "Moment";
    private static final String DEBUG_PROPERTY = "persist.debug.wm.moment";

    private static final String SYSTEM_PACKAGE_NAME = "android";
    private static final float DEFAULT_SCALE = 0.55f;
    private static final float LANDSCAPE_DEFAULT_SCALE = 0.40f;
    private static final float MIN_SCALE = 0.25f;
    private static final float MAX_SCALE = 1.0f;
    private static final float COMPACT_EPSILON = 0.001f;
    private static final float COMPACT_DISMISS_SCALE = 0.85f;
    private static final int MOVEMENT_SAFE_MARGIN_DP = 5;
    private static final long OPEN_REVEAL_TIMEOUT_MS = 3000;
    private static final int ANIMATION_EXPAND_FULLSCREEN = 1;
    private static final int ANIMATION_RESTORE_COMPACT = 2;
    private static final int ANIMATION_ENTER_COMPACT = 3;
    private static final int ANIMATION_CONVERT_FULLSCREEN = 4;

    private final WindowManagerService mService;
    private final ArrayMap<Integer, MomentTaskSurfaceState> mStates = new ArrayMap<>();
    private final ArrayMap<Integer, MomentLayoutSnapshot> mLayoutSnapshots = new ArrayMap<>();
    private final ArrayMap<Integer, MomentHandleWindow> mHandleWindows = new ArrayMap<>();
    private final MomentTaskAnimationRunner mAnimationRunner;
    private final ArraySet<String> mAppJumpSourcePackages = new ArraySet<>();
    private final ArrayMap<Integer, AppJumpSession> mAppJumpSessions = new ArrayMap<>();
    private final ArrayMap<ActivityRecord, BridgeOrientationRecord> mBridgeOrientations =
            new ArrayMap<>();
    private final ArraySet<ActivityRecord> mEvaluatingBridgeOrientations = new ArraySet<>();

    private boolean mEnabled;
    private boolean mFixedAspectRatio;
    private boolean mAppJumpEnabled;
    private int mAppJumpUserId = UserHandle.USER_NULL;
    private float mDefaultScale = DEFAULT_SCALE;

    MomentController(WindowManagerService service) {
        mService = service;
        mAnimationRunner = new MomentTaskAnimationRunner(service);
    }

    void setEnabled(boolean enabled) {
        Settings.Secure.putIntForUser(mService.mContext.getContentResolver(),
                Settings.Secure.MOMENT_ENABLED, enabled ? 1 : 0, mService.mCurrentUserId);
        setEnabledFromSettings(enabled);
    }

    void setEnabledFromSettings(boolean enabled) {
        synchronized (mService.mGlobalLock) {
            mEnabled = enabled;
            if (!enabled) {
                clearBridgeOrientationsLocked();
                exitAllForUserLocked(mService.mCurrentUserId);
            }
        }
    }

    boolean isEnabled() {
        synchronized (mService.mGlobalLock) {
            return mEnabled;
        }
    }

    void setFixedAspectRatioFromSettings(boolean fixed) {
        synchronized (mService.mGlobalLock) {
            if (mFixedAspectRatio == fixed) {
                return;
            }
            mFixedAspectRatio = fixed;
            forAllMomentTasksLocked(task -> {
                final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
                updateAspectRatioLocked(task, state);
                scheduleMomentSurfaceUpdateLocked(task);
            });
        }
    }

    private void updateAspectRatioLocked(Task task, MomentTaskSurfaceState state) {
        if (state.hasFixedAspectRatio() == mFixedAspectRatio || state.isOpening()
                || state.isClosing() || state.isTransformAnimating()) {
            return;
        }
        captureLayoutSnapshotLocked(task, state.getContentCrop());
        state.setLayoutSnapshot(mLayoutSnapshots.get(task.mTaskId));
        state.setFixedAspectRatio(mFixedAspectRatio);
        ensureMomentTaskLayoutLocked(task, state);
        if (state.isMomentCompact()) {
            constrainMomentCompactPositionLocked(task, state);
        } else {
            constrainMomentPositionLocked(task, state);
        }
        task.forAllActivities(activity -> {
            activity.ensureActivityConfiguration(true /* ignoreVisibility */);
        });
        waitForLayoutLocked(task, true /* animationFinished */);
        applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
    }

    private void captureLayoutSnapshotLocked(Task task, Rect crop) {
        removeLayoutSnapshotLocked(task.mTaskId);
        final MomentLayoutSnapshot snapshot = new MomentLayoutSnapshot(task);
        snapshot.capture(crop);
        if (!snapshot.isValid()) {
            return;
        }
        mLayoutSnapshots.put(task.mTaskId, snapshot);
        final MomentTaskSurfaceState state = getStateLocked(task);
        if (state != null) {
            state.setLayoutSnapshot(snapshot);
        }
        mService.mH.postDelayed(() -> {
            synchronized (mService.mGlobalLock) {
                if (mLayoutSnapshots.get(task.mTaskId) == snapshot) {
                    Slog.w(TAG, "Timed out waiting for Moment layout, task=" + task.mTaskId);
                    removeLayoutSnapshotLocked(task.mTaskId);
                }
            }
        }, OPEN_REVEAL_TIMEOUT_MS);
    }

    private void waitForLayoutLocked(Task task, boolean animationFinished) {
        final MomentLayoutSnapshot snapshot = mLayoutSnapshots.get(task.mTaskId);
        if (snapshot != null) {
            snapshot.waitForLayout(animationFinished);
        }
    }

    void onWindowLayoutDrawn(WindowState window, SurfaceControl.Transaction t) {
        if (mLayoutSnapshots.isEmpty()) {
            return;
        }
        final Task windowTask = window.getTask();
        final Task task = windowTask != null ? windowTask.getRootTask() : null;
        final MomentLayoutSnapshot snapshot = task != null
                ? mLayoutSnapshots.get(task.mTaskId) : null;
        if (snapshot == null || !snapshot.acceptsDraw(window)) {
            return;
        }
        final int layoutGeneration = snapshot.getLayoutGeneration();
        // Do not reveal merely because finishDrawing ran: its buffer may still be waiting in
        // a sync group. Observe commitment of the transaction that actually contains the frame.
        t.addTransactionCommittedListener(command -> mService.mH.post(command), () -> {
            synchronized (mService.mGlobalLock) {
                if (mLayoutSnapshots.get(task.mTaskId) != snapshot) {
                    return;
                }
                snapshot.onDrawCommitted(layoutGeneration);
                if (snapshot.isReadyToReveal()) {
                    revealLayoutSnapshotLocked(task.mTaskId, snapshot);
                }
            }
        });
    }

    private void finishLayoutSnapshotAnimationLocked(Task task) {
        final MomentLayoutSnapshot snapshot = mLayoutSnapshots.get(task.mTaskId);
        if (snapshot != null) {
            snapshot.onAnimationFinished();
            if (snapshot.isReadyToReveal()) {
                revealLayoutSnapshotLocked(task.mTaskId, snapshot);
            }
        }
    }

    private void revealLayoutSnapshotLocked(int taskId, MomentLayoutSnapshot snapshot) {
        snapshot.reveal(() -> {
            if (mLayoutSnapshots.get(taskId) == snapshot && snapshot.isReadyToReveal()) {
                removeLayoutSnapshotLocked(taskId);
            }
        });
    }

    private void removeLayoutSnapshotLocked(int taskId) {
        final MomentLayoutSnapshot snapshot = mLayoutSnapshots.remove(taskId);
        if (snapshot == null) {
            return;
        }
        try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
            snapshot.destroy(t);
            t.apply();
        }
        final Task task = mService.mRoot.anyTaskForId(taskId);
        final MomentTaskSurfaceState state = getStateLocked(task);
        if (state != null) {
            state.setLayoutSnapshot(null);
            scheduleMomentSurfaceUpdateLocked(task);
        }
    }

    boolean isEnabledForUser(int userId) {
        return Settings.Secure.getIntForUser(mService.mContext.getContentResolver(),
                Settings.Secure.MOMENT_ENABLED, 0, userId) != 0;
    }

    void setAppJumpSettingsFromSettings(int userId, boolean enabled, String packages) {
        synchronized (mService.mGlobalLock) {
            if (mAppJumpUserId != userId) {
                clearAppJumpSessionsLocked();
                clearBridgeOrientationsLocked();
            } else if (!enabled) {
                clearBridgeOrientationsLocked();
            }
            mAppJumpUserId = userId;
            mAppJumpEnabled = enabled;
            mAppJumpSourcePackages.clear();
            if (packages != null) {
                for (String packageName : packages.split(":")) {
                    if (!packageName.isBlank()) {
                        mAppJumpSourcePackages.add(packageName.trim());
                    }
                }
            }
        }
    }

    /** Internal launch state; never accepted from an app's Intent or ActivityOptions. */
    static final class AppJumpLaunch {
        final Task sourceTask;
        @Nullable final ActivityRecord sourceActivity;
        final String sourcePackage;
        final String targetPackage;
        final ActivityOptions options;
        final AppJumpSession returningSession;
        final AppJumpSession continuingSession;
        final ArraySet<ActivityRecord> originalActivities = new ArraySet<>();
        Task existingTask;

        AppJumpLaunch(Task sourceTask, String sourcePackage, String targetPackage,
                ActivityOptions options, AppJumpSession returningSession) {
            this(sourceTask, returningSession != null ? returningSession.sourceActivity : null,
                    sourcePackage, targetPackage, options, returningSession, null);
        }

        AppJumpLaunch(Task sourceTask, String sourcePackage, String targetPackage,
                ActivityOptions options, AppJumpSession returningSession,
                AppJumpSession continuingSession) {
            this(sourceTask,
                    returningSession != null ? returningSession.sourceActivity
                            : (continuingSession != null
                                    ? continuingSession.sourceActivity : null),
                    sourcePackage, targetPackage, options, returningSession, continuingSession);
        }

        AppJumpLaunch(Task sourceTask, @Nullable ActivityRecord sourceActivity,
                String sourcePackage, String targetPackage, ActivityOptions options,
                AppJumpSession returningSession, AppJumpSession continuingSession) {
            this.sourceTask = sourceTask;
            this.sourceActivity = sourceActivity;
            this.sourcePackage = sourcePackage;
            this.targetPackage = targetPackage;
            this.options = options;
            this.returningSession = returningSession;
            this.continuingSession = continuingSession;
        }

        boolean isReturnToSource() {
            return returningSession != null;
        }
    }

    private static final class AppJumpSession {
        final Task task;
        final Task sourceTask;
        @Nullable final ActivityRecord sourceActivity;
        final String sourcePackage;
        final String targetPackage;
        final int targetUid;
        final boolean wasExistingTask;
        final MomentAppJumpHandoff<ActivityRecord> handoff;
        boolean dismissCheckScheduled;
        boolean dismissRequested;
        boolean autoDismiss = true;

        AppJumpSession(Task task, AppJumpLaunch launch, ActivityRecord entryActivity) {
            this.task = task;
            sourceTask = launch.sourceTask;
            sourceActivity = launch.sourceActivity != null
                    ? launch.sourceActivity
                    : (launch.continuingSession != null
                            ? launch.continuingSession.sourceActivity : null);
            sourcePackage = launch.sourcePackage;
            targetPackage = launch.targetPackage;
            targetUid = entryActivity.getUid();
            wasExistingTask = launch.existingTask != null;
            handoff = launch.continuingSession != null ? launch.continuingSession.handoff
                    : new MomentAppJumpHandoff<>(entryActivity, launch.originalActivities);
            if (launch.continuingSession != null) {
                handoff.onTaskHandoff(launch.originalActivities);
            }
        }
    }

    private static final class BridgeOrientationRecord {
        MomentAppJumpBridgePolicy.ProtectionMode mode;
        long phaseBDeadlineUptimeMs;
        long exitGraceDeadlineUptimeMs;
        boolean orientationOverridden;
        boolean rotationHeld;
        @Nullable Runnable timeoutRunnable;

        BridgeOrientationRecord(MomentAppJumpBridgePolicy.ProtectionMode mode,
                long phaseBDeadlineUptimeMs) {
            this.mode = mode;
            this.phaseBDeadlineUptimeMs = phaseBDeadlineUptimeMs;
        }
    }

    AppJumpLaunch prepareAppJumpMomentLaunchLocked(Intent intent, ActivityInfo target,
            ActivityRecord source, String callingPackage, int callingUid, int userId,
            ActivityOptions options, Task inTask, TaskFragment inTaskFragment) {
        if (target == null || target.packageName == null || userId != mService.mCurrentUserId
                || (intent != null && ActivityRecord.isHomeIntent(intent))) {
            return null;
        }
        Task callerTask = source != null ? source.getTask()
                : mService.mRoot.getTopDisplayFocusedRootTask();
        if (source == null && callerTask != null) {
            final ActivityRecord top = callerTask.getTopNonFinishingActivity();
            if (top == null || !top.packageName.equals(callingPackage)
                    || top.getUid() != callingUid) {
                callerTask = null;
            }
        }
        AppJumpSession callerSession = callerTask != null
                ? mAppJumpSessions.get(callerTask.getRootTaskId()) : null;
        if (callerSession == null && source == null) {
            callerSession = findRecentAppJumpHandoffLocked(callingPackage, callingUid,
                    target.packageName, userId);
            if (callerSession != null) {
                callerTask = callerSession.task;
            }
        }
        if (callerSession != null
                && callerSession.targetUid == callingUid
                && callerSession.targetPackage.equals(callingPackage)
                && (callerTask.getWindowingMode() == WINDOWING_MODE_MOMENT
                        || (!callerSession.autoDismiss
                        && callerTask.getWindowingMode() == WINDOWING_MODE_FULLSCREEN))
                && callerSession.sourcePackage.equals(target.packageName)
                && callerSession.sourceTask.isAttached()
                && callerSession.sourceTask.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && callerSession.sourceTask.mUserId == userId) {
            final ActivityOptions returnOptions = copyAppJumpOptions(options);
            returnOptions.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
            returnOptions.setLaunchBounds(null);
            return new AppJumpLaunch(callerSession.sourceTask, callerSession.sourceActivity,
                    callerSession.sourcePackage, target.packageName, returnOptions,
                    callerSession, null /* continuingSession */);
        }
        if (!mEnabled || !mAppJumpEnabled || mAppJumpUserId != userId
                || mAppJumpSourcePackages.isEmpty() || !Process.isApplicationUid(callingUid)
                || target.applicationInfo == null
                || (target.applicationInfo.flags
                        & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                        != 0
                || ActivityStartInterceptor.isAllowedSystemMediatorTarget(target)
                || inTask != null || inTaskFragment != null) {
            return null;
        }
        if (options != null
                && ((options.getLaunchWindowingMode() != WINDOWING_MODE_UNDEFINED
                        && options.getLaunchWindowingMode() != WINDOWING_MODE_FULLSCREEN)
                        || (options.getLaunchActivityType() != ACTIVITY_TYPE_UNDEFINED
                        && options.getLaunchActivityType() != ACTIVITY_TYPE_STANDARD)
                        || options.getLaunchTaskId() != INVALID_TASK_ID
                        || options.getLaunchRootTask() != null
                        || options.getLaunchTaskFragmentToken() != null
                        || options.isLaunchIntoPip())) {
            return null;
        }
        if (callerSession != null && callerSession.autoDismiss
                && callerSession.task.getWindowingMode() == WINDOWING_MODE_MOMENT
                && callerSession.targetUid == callingUid
                && target.applicationInfo.uid == callingUid
                && callerSession.targetPackage.equals(callingPackage)
                && callerSession.targetPackage.equals(target.packageName)
                && callerSession.sourceTask.isAttached()
                && callerSession.sourceTask.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && callerSession.sourceTask.mUserId == userId
                && (options == null || options.getLaunchDisplayId() == -1
                        || options.getLaunchDisplayId() == callerSession.task.getDisplayId())
                && callerSession.handoff.canHandoffToTask(
                        callerSession.task.getTopNonFinishingActivity(),
                        SystemClock.uptimeMillis())
                && ((intent != null && (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
                        || target.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
                        || target.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
                        || target.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK)) {
            final ActivityOptions handoffOptions = copyAppJumpOptions(options);
            handoffOptions.setLaunchWindowingMode(WINDOWING_MODE_MOMENT);
            handoffOptions.setLaunchDisplayId(callerSession.task.getDisplayId());
            handoffOptions.setLaunchBounds(callerSession.task.getBounds());
            return new AppJumpLaunch(callerSession.sourceTask, callerSession.sourceActivity,
                    callerSession.sourcePackage, target.packageName, handoffOptions,
                    null /* returningSession */, callerSession);
        }
        final boolean fromResolver = "com.android.intentresolver".equals(callingPackage)
                || (source != null && (source.isResolverOrDelegateActivity()
                        || "com.android.intentresolver".equals(source.packageName)));
        final DisplayContent display = source != null ? source.getDisplayContent()
                : mService.getDefaultDisplayContentLocked();
        if (display == null) {
            return null;
        }
        if (fromResolver || source == null || !source.packageName.equals(callingPackage)) {
            // Chooser may be in its own task or on top of the original app's task.
            source = display.getActivity(activity -> isAppJumpSourceActivity(activity, userId)
                    && !ActivityStartInterceptor.isAllowedSystemMediatorTarget(activity.info)
                    && !activity.isResolverOrDelegateActivity()
                    && (fromResolver || (activity.packageName.equals(callingPackage)
                            && activity.getUid() == callingUid)));
        }
        if (!isAppJumpSourceActivity(source, userId)
                || !mAppJumpSourcePackages.contains(source.packageName)
                || source.packageName.equals(target.packageName)
                || (!fromResolver && source.getUid() != callingUid)
                || (options != null && options.getLaunchDisplayId() != -1
                        && options.getLaunchDisplayId() != display.getDisplayId())) {
            return null;
        }
        final ActivityOptions launchOptions = copyAppJumpOptions(options);
        launchOptions.setLaunchWindowingMode(WINDOWING_MODE_MOMENT);
        launchOptions.setLaunchDisplayId(display.getDisplayId());
        final Rect bounds = display.getBounds();
        if (bounds.width() > bounds.height()) {
            launchOptions.setLaunchBounds(new Rect(0, 0, bounds.height(), bounds.width()));
        }
        return new AppJumpLaunch(source.getTask(), source, source.packageName, target.packageName,
                launchOptions, null /* returningSession */, null /* continuingSession */);
    }

    private AppJumpSession findRecentAppJumpHandoffLocked(String callingPackage, int callingUid,
            String targetPackage, int userId) {
        AppJumpSession match = null;
        final long now = SystemClock.uptimeMillis();
        for (int i = 0; i < mAppJumpSessions.size(); i++) {
            final AppJumpSession session = mAppJumpSessions.valueAt(i);
            if (!session.autoDismiss || session.targetUid != callingUid
                    || !session.targetPackage.equals(callingPackage)
                    || (!session.targetPackage.equals(targetPackage)
                            && !session.sourcePackage.equals(targetPackage))
                    || session.task.mUserId != userId || !session.task.isAttached()
                    || session.task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || !session.handoff.canHandoffToTask(
                            session.task.getTopNonFinishingActivity(), now)) {
                continue;
            }
            // Context launches have no Activity token. Never guess between concurrent flows.
            if (match != null) {
                return null;
            }
            match = session;
        }
        return match;
    }

    private static ActivityOptions copyAppJumpOptions(ActivityOptions options) {
        return options != null ? ActivityOptions.fromBundle(options.toBundle())
                : ActivityOptions.makeBasic();
    }

    private static boolean isAppJumpSourceActivity(ActivityRecord activity, int userId) {
        return activity != null && !activity.finishing && activity.mUserId == userId
                && activity.isVisibleRequested() && activity.getTask() != null
                && activity.getTask().isActivityTypeStandard()
                && activity.getTask().getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }

    boolean canReuseAppJumpTaskLocked(AppJumpLaunch launch, Task task) {
        if (task == null || task == launch.sourceTask || task.getRootTask() != task
                || !task.isLeafTask() || !task.isActivityTypeStandard()
                || task.mUserId != launch.sourceTask.mUserId
                || task.getDisplayId() != launch.sourceTask.getDisplayId()) {
            return false;
        }
        final AppJumpSession session = mAppJumpSessions.get(task.mTaskId);
        if (task.getWindowingMode() == WINDOWING_MODE_MOMENT) {
            return session != null && session.autoDismiss && !session.dismissRequested
                    && session.sourceTask == launch.sourceTask;
        }
        final ActivityRecord root = task.getRootActivity();
        return session == null && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && !task.isVisibleRequested() && root != null
                && launch.targetPackage.equals(root.packageName);
    }

    void onAppJumpTaskResolvedLocked(AppJumpLaunch launch, Task task) {
        if (launch.continuingSession != null && task == launch.continuingSession.task) {
            return;
        }
        if (launch.returningSession == null && task != null) {
            launch.existingTask = task;
            task.forAllActivities(activity -> {
                launch.originalActivities.add(activity);
            });
        }
    }

    void onAppJumpLaunchSucceededLocked(AppJumpLaunch launch, ActivityRecord entry) {
        if (launch.returningSession != null) {
            armPostSessionExitGraceForSessionLocked(launch.returningSession, entry);
            launch.returningSession.handoff.onReturnToSource();
            if (launch.returningSession.autoDismiss) {
                dismissAppJumpTaskLocked(launch.returningSession);
            } else {
                releaseAppJumpSessionLocked(mAppJumpSessions.remove(
                        launch.returningSession.task.mTaskId));
            }
            return;
        }
        final Task task = entry != null ? entry.getTask() : null;
        if (task == null || task == launch.sourceTask
                || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                || mAppJumpSessions.containsKey(task.mTaskId)) {
            return;
        }
        if (launch.continuingSession != null) {
            final AppJumpSession previous = launch.continuingSession;
            if (mAppJumpSessions.get(previous.task.mTaskId) != previous) {
                return;
            }
            cancelAppJumpDismissLocked(previous);
            mAppJumpSessions.remove(previous.task.mTaskId);
            final AppJumpSession nextSession = new AppJumpSession(task, launch, entry);
            mAppJumpSessions.put(task.mTaskId, nextSession);
            activatePhaseABridgeProtectionLocked(nextSession);
            // Completion and its pending SDK result now belong to the new task. Retire only
            // the previous container, without reporting a cancel during the handoff.
            if (previous.wasExistingTask && previous.task.hasChild()) {
                exitMomentTaskLocked(previous.task, false /* captureSnapshot */);
                previous.task.moveTaskToBack(previous.task);
            } else {
                closeMomentTaskLocked(previous.task);
            }
            return;
        }
        final AppJumpSession session = new AppJumpSession(task, launch, entry);
        mAppJumpSessions.put(task.mTaskId, session);
        activatePhaseABridgeProtectionLocked(session);
    }

    void onAppJumpLaunchFailedLocked(AppJumpLaunch launch) {
        final Task task = launch.existingTask;
        if (task != null && task.isAttached()
                && task.getWindowingMode() == WINDOWING_MODE_MOMENT
                && !mAppJumpSessions.containsKey(task.mTaskId)) {
            exitMomentTaskLocked(task, false /* captureSnapshot */);
            task.moveTaskToBack(task);
        }
    }

    void onAppJumpActivityFinishingLocked(ActivityRecord activity) {
        clearBridgeOrientationLocked(activity);
        final Task task = activity.getTask();
        final AppJumpSession session = task != null
                ? mAppJumpSessions.get(task.getRootTaskId()) : null;
        if (session == null) {
            return;
        }
        session.handoff.onActivityFinishing(activity, SystemClock.uptimeMillis());
        scheduleAppJumpDismissCheckLocked(session);
    }

    void onAppJumpActivityRemovedLocked(@NonNull ActivityRecord activity) {
        clearBridgeOrientationLocked(activity);
    }

    void onAppJumpActivityLaunchedLocked(ActivityRecord activity) {
        final Task task = activity != null ? activity.getTask() : null;
        final AppJumpSession session = task != null
                ? mAppJumpSessions.get(task.getRootTaskId()) : null;
        if (session == null || session.handoff.hasReturnedToSource()) {
            return;
        }
        session.handoff.onActivityLaunched(activity, SystemClock.uptimeMillis());
        if (session.dismissRequested) {
            cancelAppJumpDismissLocked(session);
        }
        scheduleAppJumpDismissCheckLocked(session);
    }

    boolean deferAppJumpCanceledResultLocked(ActivityRecord activity, int resultCode,
            Intent resultData) {
        if (resultCode != RESULT_CANCELED || resultData != null || activity.resultTo == null) {
            return false;
        }
        final Task task = activity.getTask();
        final AppJumpSession session = task != null
                ? mAppJumpSessions.get(task.getRootTaskId()) : null;
        if (session == null) {
            return false;
        }
        final ActivityRecord receiver = activity.resultTo;
        final String resultWho = activity.resultWho;
        final int requestCode = activity.requestCode;
        final int callingUid = activity.getUid();
        return session.handoff.deferCanceledResult(activity, () -> {
            if (receiver.isInRootTaskLocked() && !receiver.finishing) {
                receiver.sendResult(callingUid, resultWho, requestCode, RESULT_CANCELED,
                        null /* data */, new Binder(), null /* dataGrants */);
            }
        });
    }

    boolean shouldDeferEmptyAppJumpTaskRemovalLocked(Task task) {
        final AppJumpSession session = mAppJumpSessions.get(task.mTaskId);
        return session != null && session.handoff.isEntryFinished() && !task.mInRemoveTask
                && task.getTopNonFinishingActivity() == null && !task.isClearingToReuseTask();
    }

    private void scheduleAppJumpDismissCheckLocked(AppJumpSession session) {
        if (!session.handoff.isEntryFinished() || session.dismissRequested
                || session.dismissCheckScheduled) {
            return;
        }
        session.dismissCheckScheduled = true;
        mService.mH.postDelayed(() -> {
            synchronized (mService.mGlobalLock) {
                session.dismissCheckScheduled = false;
                if (mAppJumpSessions.get(session.task.mTaskId) != session
                        || session.task.isClearingToReuseTask()) {
                    return;
                }
                final ActivityRecord top = session.task.getTopNonFinishingActivity();
                final long now = SystemClock.uptimeMillis();
                if (session.handoff.remainingDelay(now) > 0) {
                    scheduleAppJumpDismissCheckLocked(session);
                } else if (session.handoff.shouldDismiss(top, now)) {
                    if (session.autoDismiss) {
                        dismissAppJumpTaskLocked(session);
                    } else {
                        releaseAppJumpSessionLocked(mAppJumpSessions.remove(session.task.mTaskId));
                    }
                }
            }
        }, session.handoff.remainingDelay(SystemClock.uptimeMillis()));
    }

    private void cancelAppJumpDismissLocked(AppJumpSession session) {
        session.dismissRequested = false;
        final MomentTaskSurfaceState state = getStateLocked(session.task);
        if (state != null && state.isClosing()) {
            cancelTaskAnimationLocked(session.task, state);
            state.cancelCloseAnimation(session.task.getSyncTransaction(), true /* restoreTask */);
            updateHandleWindowLocked(session.task);
            scheduleMomentSurfaceUpdateLocked(session.task);
        }
    }

    private void releaseAppJumpSessionLocked(AppJumpSession session) {
        if (session == null) {
            return;
        }
        armPostSessionExitGraceForSessionLocked(session, null /* extraBridge */);
        session.handoff.finish();
        // Releasing ownership must also release any empty task kept for an async handoff.
        mService.mH.post(() -> {
            synchronized (mService.mGlobalLock) {
                if (!mAppJumpSessions.containsKey(session.task.mTaskId)
                        && session.task.isAttached() && !session.task.hasChild()) {
                    session.task.removeIfPossible("app-jump-session-ended");
                }
            }
        });
    }

    private void clearAppJumpSessionsLocked() {
        final ArrayList<AppJumpSession> sessions = new ArrayList<>(mAppJumpSessions.values());
        mAppJumpSessions.clear();
        for (AppJumpSession session : sessions) {
            releaseAppJumpSessionLocked(session);
        }
    }

    private void stopAppJumpWindowOwnershipLocked(Task task) {
        final AppJumpSession session = mAppJumpSessions.get(task.mTaskId);
        if (session != null && session.handoff.hasPendingCanceledResult()) {
            // A manual expansion ends automatic window ownership, but not an outstanding
            // SDK handoff. Keep its provisional cancel until completion or a real callback.
            session.autoDismiss = false;
            session.dismissRequested = false;
        } else {
            releaseAppJumpSessionLocked(mAppJumpSessions.remove(task.mTaskId));
        }
    }

    @Nullable
    Task getAppJumpFocusGuardMomentTaskLocked(@Nullable Task targetTask,
            @Nullable ActivityRecord touchedActivity) {
        final Task resolvedTask = targetTask != null
                ? targetTask
                : (touchedActivity != null ? touchedActivity.getTask() : null);
        final Task targetRootTask = resolvedTask != null ? resolvedTask.getRootTask() : null;
        if (targetRootTask == null) {
            return null;
        }
        for (int i = 0; i < mAppJumpSessions.size(); i++) {
            final AppJumpSession session = mAppJumpSessions.valueAt(i);
            final Task momentRootTask = session.task.getRootTask();
            if (momentRootTask != null
                    && shouldGuardSessionForTaskLocked(session, targetRootTask, momentRootTask)) {
                return momentRootTask;
            }
        }
        return null;
    }

    boolean isAppJumpFocusGuardedAppLocked(@Nullable Task momentRootTask,
            @Nullable ActivityRecord focusedApp) {
        if (momentRootTask == null || focusedApp == null) {
            return false;
        }
        final Task focusedTask = focusedApp.getTask();
        final Task focusedRootTask = focusedTask != null ? focusedTask.getRootTask() : null;
        if (focusedRootTask == null) {
            return false;
        }
        AppJumpSession session = mAppJumpSessions.get(momentRootTask.mTaskId);
        if (session == null) {
            for (int i = 0; i < mAppJumpSessions.size(); i++) {
                final AppJumpSession candidate = mAppJumpSessions.valueAt(i);
                if (candidate.task.getRootTask() == momentRootTask) {
                    session = candidate;
                    break;
                }
            }
        }
        return session != null
                && shouldGuardSessionForTaskLocked(session, focusedRootTask, momentRootTask);
    }

    private boolean shouldGuardSessionForTaskLocked(@NonNull AppJumpSession session,
            @NonNull Task targetRootTask, @NonNull Task momentRootTask) {
        if (session.sourceTask == null || !session.sourceTask.isAttached()) {
            return false;
        }
        final Task sourceRootTask = session.sourceTask.getRootTask();
        if (sourceRootTask == null) {
            return false;
        }
        final boolean momentAttached = session.task.isAttached();
        final boolean momentWindowingMode =
                session.task.getWindowingMode() == WINDOWING_MODE_MOMENT
                        && momentRootTask.getWindowingMode() == WINDOWING_MODE_MOMENT;
        // During an async handoff (entry activity finished, waiting up to QUIET_PERIOD_MS for the
        // next flow activity), the Moment task stays attached without active children and its
        // mVisibleRequested drops transiently; keep guarding while source remains visible.
        final boolean momentVisibleOrHandoff = session.task.isVisibleRequested()
                || (session.handoff.isEntryFinished() && !session.task.isClearingToReuseTask()
                        && session.sourceTask.isVisibleRequested());
        final boolean sameRootTask = targetRootTask == sourceRootTask;
        final boolean momentCompact = isMomentCompact(session.task);
        final ActivityRecord topSource = session.sourceTask.getTopNonFinishingActivity();
        final ActivityRecord rootSource = session.sourceTask.getRootActivity();
        final boolean isNonRootSourceBridgeTop = session.sourceActivity != null
                && !session.sourceActivity.finishing
                && topSource == session.sourceActivity
                && session.sourceActivity != rootSource;
        return MomentAppJumpBridgePolicy.shouldGuardSourceTaskFocus(
                session.autoDismiss,
                session.dismissRequested,
                momentAttached,
                momentWindowingMode,
                momentVisibleOrHandoff,
                sameRootTask,
                momentCompact,
                isNonRootSourceBridgeTop);
    }

    void onAppJumpCandidateActivityStartingLocked(@Nullable ActivityRecord starting) {
        if (!isBridgePackageEnabledLocked(starting)) {
            return;
        }
        final Task task = starting.getTask();
        if (!isEligibleBridgeTask(task) || hasActivePhaseASessionForTaskLocked(task)) {
            return;
        }
        final int rawOrientation = starting.getRawOverrideOrientationLocked();
        if (MomentAppJumpBridgePolicy.isFixedOrientationLandscape(rawOrientation)
                || !hasLandscapeDefiningActivityBelowLocked(starting, task, false /* phaseA */)) {
            return;
        }
        final String className = starting.mActivityComponent != null
                ? starting.mActivityComponent.getClassName() : null;
        final MomentAppJumpBridgePolicy.ProtectionMode mode =
                MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        starting.mStyleFillsParent,
                        starting.occludesParent(),
                        starting.isNoDisplay(),
                        className,
                        rawOrientation,
                        starting.providesOrientation(),
                        true /* hasLandscapeDefiningBelow */);
        final boolean highConfidence = MomentAppJumpBridgePolicy.isHighConfidenceBridge(
                starting.mStyleFillsParent, starting.occludesParent(), starting.isNoDisplay(),
                className);
        final long timeoutMs = (mode == MomentAppJumpBridgePolicy.ProtectionMode.TIER1_BEHIND
                || (mode == MomentAppJumpBridgePolicy.ProtectionMode.NONE && highConfidence))
                ? MomentAppJumpBridgePolicy.PHASE_B_TIER1_TIMEOUT_MS
                : MomentAppJumpBridgePolicy.PHASE_B_TIER2_HOLD_MS;
        final long now = SystemClock.uptimeMillis();
        BridgeOrientationRecord record = mBridgeOrientations.get(starting);
        if (record == null) {
            record = new BridgeOrientationRecord(mode, now + timeoutMs);
            mBridgeOrientations.put(starting, record);
        } else {
            record.mode = mode;
            record.phaseBDeadlineUptimeMs = now + timeoutMs;
            record.exitGraceDeadlineUptimeMs = 0;
            record.orientationOverridden = false;
            record.rotationHeld = false;
        }
        scheduleBridgeTimeoutLocked(starting, record, timeoutMs);
    }

    void onAppJumpBridgeActivityLaunchFailedLocked(@Nullable ActivityRecord starting) {
        clearBridgeOrientationLocked(starting);
    }

    int overrideAppJumpBridgeOrientationLocked(@NonNull ActivityRecord activity,
            int candidateOrientation) {
        if (activity == null || mEvaluatingBridgeOrientations.contains(activity)
                || !isBridgePackageEnabledLocked(activity)) {
            return candidateOrientation;
        }
        final Task task = activity.getTask();
        if (!isEligibleBridgeTask(task)
                || !MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                        candidateOrientation, activity.providesOrientation())) {
            return candidateOrientation;
        }
        if (hasActivePhaseASessionForTaskLocked(task)) {
            if (hasLandscapeDefiningActivityBelowLocked(activity, task, true /* phaseA */)) {
                final BridgeOrientationRecord record = mBridgeOrientations.get(activity);
                if (record != null) {
                    record.orientationOverridden = true;
                }
                return SCREEN_ORIENTATION_BEHIND;
            }
            return candidateOrientation;
        }
        final BridgeOrientationRecord record = mBridgeOrientations.get(activity);
        if (record == null) {
            return candidateOrientation;
        }
        final long now = SystemClock.uptimeMillis();
        final boolean exitGraceActive = record.exitGraceDeadlineUptimeMs > now;
        final boolean phaseBActive = record.phaseBDeadlineUptimeMs > now;
        if (!exitGraceActive && !phaseBActive) {
            return candidateOrientation;
        }
        if (!hasLandscapeDefiningActivityBelowLocked(
                activity, task, exitGraceActive /* phaseA */)) {
            return candidateOrientation;
        }
        if (exitGraceActive) {
            record.orientationOverridden = true;
            return SCREEN_ORIENTATION_BEHIND;
        }
        if (record.mode == MomentAppJumpBridgePolicy.ProtectionMode.NONE) {
            final String className = activity.mActivityComponent != null
                    ? activity.mActivityComponent.getClassName() : null;
            final MomentAppJumpBridgePolicy.ProtectionMode upgraded =
                    MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                            activity.mStyleFillsParent,
                            activity.occludesParent(),
                            activity.isNoDisplay(),
                            className,
                            candidateOrientation,
                            activity.providesOrientation(),
                            true /* hasLandscapeDefiningBelow */);
            if (upgraded != MomentAppJumpBridgePolicy.ProtectionMode.NONE) {
                record.mode = upgraded;
            }
        }
        if (record.mode == MomentAppJumpBridgePolicy.ProtectionMode.TIER1_BEHIND) {
            record.orientationOverridden = true;
            return SCREEN_ORIENTATION_BEHIND;
        }
        return candidateOrientation;
    }

    boolean shouldHoldBridgeRotationLocked(@Nullable ActivityRecord activity) {
        if (!isBridgePackageEnabledLocked(activity)) {
            return false;
        }
        final Task task = activity.getTask();
        if (!isEligibleBridgeTask(task) || hasActivePhaseASessionForTaskLocked(task)) {
            return false;
        }
        final BridgeOrientationRecord record = mBridgeOrientations.get(activity);
        if (record == null) {
            return false;
        }
        final long now = SystemClock.uptimeMillis();
        if (record.phaseBDeadlineUptimeMs <= now || record.exitGraceDeadlineUptimeMs > now) {
            return false;
        }
        if (record.mode == MomentAppJumpBridgePolicy.ProtectionMode.NONE) {
            final int rawOrientation = activity.getRawOverrideOrientationLocked();
            if (MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                    rawOrientation, activity.providesOrientation())
                    && hasLandscapeDefiningActivityBelowLocked(activity, task, false /* phaseA */)) {
                final String className = activity.mActivityComponent != null
                        ? activity.mActivityComponent.getClassName() : null;
                record.mode = MomentAppJumpBridgePolicy.classifyPhaseBBridge(
                        activity.mStyleFillsParent,
                        activity.occludesParent(),
                        activity.isNoDisplay(),
                        className,
                        rawOrientation,
                        activity.providesOrientation(),
                        true /* hasLandscapeDefiningBelow */);
            }
        }
        if (record.mode != MomentAppJumpBridgePolicy.ProtectionMode.TIER2_DISPLAY_HOLD
                || !hasLandscapeDefiningActivityBelowLocked(
                        activity, task, false /* phaseA */)) {
            return false;
        }
        record.rotationHeld = true;
        return true;
    }

    private boolean isBridgePackageEnabledLocked(@Nullable ActivityRecord activity) {
        return activity != null
                && !activity.finishing
                && !activity.isResolverOrDelegateActivity()
                && mEnabled
                && mAppJumpEnabled
                && activity.mUserId == mAppJumpUserId
                && activity.packageName != null
                && mAppJumpSourcePackages.contains(activity.packageName);
    }

    private static boolean isEligibleBridgeTask(@Nullable Task task) {
        return task != null
                && task.isActivityTypeStandard()
                && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }

    private boolean hasActivePhaseASessionForTaskLocked(@Nullable Task task) {
        return hasOtherActivePhaseASessionForTaskLocked(task, null /* excluded */);
    }

    private boolean hasOtherActivePhaseASessionForTaskLocked(@Nullable Task task,
            @Nullable AppJumpSession excluded) {
        if (task == null) {
            return false;
        }
        for (int i = 0; i < mAppJumpSessions.size(); i++) {
            final AppJumpSession session = mAppJumpSessions.valueAt(i);
            if (session != excluded
                    && session.sourceTask == task
                    && session.task.isAttached()
                    && session.task.getWindowingMode() == WINDOWING_MODE_MOMENT) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLandscapeDefiningActivityBelowLocked(@NonNull ActivityRecord activity,
            @NonNull Task task, boolean phaseA) {
        final ActivityRecord below = findDefiningActivityBelowLocked(activity, task, phaseA);
        if (below == null) {
            return false;
        }
        final int rawBelow = below.getRawOverrideOrientationLocked();
        final int requestedConfigOrientation = below.getRequestedConfigurationOrientation(
                false /* forDisplay */, rawBelow);
        final int currentConfigOrientation = below.getConfiguration().orientation;
        return MomentAppJumpBridgePolicy.isLandscapeDefiningActivity(
                rawBelow, requestedConfigOrientation, currentConfigOrientation, phaseA);
    }

    @Nullable
    private ActivityRecord findDefiningActivityBelowLocked(@NonNull ActivityRecord activity,
            @NonNull Task task, boolean phaseA) {
        final long now = SystemClock.uptimeMillis();
        final Predicate<ActivityRecord> predicate = below -> {
            if (below == activity || below.finishing
                    || mEvaluatingBridgeOrientations.contains(below)) {
                return false;
            }
            final BridgeOrientationRecord belowRecord = mBridgeOrientations.get(below);
            if (belowRecord != null
                    && belowRecord.mode != MomentAppJumpBridgePolicy.ProtectionMode.NONE
                    && (phaseA || belowRecord.phaseBDeadlineUptimeMs > now
                            || belowRecord.exitGraceDeadlineUptimeMs > now)) {
                return false;
            }
            mEvaluatingBridgeOrientations.add(below);
            try {
                final int rawBelow = below.getRawOverrideOrientationLocked();
                return MomentAppJumpBridgePolicy.isDefiningOrientationCandidate(
                        rawBelow, below.providesOrientation());
            } finally {
                mEvaluatingBridgeOrientations.remove(below);
            }
        };
        if (activity.isDescendantOf(task)) {
            return task.getActivity(predicate, activity /* boundary */,
                    false /* includeBoundary */, true /* traverseTopToBottom */);
        }
        return task.getActivity(predicate, true /* traverseTopToBottom */);
    }

    private void activatePhaseABridgeProtectionLocked(@NonNull AppJumpSession session) {
        final Task sourceTask = session.sourceTask;
        if (sourceTask == null || !sourceTask.isAttached()) {
            return;
        }
        boolean clearedPhaseBHold = false;
        for (int i = mBridgeOrientations.size() - 1; i >= 0; i--) {
            final ActivityRecord bridge = mBridgeOrientations.keyAt(i);
            if (bridge.getTask() != sourceTask) {
                continue;
            }
            final BridgeOrientationRecord record = mBridgeOrientations.valueAt(i);
            if (record.timeoutRunnable != null) {
                mService.mH.removeCallbacks(record.timeoutRunnable);
                record.timeoutRunnable = null;
            }
            record.phaseBDeadlineUptimeMs = 0;
            if (record.mode == MomentAppJumpBridgePolicy.ProtectionMode.TIER2_DISPLAY_HOLD
                    || record.rotationHeld) {
                clearedPhaseBHold = true;
            }
            if (bridge.finishing || !bridge.isAttached()) {
                mBridgeOrientations.removeAt(i);
                continue;
            }
            final int raw = bridge.getRawOverrideOrientationLocked();
            if (MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                    raw, bridge.providesOrientation())) {
                record.mode = MomentAppJumpBridgePolicy.ProtectionMode.TIER1_BEHIND;
                record.orientationOverridden = true;
            } else {
                mBridgeOrientations.removeAt(i);
            }
        }
        final DisplayContent dc = sourceTask.getDisplayContent();
        if (dc == null) {
            return;
        }
        final ActivityRecord fixedApp = dc.getFixedRotationLaunchingApp();
        if (fixedApp != null && fixedApp.getTask() == sourceTask) {
            final int rawFixed = fixedApp.getRawOverrideOrientationLocked();
            if (overrideAppJumpBridgeOrientationLocked(fixedApp, rawFixed) != rawFixed) {
                dc.clearFixedRotationLaunchingApp();
                fixedApp.clearAppCompatSizeCompatForBridgeLocked();
                fixedApp.ensureActivityConfiguration();
                dc.updateOrientation();
                return;
            }
        }
        if (clearedPhaseBHold) {
            dc.updateOrientation();
        }
    }

    private void armPostSessionExitGraceForSessionLocked(@Nullable AppJumpSession session,
            @Nullable ActivityRecord extraBridge) {
        if (session == null || !mEnabled || !mAppJumpEnabled) {
            return;
        }
        final Task sourceTask = session.sourceTask;
        if (sourceTask == null || !sourceTask.isAttached()
                || sourceTask.mUserId != mAppJumpUserId
                || !isEligibleBridgeTask(sourceTask)
                || hasOtherActivePhaseASessionForTaskLocked(sourceTask, session)) {
            return;
        }
        if (extraBridge != null && extraBridge.getTask() == sourceTask) {
            armPostSessionExitGraceOnActivityLocked(extraBridge, sourceTask);
        }
        final ActivityRecord top = sourceTask.getTopNonFinishingActivity();
        if (top != null && top != extraBridge) {
            armPostSessionExitGraceOnActivityLocked(top, sourceTask);
        }
        if (session.sourceActivity != null && session.sourceActivity != extraBridge
                && session.sourceActivity != top
                && session.sourceActivity.getTask() == sourceTask) {
            armPostSessionExitGraceOnActivityLocked(session.sourceActivity, sourceTask);
        }
        for (int i = mBridgeOrientations.size() - 1; i >= 0; i--) {
            final ActivityRecord candidate = mBridgeOrientations.keyAt(i);
            final BridgeOrientationRecord record = mBridgeOrientations.valueAt(i);
            if (candidate != extraBridge && candidate != top
                    && candidate != session.sourceActivity
                    && candidate.getTask() == sourceTask
                    && record.mode != MomentAppJumpBridgePolicy.ProtectionMode.NONE) {
                armPostSessionExitGraceOnActivityLocked(candidate, sourceTask);
            }
        }
    }

    private void armPostSessionExitGraceOnActivityLocked(@NonNull ActivityRecord activity,
            @NonNull Task sourceTask) {
        if (!isBridgePackageEnabledLocked(activity) || activity.getTask() != sourceTask) {
            return;
        }
        final int rawOrientation = activity.getRawOverrideOrientationLocked();
        if (MomentAppJumpBridgePolicy.isFixedOrientationLandscape(rawOrientation)
                || !hasLandscapeDefiningActivityBelowLocked(
                        activity, sourceTask, true /* phaseA */)) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final boolean shouldOverrideNow =
                MomentAppJumpBridgePolicy.shouldProtectCandidateOrientation(
                        rawOrientation, activity.providesOrientation());
        BridgeOrientationRecord record = mBridgeOrientations.get(activity);
        if (record == null) {
            record = new BridgeOrientationRecord(
                    MomentAppJumpBridgePolicy.ProtectionMode.TIER1_BEHIND, 0 /* phaseBDeadline */);
            mBridgeOrientations.put(activity, record);
        } else if (shouldOverrideNow) {
            record.mode = MomentAppJumpBridgePolicy.ProtectionMode.TIER1_BEHIND;
        }
        record.phaseBDeadlineUptimeMs = 0;
        record.exitGraceDeadlineUptimeMs = now + MomentAppJumpBridgePolicy.POST_SESSION_EXIT_GRACE_MS;
        if (shouldOverrideNow) {
            record.orientationOverridden = true;
        }
        scheduleBridgeTimeoutLocked(activity, record,
                MomentAppJumpBridgePolicy.POST_SESSION_EXIT_GRACE_MS);
    }

    private void scheduleBridgeTimeoutLocked(@NonNull ActivityRecord activity,
            @NonNull BridgeOrientationRecord record, long delayMs) {
        if (record.timeoutRunnable != null) {
            mService.mH.removeCallbacks(record.timeoutRunnable);
            record.timeoutRunnable = null;
        }
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                synchronized (mService.mGlobalLock) {
                    onBridgeOrientationTimeoutLocked(activity, record, this);
                }
            }
        };
        record.timeoutRunnable = timeout;
        mService.mH.postDelayed(timeout, Math.max(1L, delayMs));
    }

    private void onBridgeOrientationTimeoutLocked(@NonNull ActivityRecord activity,
            @NonNull BridgeOrientationRecord record, @NonNull Runnable self) {
        if (record.timeoutRunnable != self || mBridgeOrientations.get(activity) != record) {
            return;
        }
        record.timeoutRunnable = null;
        if (activity.finishing || !activity.isAttached()) {
            mBridgeOrientations.remove(activity);
            return;
        }
        final Task task = activity.getTask();
        if (hasActivePhaseASessionForTaskLocked(task)) {
            record.phaseBDeadlineUptimeMs = 0;
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final boolean exitGrace = record.exitGraceDeadlineUptimeMs > 0;
        final DisplayContent dc = activity.getDisplayContent();
        if (!exitGrace && dc != null && isChooserVisibleOnDisplayLocked(dc)) {
            record.phaseBDeadlineUptimeMs = now + MomentAppJumpBridgePolicy.PHASE_B_TIER1_TIMEOUT_MS;
            scheduleBridgeTimeoutLocked(activity, record,
                    MomentAppJumpBridgePolicy.PHASE_B_TIER1_TIMEOUT_MS);
            return;
        }
        final long nextDeadline = Math.max(record.phaseBDeadlineUptimeMs,
                record.exitGraceDeadlineUptimeMs);
        if (nextDeadline > now) {
            scheduleBridgeTimeoutLocked(activity, record, nextDeadline - now);
            return;
        }
        mBridgeOrientations.remove(activity);
        if (!record.orientationOverridden && !record.rotationHeld) {
            return;
        }
        restoreOrientationForExpiredBridgeLocked(activity);
    }

    private void restoreOrientationForExpiredBridgeLocked(@Nullable ActivityRecord activity) {
        if (activity == null || activity.finishing || !activity.isAttached()) {
            return;
        }
        activity.clearAppCompatSizeCompatForBridgeLocked();
        final DisplayContent dc = activity.getDisplayContent();
        if (dc == null) {
            return;
        }
        if (dc.isFixedRotationLaunchingApp(activity)) {
            if (!activity.hasAnimatingFixedRotationTransition() && !activity.inTransition()) {
                dc.continueUpdateOrientationForDiffOrienLaunchingApp();
            }
            return;
        }
        if (!dc.onDescendantOrientationChanged(activity) && activity.getParent() != null
                && activity.getConfiguration().orientation
                        != activity.getRequestedConfigurationOrientation(false /* forDisplay */)) {
            activity.onConfigurationChanged(activity.getParent().getConfiguration());
        }
        activity.ensureActivityConfiguration();
    }

    private static boolean isChooserVisibleOnDisplayLocked(@NonNull DisplayContent dc) {
        return dc.getActivity(activity -> activity.isVisibleRequested()
                && activity.isResolverOrDelegateActivity()) != null;
    }

    private void clearBridgeOrientationLocked(@Nullable ActivityRecord activity) {
        if (activity == null) {
            return;
        }
        final BridgeOrientationRecord record = mBridgeOrientations.remove(activity);
        if (record != null && record.timeoutRunnable != null) {
            mService.mH.removeCallbacks(record.timeoutRunnable);
            record.timeoutRunnable = null;
        }
    }

    private void clearBridgeOrientationsForTaskLocked(@Nullable Task task) {
        if (task == null) {
            return;
        }
        for (int i = mBridgeOrientations.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mBridgeOrientations.keyAt(i);
            if (activity.getTask() == task) {
                final BridgeOrientationRecord record = mBridgeOrientations.removeAt(i);
                if (record.timeoutRunnable != null) {
                    mService.mH.removeCallbacks(record.timeoutRunnable);
                    record.timeoutRunnable = null;
                }
            }
        }
    }

    private void clearBridgeOrientationsLocked() {
        for (int i = mBridgeOrientations.size() - 1; i >= 0; i--) {
            final BridgeOrientationRecord record = mBridgeOrientations.valueAt(i);
            if (record.timeoutRunnable != null) {
                mService.mH.removeCallbacks(record.timeoutRunnable);
                record.timeoutRunnable = null;
            }
        }
        mBridgeOrientations.clear();
    }

    private void dismissAppJumpTaskLocked(AppJumpSession session) {
        if (mAppJumpSessions.get(session.task.mTaskId) != session || session.dismissRequested) {
            return;
        }
        session.dismissRequested = true;
        finishAppJumpWhenReadyLocked(session, SystemClock.uptimeMillis()
                + OPEN_REVEAL_TIMEOUT_MS);
    }

    private void finishAppJumpWhenReadyLocked(AppJumpSession session, long deadline) {
        final Task task = session.task;
        if (mAppJumpSessions.get(task.mTaskId) != session || !task.isAttached()
                || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
            return;
        }
        if (!session.dismissRequested) {
            return;
        }
        if (!session.handoff.hasReturnedToSource()
                && session.handoff.hasFlowActivity(task.getTopNonFinishingActivity())) {
            cancelAppJumpDismissLocked(session);
            return;
        }
        final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
        if ((state.isOpening() || state.isTransformAnimating())
                && SystemClock.uptimeMillis() < deadline) {
            mService.mH.postDelayed(() -> {
                synchronized (mService.mGlobalLock) {
                    finishAppJumpWhenReadyLocked(session, deadline);
                }
            }, 16);
        } else if (state.isMomentCompact() || state.isOpening() || state.isTransformAnimating()) {
            closeMomentTaskLocked(task);
        } else {
            startMomentCloseAnimationLocked(task, state);
        }
    }

    boolean shouldSuppressRelaunchForConversion(ActivityRecord activity) {
        synchronized (mService.mGlobalLock) {
            final Task task = activity != null ? activity.getTask() : null;
            final MomentTaskSurfaceState state = getStateLocked(task);
            return state != null && state.shouldSuppressConversionRelaunch();
        }
    }

    void setDefaultScale(float scale) {
        synchronized (mService.mGlobalLock) {
            mDefaultScale = clampScale(scale);
            forAllMomentTasksLocked(task -> {
                final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
                if (state.isMomentCompact() || state.isOpening() || state.isClosing()
                        || state.isTransformAnimating()) {
                    return;
                }
                state.setScale(clampScaleLocked(task, state, mDefaultScale));
                constrainMomentPositionLocked(task, state);
                updateHandleWindowLocked(task);
                scheduleMomentSurfaceUpdateLocked(task);
            });
        }
    }

    float getDefaultScale() {
        synchronized (mService.mGlobalLock) {
            return mDefaultScale;
        }
    }

    int startActivityInMoment(Intent intent, int userId) {
        if (!isEnabledForUser(userId)) {
            throw new IllegalStateException("Moment is disabled");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        final ActivityOptions options = ActivityOptions.makeCustomAnimation(
                ActivityThread.currentActivityThread().getSystemUiContext(),
                0 /* enterResId */, 0 /* exitResId */);
        options.setDisableStartingWindow(true);
        options.setLaunchWindowingMode(WINDOWING_MODE_MOMENT);
        synchronized (mService.mGlobalLock) {
            final DisplayContent displayContent = mService.getDefaultDisplayContentLocked();
            if (displayContent != null) {
                final Rect displayBounds = new Rect();
                displayContent.getBounds(displayBounds);
                if (displayBounds.width() > displayBounds.height()) {
                    options.setLaunchBounds(new Rect(0, 0,
                            displayBounds.height(), displayBounds.width()));
                }
            }
        }
        final Bundle bundle = options.toBundle();
        final long identity = Binder.clearCallingIdentity();
        try {
            return mService.mAtmService.startActivityAsUser(null, SYSTEM_PACKAGE_NAME, null,
                    intent, null, null, null, 0, 0, null, bundle, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    int convertTaskToMoment(int taskId) {
        if (!isEnabled()) {
            throw new IllegalStateException("Moment is disabled");
        }
        synchronized (mService.mGlobalLock) {
            final Task task = taskId == INVALID_TASK_ID
                    ? mService.mRoot.getTopDisplayFocusedRootTask()
                    : mService.mRoot.anyTaskForId(taskId);
            if (task == null) {
                throw new IllegalStateException("No target task");
            }
            final Task rootTask = task.getRootTask() != null ? task.getRootTask() : task;
            if (!rootTask.isRootTask() || !rootTask.isActivityTypeStandard()
                    || rootTask.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                    || !rootTask.isVisibleRequested()) {
                throw new IllegalStateException(
                        "Task must be a visible fullscreen standard task: " + rootTask.mTaskId);
            }
            final ActivityRecord topActivity = rootTask.topRunningActivity();
            final DisplayContent displayContent = rootTask.getDisplayContent();
            if (topActivity == null || displayContent == null || rootTask.mSurfaceControl == null
                    || !rootTask.mSurfaceControl.isValid()) {
                throw new IllegalStateException("Task is not ready: " + rootTask.mTaskId);
            }

            final Rect taskBounds = new Rect(rootTask.getBounds());
            if (taskBounds.isEmpty()) {
                throw new IllegalStateException("Task has no bounds: " + rootTask.mTaskId);
            }

            final MomentTaskSurfaceState state = new MomentTaskSurfaceState(rootTask,
                    mDefaultScale);
            state.setFixedAspectRatio(mFixedAspectRatio);
            if (mFixedAspectRatio) {
                final Rect crop = new Rect(taskBounds);
                crop.offsetTo(0, 0);
                captureLayoutSnapshotLocked(rootTask, crop);
                state.setLayoutSnapshot(mLayoutSnapshots.get(rootTask.mTaskId));
            }
            state.prepareForFullscreenConversion(mFixedAspectRatio);
            mStates.put(rootTask.mTaskId, state);
            if (mFixedAspectRatio) {
                markNoAnimationLocked(rootTask);
                rootTask.setWindowingMode(WINDOWING_MODE_MOMENT);
            }
            ensureMomentTaskLayoutLocked(rootTask, state);
            constrainMomentPositionLocked(rootTask, state);
            final Rect targetBounds = state.getSurfaceBounds();
            final Rect fullscreenBounds = state.getFullscreenBounds();
            final float startScaleX = (float) fullscreenBounds.width()
                    / rootTask.getBounds().width();
            final float startScaleY = (float) fullscreenBounds.height()
                    / rootTask.getBounds().height();

            markNoAnimationLocked(rootTask);
            rootTask.setWindowingMode(WINDOWING_MODE_MOMENT);
            if (rootTask.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                mStates.remove(rootTask.mTaskId);
                removeLayoutSnapshotLocked(rootTask.mTaskId);
                state.destroy(rootTask.getSyncTransaction());
                throw new IllegalStateException(
                        "Task does not support Moment: " + rootTask.mTaskId);
            }
            rootTask.forAllActivities(activity -> {
                activity.ensureActivityConfiguration(true /* ignoreVisibility */);
            });
            waitForLayoutLocked(rootTask, false /* animationFinished */);
            startTaskTransformAnimationFromLocked(rootTask, state, startScaleX, startScaleY,
                    fullscreenBounds.exactCenterX(), fullscreenBounds.exactCenterY(), 1f,
                    getDisplayCornerRadiusLocked(rootTask),
                    state.getScale(), state.getScale(), targetBounds.exactCenterX(),
                    targetBounds.exactCenterY(), 1f,
                    MomentGeometry.getCornerRadius(getDensityLocked(rootTask)) * state.getScale(),
                    1f,
                    MomentMotion.SLOW_SPATIAL, ANIMATION_CONVERT_FULLSCREEN);
            return rootTask.mTaskId;
        }
    }

    void exitMomentTask(int taskId) {
        synchronized (mService.mGlobalLock) {
            final Task task = taskId == INVALID_TASK_ID ? null
                    : mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            exitMomentTaskLocked(task);
        }
    }

    void closeMomentTask(int taskId) {
        synchronized (mService.mGlobalLock) {
            final Task task = taskId == INVALID_TASK_ID ? null
                    : mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isMomentCompact()) {
                closeMomentTaskLocked(task);
            } else {
                startMomentCloseAnimationLocked(task, state);
            }
        }
    }

    void exitAll() {
        synchronized (mService.mGlobalLock) {
            exitAllLocked();
        }
    }

    void onTaskRemoved(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task != null) {
                final AppJumpSession removed = mAppJumpSessions.remove(task.mTaskId);
                if (removed != null) {
                    removed.handoff.finish();
                    if (removed.sourceTask != task) {
                        armPostSessionExitGraceForSessionLocked(removed, null /* extraBridge */);
                    }
                }
                for (int i = mAppJumpSessions.size() - 1; i >= 0; i--) {
                    if (mAppJumpSessions.valueAt(i).sourceTask == task) {
                        final AppJumpSession session = mAppJumpSessions.valueAt(i);
                        mAppJumpSessions.removeAt(i);
                        releaseAppJumpSessionLocked(session);
                    }
                }
                clearBridgeOrientationsForTaskLocked(task);
                removeLayoutSnapshotLocked(task.mTaskId);
            }
            removeHandleWindowLocked(task);
            if (task != null) {
                final MomentTaskSurfaceState state = mStates.remove(task.mTaskId);
                if (state != null) {
                    cancelTaskAnimationLocked(task, state);
                    state.destroy(task.getSyncTransaction());
                }
            }
        }
    }

    void onTaskLeftMomentWindowingMode(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null) {
                return;
            }
            stopAppJumpWindowOwnershipLocked(task);
            removeHandleWindowLocked(task);
            final MomentTaskSurfaceState state = mStates.remove(task.mTaskId);
            if (state == null) {
                return;
            }
            removeLayoutSnapshotLocked(task.mTaskId);
            final boolean wasClosing = state.isClosing();
            cancelTaskAnimationLocked(task, state);
            final SurfaceControl.Transaction t = task.getSyncTransaction();
            state.reset(t);
            if (task.mSurfaceControl != null && task.mSurfaceControl.isValid()) {
                if (!wasClosing && task.isVisibleRequested()) {
                    t.show(task.mSurfaceControl);
                } else {
                    t.hide(task.mSurfaceControl);
                }
            }
            state.destroy(t);
            scheduleMomentSurfaceUpdateLocked(task);
        }
    }

    void dumpStatus(PrintWriter pw) {
        synchronized (mService.mGlobalLock) {
            pw.println("Moment enabled=" + mEnabled + " defaultScale=" + mDefaultScale
                    + " fixedAspectRatio=" + mFixedAspectRatio);
            dumpTasksLocked(pw);
        }
    }

    void dumpTasks(PrintWriter pw) {
        synchronized (mService.mGlobalLock) {
            dumpTasksLocked(pw);
        }
    }

    void applyMomentSurface(Task task, SurfaceControl.Transaction t) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            updateAspectRatioLocked(task, state);
            constrainMomentPositionLocked(task, state);
            final boolean visibilityChanged = state.ensureForceTranslucent();
            final ActivityRecord topActivity = task.topRunningActivity();
            if (!state.hasApplied() && task.isVisibleRequested() && topActivity != null
                    && topActivity.isVisibleRequested()) {
                startMomentOpenAnimationLocked(task, state, t);
            }
            final boolean showHandle = shouldShowHandleLocked(task, state);
            state.apply(t, showHandle);
            if (isDebugEnabled()) {
                final Rect letterboxInnerBounds = new Rect();
                if (topActivity != null) {
                    topActivity.getLetterboxInnerBounds(letterboxInnerBounds);
                }
                Slog.d(TAG, "applySurface taskId=" + task.mTaskId
                        + " mode=" + task.getWindowingMode()
                        + " visibleRequested=" + task.isVisibleRequested()
                        + " visible=" + task.isVisible()
                        + " top=" + topActivity
                        + " taskBounds=" + task.getBounds()
                        + " surfaceBounds=" + state.getSurfaceBounds()
                        + " decorationBounds=" + state.getDecorationBounds()
                        + " handleBounds=" + state.getHandleBounds()
                        + " activityBounds="
                        + (topActivity != null ? topActivity.getBounds() : "null")
                        + " requestedOrientation="
                        + (topActivity != null ? topActivity.getRequestedOrientation() : "null")
                        + " configOrientation="
                        + (topActivity != null ? topActivity.getConfiguration().orientation : "null")
                        + " configRotation="
                        + (topActivity != null
                                ? topActivity.getWindowConfiguration().getRotation() : "null")
                        + " sizeCompat="
                        + (topActivity != null && topActivity.inSizeCompatMode())
                        + " sizeCompatBounds="
                        + (topActivity != null && topActivity.hasSizeCompatBounds())
                        + " compatScale="
                        + (topActivity != null ? topActivity.getCompatScale() : "null")
                        + " letterboxed="
                        + (topActivity != null && topActivity.areBoundsLetterboxed())
                        + " appCompatState="
                        + (topActivity != null ? topActivity.getAppCompatState() : "null")
                        + " letterboxInsets="
                        + (topActivity != null ? topActivity.getLetterboxInsets() : "null")
                        + " letterboxInnerBounds=" + letterboxInnerBounds
                        + " geometry={" + state.getDebugGeometry() + "}"
                        + " showHandle=" + showHandle
                        + " forceTranslucentChanged=" + visibilityChanged);
            }
            if (!state.isClosing() && shouldAttachInteractionWindowLocked(task)) {
                updateHandleWindowLocked(task);
            } else {
                removeHandleWindowLocked(task);
            }
            if (visibilityChanged) {
                mService.mRoot.ensureActivitiesVisible();
            }
        }
    }

    boolean shouldApplyMomentSurface(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return false;
            }
            // Moment does not have a separate Shell transition handler, so keep the task
            // surface constrained whenever the task stays in Moment.
            return true;
        }
    }

    Rect moveMomentTask(Task task, int centerX, int centerY) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return new Rect();
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            state.setCenter(centerX, centerY);
            constrainMomentPositionLocked(task, state);
            applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
            return state.getSurfaceBounds();
        }
    }

    void animateMomentHandlePress(Task task, boolean isTouchDown) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || !shouldShowHandleLocked(task, getOrCreateStateLocked(task))) {
                return;
            }
            getOrCreateStateLocked(task).animateHandlePress(isTouchDown);
        }
    }

    float getMomentTaskScale(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return mDefaultScale;
            }
            return getOrCreateStateLocked(task).getScale();
        }
    }

    boolean isMomentCompact(Task task) {
        synchronized (mService.mGlobalLock) {
            final MomentTaskSurfaceState state = getStateLocked(task);
            return task != null && task.getWindowingMode() == WINDOWING_MODE_MOMENT
                    && state != null && state.isMomentCompact();
        }
    }

    void setMomentResizing(Task task, boolean resizing) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) return;
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            state.setResizing(resizing);
            applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
        }
    }

    Rect resizeMomentTask(Task task, float scale, int fixedX, int fixedY,
            boolean resizeFromLeft, boolean resizeFromTop) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return new Rect();
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            state.setScaleFromCorner(clampScaleLocked(task, state, scale), fixedX, fixedY,
                    resizeFromLeft, resizeFromTop);
            constrainMomentPositionLocked(task, state);
            applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
            return state.getSurfaceBounds();
        }
    }

    void finishMomentResize(Task task, float restoreScale) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.getScale() > MIN_SCALE + COMPACT_EPSILON || state.isMomentCompact()) {
                return;
            }
            state.enterCompact(restoreScale > MIN_SCALE + COMPACT_EPSILON
                    ? clampScaleLocked(task, state, restoreScale)
                    : clampScaleLocked(task, state, mDefaultScale));
            constrainMomentCompactPositionLocked(task, state);
            applyMomentStateLocked(task, state, false /* allowSurfaceHandle */);
        }
    }

    void enterMomentCompact(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isMomentCompact() || state.isOpening() || state.isClosing()
                    || state.isTransformAnimating()) {
                return;
            }
            final Rect bounds = state.getSurfaceBounds();
            final float endScale = clampScaleLocked(task, state, MIN_SCALE);
            startTaskTransformAnimationLocked(task, state, endScale, bounds.exactCenterX(),
                    bounds.exactCenterY(), 1f,
                    MomentGeometry.getCornerRadius(getDensityLocked(task)) * endScale,
                    1f,
                    MomentMotion.DEFAULT_SPATIAL,
                    ANIMATION_ENTER_COMPACT);
        }
    }

    Rect moveMomentCompactTask(Task task, float centerX, float centerY, float magnetProgress,
            boolean constrainToMovementBounds) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return new Rect();
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (!state.isMomentCompact()) {
                return new Rect();
            }
            final float dismissScale = getCompactDismissScaleLocked(task, state);
            final float scale = lerp(state.getCompactScale(), dismissScale, magnetProgress);
            state.setCompactDragTransform(scale, Math.round(centerX), Math.round(centerY));
            if (constrainToMovementBounds) {
                constrainMomentCompactPositionLocked(task, state);
            }
            applyMomentStateLocked(task, state, false /* allowSurfaceHandle */);
            return state.getSurfaceBounds();
        }
    }

    Rect getMomentCompactMovementBounds(Task task) {
        synchronized (mService.mGlobalLock) {
            final Rect movementBounds = new Rect();
            if (task != null && task.getWindowingMode() == WINDOWING_MODE_MOMENT) {
                final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
                if (state.isMomentCompact()) {
                    getMomentCompactMovementBoundsLocked(task, state, movementBounds);
                }
            }
            return movementBounds;
        }
    }

    int getMomentDisplayBottom(Task task) {
        synchronized (mService.mGlobalLock) {
            return task != null ? getDisplayBottomLocked(task) : 0;
        }
    }

    int getMomentCompactStashedSide(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return 0;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            return state.isMomentCompact() ? state.getCompactStashedSide() : 0;
        }
    }

    void setMomentCompactStashedSide(Task task, int side) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isMomentCompact()) {
                state.setCompactStashedSide(side);
            }
        }
    }

    boolean canStashMomentCompact(Task task, int side) {
        synchronized (mService.mGlobalLock) {
            final DisplayContent displayContent = task != null ? task.getDisplayContent() : null;
            if (displayContent == null || displayContent.getDisplayInfo().displayCutout == null) {
                return true;
            }
            return side < 0
                    ? displayContent.getDisplayInfo().displayCutout.getSafeInsetLeft() == 0
                    : displayContent.getDisplayInfo().displayCutout.getSafeInsetRight() == 0;
        }
    }

    void dismissMomentCompactTask(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isMomentCompact()) {
                closeMomentTaskLocked(task);
            }
        }
    }

    void finishMomentCompactDrag(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (!state.isMomentCompact()) {
                return;
            }
            state.setCompactDragTransform(state.getCompactScale(),
                    state.getSurfaceBounds().centerX(), state.getSurfaceBounds().centerY());
            constrainMomentCompactPositionLocked(task, state);
            applyMomentStateLocked(task, state, false /* allowSurfaceHandle */);
        }
    }

    void restoreMomentCompact(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (!state.isMomentCompact() || state.isOpening() || state.isClosing()
                    || state.isTransformAnimating()) {
                return;
            }
            final Rect safeBounds = new Rect();
            getMomentSafeBoundsLocked(task, safeBounds);
            final DisplayContent display = task.getDisplayContent();
            if (display != null) {
                final android.graphics.Insets imeInsets = display.getInsetsStateController()
                        .getRawInsetsState().calculateInsets(display.getBounds(), display.getBounds(),
                                android.view.WindowInsets.Type.ime(), false);
                safeBounds.bottom = Math.max(safeBounds.top + 1,
                        Math.min(safeBounds.bottom, display.getBounds().bottom - imeInsets.bottom));
            }
            final Rect crop = state.getContentCrop();
            final float availableHeight = Math.max(1f, safeBounds.height()
                    - HANDLE_AREA_HEIGHT_DP * getDensityLocked(task));
            final float endScale = Math.max(MIN_SCALE, Math.min(
                    clampScaleLocked(task, state, state.getCompactRestoreScale()),
                    Math.min((float) safeBounds.width() / Math.max(1, crop.width()),
                            availableHeight / Math.max(1, crop.height()))));
            startTaskTransformAnimationLocked(task, state, endScale,
                    safeBounds.exactCenterX(), safeBounds.exactCenterY(), 1f,
                    MomentGeometry.getCornerRadius(getDensityLocked(task)) * endScale,
                    1f,
                    MomentMotion.DEFAULT_SPATIAL, ANIMATION_RESTORE_COMPACT);
        }
    }

    void expandMomentTaskAnimated(int taskId) {
        synchronized (mService.mGlobalLock) {
            final Task task = taskId == INVALID_TASK_ID ? null
                    : mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isOpening() || state.isClosing() || state.isTransformAnimating()) {
                return;
            }
            if (state.hasFixedAspectRatio()) {
                captureLayoutSnapshotLocked(task, state.getContentCrop());
            }
            final Rect taskBounds = task.getBounds();
            final Rect fullscreenBounds = state.getFullscreenBounds();
            final float endScaleX = (float) fullscreenBounds.width() / taskBounds.width();
            final float endScaleY = (float) fullscreenBounds.height() / taskBounds.height();
            startTaskTransformAnimationLocked(task, state, endScaleX, endScaleY,
                    fullscreenBounds.exactCenterX(), fullscreenBounds.exactCenterY(), 1f,
                    getDisplayCornerRadiusLocked(task),
                    0f,
                    MomentMotion.SLOW_SPATIAL, ANIMATION_EXPAND_FULLSCREEN);
        }
    }

    void performMomentBack(Task task) {
        final int displayId;
        final int targetUid;
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final ActivityRecord topActivity = task.topRunningActivity();
            if (topActivity == null) {
                return;
            }
            if (!task.isFocused()) {
                mService.mAtmService.setFocusedTask(task.mTaskId, topActivity);
                return;
            }
            displayId = task.getDisplayId();
            targetUid = topActivity.getUid();
        }
        mService.mH.post(() -> injectBackKey(displayId, targetUid));
    }

    private void injectBackKey(int displayId, int targetUid) {
        final long downTime = SystemClock.uptimeMillis();
        injectBackKeyEvent(KeyEvent.ACTION_DOWN, downTime, downTime, displayId, targetUid);
        injectBackKeyEvent(KeyEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), displayId,
                targetUid);
    }

    private void injectBackKeyEvent(int action, long downTime, long eventTime, int displayId,
            int targetUid) {
        final KeyEvent event = KeyEvent.obtain(downTime, eventTime, action, KeyEvent.KEYCODE_BACK,
                0 /* repeat */, 0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD,
                0 /* scanCode */, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
                displayId, null /* characters */);
        try {
            mService.mInputManager.injectInputEventToTarget(
                    event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, targetUid);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Unable to deliver BACK to Moment task uid=" + targetUid, e);
        } finally {
            event.recycle();
        }
    }

    private MomentTaskSurfaceState getOrCreateStateLocked(Task task) {
        final Task rootTask = task != null ? task.getRootTask() : null;
        final Task target = rootTask != null ? rootTask : task;
        MomentTaskSurfaceState state = target != null ? mStates.get(target.mTaskId) : null;
        if (state == null) {
            state = new MomentTaskSurfaceState(target, mDefaultScale);
            state.setFixedAspectRatio(mFixedAspectRatio);
            mStates.put(target.mTaskId, state);
        }
        ensureMomentTaskLayoutLocked(target, state);
        return state;
    }

    private MomentTaskSurfaceState getStateLocked(Task task) {
        final Task rootTask = task != null ? task.getRootTask() : null;
        final Task target = rootTask != null ? rootTask : task;
        return target != null ? mStates.get(target.mTaskId) : null;
    }

    DisplayFrames getMomentDisplayFrames(Task task) {
        if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
            return null;
        }
        final Task rootTask = task.getRootTask();
        final Task target = rootTask != null ? rootTask : task;
        MomentTaskSurfaceState state = mStates.get(target.mTaskId);
        if (state == null) {
            state = new MomentTaskSurfaceState(target, mDefaultScale);
            state.setFixedAspectRatio(mFixedAspectRatio);
            mStates.put(target.mTaskId, state);
        }
        return state.getPortraitDisplayFrames();
    }

    private void ensureMomentTaskLayoutLocked(Task task, MomentTaskSurfaceState state) {
        final DisplayContent displayContent = task != null ? task.getDisplayContent() : null;
        if (displayContent == null) {
            return;
        }
        final Rect displayBounds = new Rect();
        displayContent.getBounds(displayBounds);
        final boolean landscape = displayBounds.width() > displayBounds.height();
        final Rect desiredBounds = state.hasFixedAspectRatio()
                ? state.getFixedAspectRatioBounds(displayContent) : new Rect(0, 0,
                landscape ? displayBounds.height() : displayBounds.width(),
                landscape ? displayBounds.width() : displayBounds.height());
        if (!state.shouldPreserveTaskBounds() && !task.getBounds().equals(desiredBounds)) {
            final boolean coverLayout = state.hasFixedAspectRatio() && state.hasApplied()
                    && !mLayoutSnapshots.containsKey(task.mTaskId);
            if (coverLayout) {
                captureLayoutSnapshotLocked(task, state.getContentCrop());
            }
            task.setBounds(desiredBounds);
            if (mLayoutSnapshots.containsKey(task.mTaskId)) {
                waitForLayoutLocked(task, !state.isTransformAnimating());
            }
        }
        final float maxScale = calculateMomentMaxScaleLocked(task);
        state.updateLandscapeLayout(landscape, LANDSCAPE_DEFAULT_SCALE, maxScale);
        if (!landscape && state.getScale() > maxScale) {
            state.setScale(maxScale);
        }
    }

    private float calculateMomentMaxScaleLocked(Task task) {
        final Rect safeBounds = new Rect();
        getMomentSafeBoundsLocked(task, safeBounds);
        final Rect taskBounds = task.getBounds();
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent != null && displayContent.getBounds().width()
                > displayContent.getBounds().height()) {
            final int taskWidth = Math.max(1, taskBounds.width());
            return Math.max(MIN_SCALE,
                    Math.min(MAX_SCALE, (float) safeBounds.width() / taskWidth));
        }
        final int taskHeight = Math.max(1, taskBounds.height());
        final float density = getDensityLocked(task);
        final float availableHeight = safeBounds.height()
                - (HANDLE_MENU_TOP_INSET_DP + HANDLE_AREA_HEIGHT_DP) * density;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, availableHeight / taskHeight));
    }

    private float clampScaleLocked(Task task, MomentTaskSurfaceState state, float scale) {
        final float maxScale = calculateMomentMaxScaleLocked(task);
        return Math.max(MIN_SCALE, Math.min(maxScale, scale));
    }

    private void constrainMomentPositionLocked(Task task, MomentTaskSurfaceState state) {
        if (state.isMomentCompact() || state.isTransformAnimating()) {
            return;
        }
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null) {
            return;
        }
        final Rect safeBounds = new Rect();
        getMomentSafeBoundsLocked(task, safeBounds);
        final Rect surfaceBounds = state.getSurfaceBounds();
        final float halfWidth = surfaceBounds.width() / 2f;
        final float halfHeight = surfaceBounds.height() / 2f;
        final float density = getDensityLocked(task);
        final float handleHeight = MomentGeometry.getBottomHandleHeight(
                surfaceBounds.width(), density);
        final float handleCenterOffset = HANDLE_AREA_HEIGHT_DP * density / 2f;
        final float minCenterY = safeBounds.top - handleCenterOffset
                + handleHeight / 2f - halfHeight;
        final boolean landscape = displayContent.getBounds().width()
                > displayContent.getBounds().height();

        final float minCenterX;
        final float maxCenterX;
        final float maxCenterY;
        if (landscape) {
            final float requiredHalfWidth = Math.max(halfWidth,
                    HANDLE_MENU_WIDTH_DP * density / 2f);
            minCenterX = safeBounds.left + requiredHalfWidth;
            maxCenterX = safeBounds.right - requiredHalfWidth;
            maxCenterY = safeBounds.bottom - handleCenterOffset - handleHeight / 2f - halfHeight;
        } else {
            final float topHandleHalfWidth = Math.max(HANDLE_MENU_WIDTH_DP * density / 2f,
                    surfaceBounds.width() / 4f);
            minCenterX = safeBounds.left + topHandleHalfWidth;
            maxCenterX = safeBounds.right - topHandleHalfWidth;
            final float maxTaskBottom = safeBounds.bottom - handleCenterOffset - handleHeight / 2f;
            maxCenterY = maxTaskBottom - halfHeight;
        }
        final int currentCenterX = state.getCenterX();
        final int currentCenterY = state.getCenterY();
        final int constrainedCenterX = Math.round(
                clampToRange(currentCenterX, minCenterX, maxCenterX));
        final int constrainedCenterY = Math.round(
                clampToRange(currentCenterY, minCenterY, maxCenterY));
        if (constrainedCenterX != currentCenterX || constrainedCenterY != currentCenterY) {
            state.setCenter(constrainedCenterX, constrainedCenterY);
        }
    }

    private void constrainMomentCompactPositionLocked(Task task,
            MomentTaskSurfaceState state) {
        final Rect movementBounds = new Rect();
        getMomentCompactMovementBoundsLocked(task, state, movementBounds);
        final int currentCenterX = state.getCenterX();
        final int currentCenterY = state.getCenterY();
        final int constrainedCenterX = Math.round(clampToRange(
                currentCenterX, movementBounds.left, movementBounds.right));
        final int constrainedCenterY = Math.round(clampToRange(
                currentCenterY, movementBounds.top, movementBounds.bottom));
        if (constrainedCenterX != currentCenterX || constrainedCenterY != currentCenterY) {
            state.setCenter(constrainedCenterX, constrainedCenterY);
        }
    }

    private void getMomentCompactMovementBoundsLocked(Task task,
            MomentTaskSurfaceState state, Rect outBounds) {
        getMomentSafeBoundsLocked(task, outBounds);
        final Rect taskBounds = task.getBounds();
        final int halfWidth = Math.round(taskBounds.width() * state.getCompactScale() / 2f);
        final int halfHeight = Math.round(taskBounds.height() * state.getCompactScale() / 2f);
        outBounds.inset(halfWidth, halfHeight);
        if (outBounds.left > outBounds.right) {
            final int centerX = outBounds.centerX();
            outBounds.left = centerX;
            outBounds.right = centerX;
        }
        if (outBounds.top > outBounds.bottom) {
            final int centerY = outBounds.centerY();
            outBounds.top = centerY;
            outBounds.bottom = centerY;
        }
    }

    private void getMomentSafeBoundsLocked(Task task, Rect outBounds) {
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null) {
            outBounds.set(task.getBounds());
            return;
        }
        displayContent.getStableRect(outBounds);
        final int margin = Math.round(MOVEMENT_SAFE_MARGIN_DP * getDensityLocked(task));
        outBounds.inset(margin, margin);
    }

    private float getDensityLocked(Task task) {
        final DisplayContent displayContent = task.getDisplayContent();
        return displayContent != null ? displayContent.getDisplayMetrics().density : 1f;
    }

    private static float clampToRange(float value, float min, float max) {
        if (min > max) {
            return (min + max) / 2f;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void startMomentCloseAnimationLocked(Task task, MomentTaskSurfaceState state) {
        if (state.isOpening() || state.isClosing() || state.isTransformAnimating()) {
            return;
        }
        cancelTaskAnimationLocked(task, state);
        final Rect startBounds = state.getSurfaceBounds();
        final float taskScale = state.getScale();
        final float density = getDensityLocked(task);
        final int color = mService.mContext.getColor(android.R.color.system_accent1_100);
        final int generation;
        try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
            generation = state.beginCloseAnimation(t, color,
                    MomentGeometry.getCornerRadius(density) * taskScale);
            if (generation < 0) {
                Slog.w(TAG, "Close animation unavailable for task " + task.mTaskId
                        + ", closing immediately");
                closeMomentTaskLocked(task);
                return;
            }
            t.apply();
        } catch (Surface.OutOfResourcesException e) {
            Slog.w(TAG, "Unable to create close animation layer for task " + task.mTaskId, e);
            closeMomentTaskLocked(task);
            return;
        }
        Slog.i(TAG, "Started close animation for task " + task.mTaskId
                + " bounds=" + startBounds);
        removeHandleWindowLocked(task);
        scheduleMomentSurfaceUpdateLocked(task);

        final MomentGeometry.MorphFrame frame = new MomentGeometry.MorphFrame();
        mAnimationRunner.start(state, MomentMotion.EXIT_SPATIAL,
                () -> state.isCloseAnimation(generation), (spatial, effects) -> {
                MomentGeometry.evaluateCloseMorph(startBounds, taskScale, density,
                        MomentMotion.clamp(spatial), effects, frame);
                try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                    if (state.applyCloseAnimationFrame(t, generation, frame.centerX, frame.centerY,
                            frame.width, frame.height, frame.cornerRadius, frame.alpha)) {
                        t.apply();
                    }
                }
                }, () -> mService.mH.post(() -> onMomentCloseAnimationFinished(
                        task.mTaskId, state, generation)));
    }

    private void startMomentOpenAnimationLocked(Task task, MomentTaskSurfaceState state,
            SurfaceControl.Transaction startTransaction) {
        if (state.isOpening() || state.isClosing()) {
            return;
        }
        final Rect endBounds = state.getSurfaceBounds();
        final float taskScale = state.getScale();
        final float density = getDensityLocked(task);
        final MomentGeometry.MorphFrame initialFrame = new MomentGeometry.MorphFrame();
        MomentGeometry.evaluateOpenMorph(endBounds, taskScale, density, 0f, 0f, initialFrame);
        final int color = mService.mContext.getColor(android.R.color.system_accent1_100);
        final int generation = state.beginOpenAnimation(startTransaction, color,
                initialFrame.centerX, initialFrame.centerY, initialFrame.width,
                initialFrame.height, initialFrame.cornerRadius);
        if (generation < 0) {
            Slog.w(TAG, "Open animation unavailable for task " + task.mTaskId);
            return;
        }
        final int taskId = task.mTaskId;
        startTransaction.addTransactionCommittedListener(Runnable::run,
                () -> startMomentOpenAnimator(taskId, state, generation, endBounds, taskScale,
                        density));
        removeHandleWindowLocked(task);
        Slog.i(TAG, "Prepared open animation for task " + taskId + " bounds=" + endBounds);
    }

    private void startMomentOpenAnimator(int taskId, MomentTaskSurfaceState state, int generation,
            Rect endBounds, float taskScale, float density) {
        final MomentGeometry.MorphFrame frame = new MomentGeometry.MorphFrame();
        mAnimationRunner.start(state, MomentMotion.DEFAULT_SPATIAL,
                () -> state.isOpenAnimation(generation), (spatial, effects) -> {
            MomentGeometry.evaluateOpenMorph(endBounds, taskScale, density,
                    spatial, effects, frame);
            try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                if (state.applyOpenAnimationFrame(t, generation, frame.centerX, frame.centerY,
                        frame.width, frame.height, frame.cornerRadius, frame.alpha)) {
                    t.apply();
                }
            }
        }, () -> {
            final long revealDeadline = SystemClock.uptimeMillis() + OPEN_REVEAL_TIMEOUT_MS;
            mService.mH.post(() -> finishMomentOpenAnimationWhenReady(taskId, state,
                    generation, revealDeadline));
        });
    }

    private void finishMomentOpenAnimationWhenReady(int taskId, MomentTaskSurfaceState state,
            int generation, long revealDeadline) {
        synchronized (mService.mGlobalLock) {
            final Task task = mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || getStateLocked(task) != state || !state.isOpenAnimation(generation)) {
                return;
            }
            final ActivityRecord topActivity = task.topRunningActivity();
            final WindowState mainWindow = topActivity != null
                    ? topActivity.findMainWindow(false /* includeStartingApp */) : null;
            if ((mainWindow == null || !mainWindow.isDrawn())
                    && SystemClock.uptimeMillis() < revealDeadline) {
                mService.mH.postDelayed(() -> finishMomentOpenAnimationWhenReady(taskId, state,
                        generation, revealDeadline), 16);
                return;
            }
            try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                if (!state.finishOpenAnimation(t, generation)) {
                    return;
                }
                final boolean showHandle = shouldShowHandleLocked(task, state);
                state.apply(t, showHandle);
                t.apply();
            }
            if (shouldAttachInteractionWindowLocked(task)) {
                updateHandleWindowLocked(task);
            }
            scheduleMomentSurfaceUpdateLocked(task);
            Slog.i(TAG, "Finished open animation for task " + taskId);
        }
    }

    private void onMomentCloseAnimationFinished(int taskId, MomentTaskSurfaceState state,
            int generation) {
        synchronized (mService.mGlobalLock) {
            final Task task = mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || getStateLocked(task) != state) {
                cleanupStaleAnimationStateLocked(taskId, task, state);
                return;
            }
            try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                if (!state.completeCloseAnimation(t, generation)) {
                    return;
                }
                t.apply();
            }
            Slog.i(TAG, "Finished close animation for task " + taskId);
            closeMomentTaskLocked(task);
        }
    }

    private void startTaskTransformAnimationLocked(Task task, MomentTaskSurfaceState state,
            float endScale, float endCenterX, float endCenterY, float endAlpha,
            float endDisplayedCornerRadius, float endCropProgress, MomentMotion.Spec spatialSpec,
            int endAction) {
        startTaskTransformAnimationLocked(task, state, endScale, endScale, endCenterX, endCenterY,
                endAlpha, endDisplayedCornerRadius, endCropProgress, spatialSpec, endAction);
    }

    private void startTaskTransformAnimationLocked(Task task, MomentTaskSurfaceState state,
            float endScaleX, float endScaleY, float endCenterX, float endCenterY, float endAlpha,
            float endDisplayedCornerRadius, float endCropProgress, MomentMotion.Spec spatialSpec,
            int endAction) {
        final Rect startBounds = state.getSurfaceBounds();
        final float startScale = state.getScale();
        startTaskTransformAnimationFromLocked(task, state, startScale, startScale,
                startBounds.exactCenterX(), startBounds.exactCenterY(), 1f,
                state.getDisplayedCornerRadius(), endScaleX, endScaleY, endCenterX, endCenterY,
                endAlpha, endDisplayedCornerRadius, endCropProgress,
                spatialSpec, endAction);
    }

    private void startTaskTransformAnimationFromLocked(Task task, MomentTaskSurfaceState state,
            float startScaleX, float startScaleY, float startCenterX, float startCenterY,
            float startAlpha, float startDisplayedCornerRadius, float endScaleX, float endScaleY,
            float endCenterX, float endCenterY, float endAlpha, float endDisplayedCornerRadius,
            float endCropProgress, MomentMotion.Spec spatialSpec, int endAction) {
        final float startCropProgress = endAction == ANIMATION_CONVERT_FULLSCREEN ? 0f : 1f;
        final int generation = state.beginTransformAnimation(startScaleX, startScaleY,
                startCenterX, startCenterY, startAlpha, startDisplayedCornerRadius,
                startCropProgress);
        final SurfaceControl.Transaction startTransaction = task.getSyncTransaction();
        state.apply(startTransaction, false /* showHandle */);
        if (endAction == ANIMATION_RESTORE_COMPACT) {
            updateHandleWindowLocked(task);
        } else {
            removeHandleWindowLocked(task);
        }
        scheduleMomentSurfaceUpdateLocked(task);

        final Rect baseBounds = new Rect(task.getBounds());
        final Rect contentCrop = state.getContentCrop();
        final Rect envelope = new Rect(task.getDisplayContent().getBounds());
        // Preserve an intentionally stashed source or destination, but do not let spring
        // overshoot push an otherwise visible task off the physical display.
        envelope.union(Math.round(startCenterX - baseBounds.width() * startScaleX / 2f),
                Math.round(startCenterY - baseBounds.height() * startScaleY / 2f),
                Math.round(startCenterX + baseBounds.width() * startScaleX / 2f),
                Math.round(startCenterY + baseBounds.height() * startScaleY / 2f));
        final boolean[] settlingPosted = {false};
        // IME/configuration changes can keep the task's sync transaction pending while app
        // buffers redraw. Do not run the animation against an uncommitted initial pose: it
        // would finish invisibly and the first presented frame would already be a small window.
        // Use the same committed-frame handoff as the Moment open animation. The generation
        // check in the runner discards starts after task removal or animation cancellation.
        startTransaction.addTransactionCommittedListener(Runnable::run,
                () -> mAnimationRunner.start(state, spatialSpec,
                () -> state.isTransformAnimation(generation), (progress, effects) -> {
                final float cropProgress = lerp(startCropProgress, endCropProgress, effects);
                final float cropWidth = lerp(baseBounds.width(), contentCrop.width(), cropProgress);
                final float cropHeight = lerp(baseBounds.height(), contentCrop.height(), cropProgress);
                final float rawScaleX = Math.max(0.001f, lerp(startScaleX, endScaleX, progress));
                final float rawScaleY = Math.max(0.001f, lerp(startScaleY, endScaleY, progress));
                final float fit = Math.min(1f, Math.min(envelope.width() / (cropWidth * rawScaleX),
                        envelope.height() / (cropHeight * rawScaleY)));
                final float scaleX = rawScaleX * fit;
                final float scaleY = rawScaleY * fit;
                final float halfWidth = cropWidth * scaleX / 2f;
                final float halfHeight = cropHeight * scaleY / 2f;
                final float centerX = clampToRange(lerp(startCenterX, endCenterX, progress),
                        envelope.left + halfWidth, envelope.right - halfWidth);
                final float centerY = clampToRange(lerp(startCenterY, endCenterY, progress),
                        envelope.top + halfHeight, envelope.bottom - halfHeight);
                final float alpha = MomentMotion.clamp(lerp(startAlpha, endAlpha, effects));
                final float displayedCornerRadius = lerp(startDisplayedCornerRadius,
                        endDisplayedCornerRadius, MomentMotion.clamp(progress));
                try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                    if (state.applyAnimatedTransform(t, generation, scaleX, scaleY, centerX, centerY,
                            alpha, displayedCornerRadius, cropProgress)) {
                        t.apply();
                    }
                }
                if (endAction == ANIMATION_CONVERT_FULLSCREEN && progress >= 0.95f
                        && !settlingPosted[0]) {
                    settlingPosted[0] = true;
                    mService.mH.post(() -> {
                        synchronized (mService.mGlobalLock) {
                            if (getStateLocked(task) == state
                                    && state.isTransformAnimation(generation)) {
                                finishLayoutSnapshotAnimationLocked(task);
                            }
                        }
                    });
                }
                }, () -> mService.mH.post(() -> onTaskTransformAnimationFinished(task.mTaskId,
                        state, generation, endScaleX, endCenterX, endCenterY, endAction))));
    }

    private float getDisplayCornerRadiusLocked(Task task) {
        final DisplayContent displayContent = task != null ? task.getDisplayContent() : null;
        if (displayContent == null) {
            return 0f;
        }
        return ScreenDecorationsUtils.getWindowCornerRadius(
                displayContent.getDisplayPolicy().getSystemUiContext());
    }

    private void onTaskTransformAnimationFinished(int taskId, MomentTaskSurfaceState state,
            int generation, float endScale, float endCenterX, float endCenterY, int endAction) {
        synchronized (mService.mGlobalLock) {
            final Task task = mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || getStateLocked(task) != state
                    || !state.finishTransformAnimation(generation)) {
                if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                    cleanupStaleAnimationStateLocked(taskId, task, state);
                }
                return;
            }
            switch (endAction) {
                case ANIMATION_EXPAND_FULLSCREEN:
                    exitMomentTaskLocked(task);
                    break;
                case ANIMATION_RESTORE_COMPACT:
                    state.setCenter(Math.round(endCenterX), Math.round(endCenterY));
                    state.finishCompactRestore(endScale);
                    constrainMomentPositionLocked(task, state);
                    applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
                    break;
                case ANIMATION_ENTER_COMPACT:
                    final float restoreScale = state.getScale();
                    state.setCenter(Math.round(endCenterX), Math.round(endCenterY));
                    state.setScale(endScale);
                    state.enterCompact(restoreScale);
                    constrainMomentCompactPositionLocked(task, state);
                    applyMomentStateLocked(task, state, false /* allowSurfaceHandle */);
                    break;
                case ANIMATION_CONVERT_FULLSCREEN:
                    state.finishFullscreenConversion();
                    state.setCenter(Math.round(endCenterX), Math.round(endCenterY));
                    state.setScale(endScale);
                    constrainMomentPositionLocked(task, state);
                    applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
                    finishLayoutSnapshotAnimationLocked(task);
                    break;
                default:
                    break;
            }
        }
    }

    private void cleanupStaleAnimationStateLocked(int taskId, Task task,
            MomentTaskSurfaceState state) {
        if (mStates.get(taskId) != state) {
            return;
        }
        mStates.remove(taskId);
        if (task != null) {
            removeHandleWindowLocked(task);
        }
        mAnimationRunner.cancel(state);
        if (task != null) {
            final SurfaceControl.Transaction t = task.getSyncTransaction();
            state.reset(t);
            if (task.mSurfaceControl != null && task.mSurfaceControl.isValid()) {
                if (task.isVisibleRequested()) {
                    t.show(task.mSurfaceControl);
                } else {
                    t.hide(task.mSurfaceControl);
                }
            }
            state.destroy(t);
            scheduleMomentSurfaceUpdateLocked(task);
        } else {
            try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                state.destroy(t);
                t.apply();
            }
        }
    }

    private void cancelTaskAnimationLocked(Task task, MomentTaskSurfaceState state) {
        state.cancelTransformAnimation();
        mAnimationRunner.cancel(state);
    }

    private void closeMomentTaskLocked(Task task) {
        final AppJumpSession currentSession = mAppJumpSessions.get(task.mTaskId);
        if (currentSession != null && currentSession.dismissRequested
                && !currentSession.handoff.hasReturnedToSource()
                && currentSession.handoff.hasFlowActivity(task.getTopNonFinishingActivity())) {
            cancelAppJumpDismissLocked(currentSession);
            return;
        }
        final AppJumpSession session = mAppJumpSessions.remove(task.mTaskId);
        if (session != null) {
            session.handoff.finish();
            armPostSessionExitGraceForSessionLocked(session, null /* extraBridge */);
        }
        if (session != null && session.dismissRequested && session.wasExistingTask
                && task.hasChild()) {
            // Restore the user's existing task only after the Moment close morph has finished.
            exitMomentTaskLocked(task, false /* captureSnapshot */);
            task.moveTaskToBack(task);
            return;
        }
        removeLayoutSnapshotLocked(task.mTaskId);
        removeHandleWindowLocked(task);
        final MomentTaskSurfaceState state = mStates.get(task.mTaskId);
        if (state != null) {
            cancelTaskAnimationLocked(task, state);
            state.prepareForTaskRemoval(task.getSyncTransaction());
        }
        mService.mAtmService.removeTask(task, "close-moment");
        if (task.isAttached() && !task.hasChild()) {
            task.removeIfPossible("close-empty-moment");
        }
    }

    private int getDisplayBottomLocked(Task task) {
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null) {
            return task.getBounds().bottom;
        }
        final Rect displayBounds = new Rect();
        displayContent.getBounds(displayBounds);
        return displayBounds.bottom;
    }

    private float getCompactDismissScaleLocked(Task task, MomentTaskSurfaceState state) {
        final Rect taskBounds = task.getBounds();
        final float compactMaxDimension = Math.max(taskBounds.width(), taskBounds.height())
                * state.getCompactScale();
        final DisplayContent displayContent = task.getDisplayContent();
        final float density = displayContent != null
                ? displayContent.getDisplayMetrics().density : 1f;
        final float desiredWidth = COMPACT_DISMISS_TARGET_SIZE_DP * density
                * COMPACT_DISMISS_SCALE;
        return compactMaxDimension > 0f
                ? state.getCompactScale() * Math.min(1f, desiredWidth / compactMaxDimension)
                : state.getCompactScale();
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private void exitAllLocked() {
        final ArrayList<Task> tasks = new ArrayList<>();
        forAllMomentTasksLocked(tasks::add);
        for (int i = 0; i < tasks.size(); i++) {
            exitMomentTaskLocked(tasks.get(i));
        }
    }

    private void exitAllForUserLocked(int userId) {
        final ArrayList<Task> tasks = new ArrayList<>();
        forAllMomentTasksLocked(task -> {
            if (task.mUserId == userId) {
                tasks.add(task);
            }
        });
        for (int i = 0; i < tasks.size(); i++) {
            exitMomentTaskLocked(tasks.get(i));
        }
    }

    private void exitMomentTaskLocked(Task task) {
        exitMomentTaskLocked(task, true /* captureSnapshot */);
    }

    private void exitMomentTaskLocked(Task task, boolean captureSnapshot) {
        stopAppJumpWindowOwnershipLocked(task);
        if (!captureSnapshot) {
            removeLayoutSnapshotLocked(task.mTaskId);
        }
        final MomentTaskSurfaceState currentState = getStateLocked(task);
        if (captureSnapshot && currentState != null && currentState.hasFixedAspectRatio()
                && !mLayoutSnapshots.containsKey(task.mTaskId)) {
            captureLayoutSnapshotLocked(task, currentState.getContentCrop());
        }
        final MomentTaskSurfaceState state = mStates.remove(task.mTaskId);
        if (state != null) {
            cancelTaskAnimationLocked(task, state);
            state.reset(task.getSyncTransaction());
            state.destroy(task.getSyncTransaction());
        }
        removeHandleWindowLocked(task);
        markNoAnimationLocked(task);
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        task.setBounds(null);
        final MomentLayoutSnapshot snapshot = mLayoutSnapshots.get(task.mTaskId);
        if (snapshot != null) {
            final Rect bounds = new Rect(task.getBounds());
            bounds.offsetTo(0, 0);
            snapshot.applyBounds(task.getSyncTransaction(), bounds);
            task.forAllActivities(activity -> {
                activity.ensureActivityConfiguration(true /* ignoreVisibility */);
            });
            waitForLayoutLocked(task, true /* animationFinished */);
        }
        scheduleMomentSurfaceUpdateLocked(task);
    }

    private void removeHandleWindowLocked(Task task) {
        if (task == null) {
            return;
        }
        final MomentHandleWindow handleWindow = mHandleWindows.remove(task.mTaskId);
        if (handleWindow != null) {
            handleWindow.destroy();
        }
    }

    void onNotificationShadeExpandedLocked(DisplayContent displayContent, boolean expanded) {
        forAllMomentTasksLocked(task -> {
            if (task.getDisplayContent() != displayContent) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            state.apply(task.getSyncTransaction(), !expanded && shouldShowHandleLocked(task, state));
            if (expanded) {
                removeHandleWindowLocked(task);
            } else {
                updateHandleWindowLocked(task);
            }
            scheduleMomentSurfaceUpdateLocked(task);
        });
    }

    void onNotificationShadeRemovedLocked(DisplayContent displayContent,
            WindowState removedShade, int shadeGeneration) {
        mService.mH.post(() -> finishNotificationShadeRemoval(
                displayContent, removedShade, shadeGeneration, 120 /* remainingFrames */));
    }

    private void finishNotificationShadeRemoval(DisplayContent displayContent,
            WindowState removedShade, int shadeGeneration, int remainingFrames) {
        synchronized (mService.mGlobalLock) {
            final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
            if (displayPolicy.getNotificationShadeGeneration() != shadeGeneration
                    || !displayPolicy.isNotificationShadeExpanded()) {
                return;
            }
            if (removedShade.isOnScreen() || removedShade.isAnimating()) {
                if (remainingFrames <= 0) {
                    Slog.w(TAG, "Timed out waiting for notification shade removal on display "
                            + displayContent.getDisplayId());
                } else {
                    mService.mH.postDelayed(
                            () -> finishNotificationShadeRemoval(displayContent, removedShade,
                                    shadeGeneration, remainingFrames - 1), 16);
                    return;
                }
            }
            final WindowState currentShade = displayPolicy.getNotificationShade();
            if (currentShade != null && displayPolicy.hasNotificationShadeExpansionReport()
                    && displayPolicy.isNotificationShadeExpanded()) {
                return;
            }
            if (displayPolicy.setNotificationShadeExpanded(false)) {
                onNotificationShadeExpandedLocked(displayContent, false /* expanded */);
            }
        }
    }

    private void updateHandleWindowLocked(Task task) {
        if (!shouldAttachInteractionWindowLocked(task)) {
            removeHandleWindowLocked(task);
            return;
        }
        final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
        final Rect handleBounds = state.getHandleBounds();
        if (handleBounds.isEmpty()) {
            removeHandleWindowLocked(task);
            return;
        }
        MomentHandleWindow handleWindow = mHandleWindows.get(task.mTaskId);
        final DisplayContent displayContent = task.getDisplayContent();
        final int displayId = displayContent != null
                ? displayContent.getDisplayId() : android.view.Display.DEFAULT_DISPLAY;
        if (handleWindow != null && !handleWindow.isOnDisplay(displayId)) {
            mHandleWindows.remove(task.mTaskId);
            handleWindow.destroy();
            handleWindow = null;
        }
        if (handleWindow == null) {
            handleWindow = new MomentHandleWindow(mService, task, state.getHandleSurfaces());
            mHandleWindows.put(task.mTaskId, handleWindow);
        }
        handleWindow.showOrUpdate(handleBounds, state.isMomentCompact(),
                state.isTransformAnimating() || mLayoutSnapshots.containsKey(task.mTaskId));
    }

    private void markNoAnimationLocked(Task task) {
        task.forAllActivities(activity -> {
            activity.intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            activity.mTransitionController.setNoAnimation(activity);
        });
    }

    private boolean shouldAttachInteractionWindowLocked(Task task) {
        if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                || !task.isVisibleRequested()) {
            return false;
        }
        final MomentTaskSurfaceState state = getStateLocked(task);
        if (state != null && (state.isOpening() || state.isClosing())) {
            return false;
        }
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null
                || displayContent.getDisplayPolicy().isNotificationShadeExpanded()) {
            return false;
        }
        final ActivityRecord topActivity = task.topRunningActivity();
        if (topActivity == null || !topActivity.isVisibleRequested()) {
            return false;
        }
        return true;
    }

    private boolean shouldShowHandleLocked(Task task, MomentTaskSurfaceState state) {
        return shouldAttachInteractionWindowLocked(task) && !state.isMomentCompact()
                && !state.isClosing();
    }

    private void applyMomentStateLocked(Task task, MomentTaskSurfaceState state,
            boolean allowSurfaceHandle) {
        state.apply(task.getSyncTransaction(),
                allowSurfaceHandle && shouldShowHandleLocked(task, state));
        if (shouldAttachInteractionWindowLocked(task)) {
            updateHandleWindowLocked(task);
        } else {
            removeHandleWindowLocked(task);
        }
        scheduleMomentSurfaceUpdateLocked(task);
    }

    private void scheduleMomentSurfaceUpdateLocked(Task task) {
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent != null) {
            displayContent.setLayoutNeeded();
        }
        mService.mWindowPlacerLocked.requestTraversal();
    }

    private void dumpTasksLocked(PrintWriter pw) {
        final ArrayList<Task> tasks = new ArrayList<>();
        forAllMomentTasksLocked(tasks::add);
        if (tasks.isEmpty()) {
            pw.println("No Moment tasks");
            return;
        }
        for (int i = 0; i < tasks.size(); i++) {
            final Task task = tasks.get(i);
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            final MomentHandleWindow handleWindow = mHandleWindows.get(task.mTaskId);
            pw.println("taskId=" + task.mTaskId + " userId=" + task.mUserId
                    + " scale=" + state.getScale()
                    + " compact=" + state.isMomentCompact()
                    + " bounds=" + state.getSurfaceBounds()
                    + " handleBounds=" + state.getHandleBounds()
                    + " decorationBounds=" + state.getDecorationBounds()
                    + " handleWindowBounds="
                    + (handleWindow != null ? handleWindow.getLastWindowBounds() : "null")
                    + " topActivity=" + task.topRunningActivity());
        }
    }

    private void forAllMomentTasksLocked(java.util.function.Consumer<Task> consumer) {
        mService.mRoot.forAllTasks(task -> {
            if (task.isRootTask() && task.getWindowingMode() == WINDOWING_MODE_MOMENT) {
                consumer.accept(task);
            }
        }, true);
    }

    private float clampScale(float scale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    private static boolean isDebugEnabled() {
        return SystemProperties.getBoolean(DEBUG_PROPERTY, false);
    }
}
