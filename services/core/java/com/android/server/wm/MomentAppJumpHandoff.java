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

import java.util.HashSet;
import java.util.Set;

/** Tracks asynchronous activity handoffs without treating a trampoline's finish as completion. */
final class MomentAppJumpHandoff<T> {
    static final long QUIET_PERIOD_MS = 750;

    private final T mEntry;
    private final Set<T> mOriginalActivities;
    private boolean mEntryFinished;
    private boolean mReturnedToSource;
    private long mSettleDeadline;
    private Runnable mPendingCanceledResult;

    MomentAppJumpHandoff(T entry, Set<T> originalActivities) {
        mEntry = entry;
        mOriginalActivities = new HashSet<>(originalActivities);
    }

    void onActivityLaunched(T activity, long now) {
        // A reused activity receiving a new share Intent now belongs to this flow too.
        mOriginalActivities.remove(activity);
        mSettleDeadline = now + QUIET_PERIOD_MS;
    }

    void onTaskHandoff(Set<T> originalActivities) {
        mOriginalActivities.addAll(originalActivities);
    }

    void onActivityFinishing(T activity, long now) {
        if (mEntry.equals(activity)) {
            mEntryFinished = true;
        }
        mSettleDeadline = now + QUIET_PERIOD_MS;
    }

    boolean isEntryFinished() {
        return mEntryFinished;
    }

    boolean hasReturnedToSource() {
        return mReturnedToSource;
    }

    boolean hasFlowActivity(T top) {
        return top != null && !mOriginalActivities.contains(top);
    }

    long remainingDelay(long now) {
        return Math.max(0, mSettleDeadline - now);
    }

    boolean shouldDismiss(T top, long now) {
        return mEntryFinished && !mReturnedToSource && !hasFlowActivity(top)
                && remainingDelay(now) == 0;
    }

    boolean canHandoffToTask(T top, long now) {
        return mEntryFinished && !mReturnedToSource && !hasFlowActivity(top)
                && remainingDelay(now) > 0;
    }

    boolean deferCanceledResult(T activity, Runnable result) {
        if (!mEntry.equals(activity) || mReturnedToSource || mPendingCanceledResult != null) {
            return false;
        }
        mPendingCanceledResult = result;
        return true;
    }

    boolean hasPendingCanceledResult() {
        return mPendingCanceledResult != null;
    }

    void onReturnToSource() {
        mReturnedToSource = true;
        // The explicit SDK callback now owns completion. Do not race it with the trampoline's
        // default null RESULT_CANCELED, which does not describe the subsequent share UI's result.
        mPendingCanceledResult = null;
    }

    void finish() {
        final Runnable result = mPendingCanceledResult;
        mPendingCanceledResult = null;
        if (result != null) {
            result.run();
        }
    }
}
