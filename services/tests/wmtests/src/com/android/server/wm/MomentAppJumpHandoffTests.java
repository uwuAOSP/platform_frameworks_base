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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Also runs as a host JUnit test: handoff state has no Android runtime dependencies. */
@RunWith(JUnit4.class)
public class MomentAppJumpHandoffTests {
    @Test
    public void asynchronousSharePageSurvivesEntryFinishAndItsProvisionalCancel() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        final AtomicInteger results = new AtomicInteger();
        assertTrue(flow.deferCanceledResult("entry", results::incrementAndGet));
        flow.onActivityFinishing("entry", 1000);
        // The captured device trace starts the real QQ share page 72 ms after entry finish.
        assertFalse(flow.shouldDismiss(null, 1072));
        assertEquals(0, results.get());
        flow.onActivityLaunched("share", 1072);
        assertFalse(flow.shouldDismiss("share", 5000));
        assertTrue(flow.hasPendingCanceledResult());
        assertEquals(0, results.get());
    }

    @Test
    public void actualCancellationAfterSharePageFinishesIsDeliveredOnce() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        final AtomicInteger results = new AtomicInteger();
        flow.deferCanceledResult("entry", results::incrementAndGet);
        flow.onActivityFinishing("entry", 1000);
        flow.onActivityLaunched("share", 1072);
        flow.onActivityFinishing("share", 5000);
        assertFalse(flow.shouldDismiss(null, 5000));
        assertTrue(flow.shouldDismiss(null, 5000 + MomentAppJumpHandoff.QUIET_PERIOD_MS));
        flow.finish();
        flow.finish();
        assertEquals(1, results.get());
        assertFalse(flow.hasPendingCanceledResult());
    }

    @Test
    public void explicitSdkCallbackWinsOverTrampolineCancellation() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        final AtomicInteger results = new AtomicInteger();
        flow.deferCanceledResult("entry", results::incrementAndGet);
        flow.onActivityFinishing("entry", 1000);
        flow.onActivityLaunched("share", 1072);
        flow.onReturnToSource();
        flow.finish();
        assertEquals(0, results.get());
        assertTrue(flow.hasReturnedToSource());
        assertFalse(flow.deferCanceledResult("entry", results::incrementAndGet));
    }

    @Test
    public void ordinaryEntryCancelCompletesAfterQuietPeriod() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        flow.onActivityFinishing("entry", 1000);
        assertFalse(flow.shouldDismiss(null, 1000));
        assertTrue(flow.shouldDismiss(null, 1000 + MomentAppJumpHandoff.QUIET_PERIOD_MS));
    }

    @Test
    public void returningToOldChatIsIdleButNewSharePageIsNot() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry",
                Set.of("main", "chat"));
        flow.onActivityFinishing("entry", 1000);
        flow.onActivityLaunched("share", 1072);
        assertFalse(flow.shouldDismiss("share", 5000));
        assertTrue(flow.shouldDismiss("chat", 5000));
    }

    @Test
    public void eachAsynchronousHandoffRestartsTheQuietPeriod() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        flow.onActivityFinishing("entry", 1000);
        flow.onActivityLaunched("intermediate", 1700);
        flow.onActivityFinishing("intermediate", 2000);
        assertFalse(flow.shouldDismiss(null, 2001));
        flow.onActivityLaunched("final", 2072);
        assertFalse(flow.shouldDismiss(null, 2000 + MomentAppJumpHandoff.QUIET_PERIOD_MS));
        assertFalse(flow.shouldDismiss("final", 5000));
    }

    @Test
    public void existingActivityReceivingShareIntentBecomesPartOfTheFlow() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry",
                Set.of("main", "chat"));
        flow.onActivityFinishing("entry", 1000);
        flow.onActivityLaunched("chat", 1072);
        assertFalse(flow.shouldDismiss("chat", 5000));
        flow.onActivityFinishing("chat", 5000);
        assertTrue(flow.shouldDismiss("main", 5000 + MomentAppJumpHandoff.QUIET_PERIOD_MS));
    }

    @Test
    public void finishingAnInternalPageDoesNotCompleteAnActiveEntry() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        flow.onActivityFinishing("internal", 1000);
        assertFalse(flow.isEntryFinished());
        assertFalse(flow.shouldDismiss(null, 5000));
    }

    @Test
    public void internalPageCannotReplaceTheEntryResult() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        final AtomicInteger results = new AtomicInteger();
        assertFalse(flow.deferCanceledResult("internal", results::incrementAndGet));
        assertTrue(flow.deferCanceledResult("entry", results::incrementAndGet));
        assertFalse(flow.deferCanceledResult("entry", () -> results.addAndGet(100)));
        flow.finish();
        assertEquals(1, results.get());
    }

    @Test
    public void contextTaskHandoffRequiresARecentGapWithoutAnActiveFlowPage() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        assertFalse(flow.canHandoffToTask(null, 1000));
        flow.onActivityFinishing("entry", 1000);
        assertTrue(flow.canHandoffToTask(null, 1035));
        flow.onActivityLaunched("share", 1072);
        assertFalse(flow.canHandoffToTask("share", 1100));
        flow.onActivityFinishing("share", 5000);
        assertTrue(flow.canHandoffToTask(null, 5035));
        assertFalse(flow.canHandoffToTask(null, 5000 + MomentAppJumpHandoff.QUIET_PERIOD_MS));
        flow.onReturnToSource();
        assertFalse(flow.canHandoffToTask(null, 5035));
    }

    @Test
    public void successDialogTaskKeepsOriginalResultAndUsesTheRealSdkCallback() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        final AtomicInteger results = new AtomicInteger();
        flow.deferCanceledResult("entry", results::incrementAndGet);
        flow.onActivityFinishing("entry", 1000);
        flow.onActivityFinishing("share", 5000);
        flow.onTaskHandoff(Set.of("qqMain", "oldChat"));
        flow.onActivityLaunched("qqMain", 5035);
        assertFalse(flow.shouldDismiss("qqMain", 9000));
        assertTrue(flow.shouldDismiss("oldChat", 9000));
        assertTrue(flow.hasPendingCanceledResult());
        assertEquals(0, results.get());
        flow.onReturnToSource();
        flow.finish();
        assertEquals(0, results.get());
    }

    @Test
    public void cancellationAfterTaskHandoffStillCompletesTheOriginalRequestOnce() {
        final MomentAppJumpHandoff<String> flow = new MomentAppJumpHandoff<>("entry", Set.of());
        final AtomicInteger results = new AtomicInteger();
        flow.deferCanceledResult("entry", results::incrementAndGet);
        flow.onActivityFinishing("entry", 1000);
        flow.onTaskHandoff(Set.of("qqMain"));
        flow.onActivityLaunched("qqMain", 1035);
        flow.onActivityFinishing("qqMain", 2000);
        assertTrue(flow.shouldDismiss(null, 2000 + MomentAppJumpHandoff.QUIET_PERIOD_MS));
        flow.finish();
        flow.finish();
        assertEquals(1, results.get());
    }
}
