/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
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

package com.android.server.clipboard;

import static org.junit.Assert.*;

import org.junit.Test;

public class ClipboardAccessStateTest {
    private static final String READ = "0:com.android.chrome:0";
    private static final String WRITE = "0:com.android.chrome:1";
    private final ClipboardAccessState state = new ClipboardAccessState();

    @Test
    public void temporaryAllowExpiresAtThirtySeconds() {
        state.resolvePrompt(READ, true, false, 100);
        assertEquals(ClipboardAccessState.Decision.ALLOW, state.getDecision(READ, 30_099));
        assertEquals(ClipboardAccessState.Decision.NONE, state.getDecision(READ, 30_100));
    }

    @Test
    public void denialSuppressesRetriesForThirtySecondsAfterResponse() {
        assertTrue(state.beginPrompt(READ, 100));
        state.resolvePrompt(READ, false, false, 5_000);
        assertEquals(ClipboardAccessState.Decision.DENY, state.getDecision(READ, 34_999));
        assertFalse(state.beginPrompt(READ, 34_999));
        assertEquals(ClipboardAccessState.Decision.NONE, state.getDecision(READ, 35_000));
        assertTrue(state.beginPrompt(READ, 35_000));
    }

    @Test
    public void readAndWriteHaveIndependentChoicesAndPrompts() {
        assertTrue(state.beginPrompt(WRITE, 100));
        state.resolvePrompt(WRITE, true, false, 101);
        assertEquals(ClipboardAccessState.Decision.NONE, state.getDecision(READ, 102));
        assertTrue(state.beginPrompt(READ, 102));
        state.resolvePrompt(READ, false, false, 103);
        assertEquals(ClipboardAccessState.Decision.ALLOW, state.getDecision(WRITE, 104));
        assertEquals(ClipboardAccessState.Decision.DENY, state.getDecision(READ, 104));
    }

    @Test
    public void openPromptSuppressesDuplicatesAndRecoversFromProcessDeath() {
        assertTrue(state.beginPrompt(READ, 100));
        assertFalse(state.beginPrompt(READ, 30_100));
        assertFalse(state.beginPrompt(READ, 120_099));
        assertTrue(state.beginPrompt(READ, 120_100));
    }

    @Test
    public void cancellationRetainsCooldown() {
        assertTrue(state.beginPrompt(READ, 100));
        state.cancelLaunch(READ);
        assertFalse(state.beginPrompt(READ, 29_999));
        assertTrue(state.beginPrompt(READ, 30_100));
    }

    @Test
    public void savingReadRuleDoesNotInvalidateWriteChoice() {
        state.resolvePrompt(WRITE, false, false, 100);
        state.resolvePrompt(READ, true, false, 100);
        state.invalidateDecisions(0, 0);
        assertEquals(ClipboardAccessState.Decision.NONE, state.getDecision(READ, 101));
        assertEquals(ClipboardAccessState.Decision.DENY, state.getDecision(WRITE, 101));
    }

    @Test
    public void savingPermanentRuleRemovesOnlyItsTemporaryChoice() {
        state.resolvePrompt(WRITE, false, false, 100);
        state.resolvePrompt(READ, true, false, 100);
        state.resolvePrompt(READ, true, true, 101);
        assertEquals(ClipboardAccessState.Decision.NONE, state.getDecision(READ, 102));
        assertEquals(ClipboardAccessState.Decision.DENY, state.getDecision(WRITE, 102));
    }

    @Test
    public void clearUserLeavesOtherUsersAndPackagesUntouched() {
        state.resolvePrompt(READ, false, false, 100);
        state.resolvePrompt("10:com.android.chrome:0", true, false, 100);
        state.resolvePrompt("0:org.example.notes:0", true, false, 100);
        assertTrue(state.beginPrompt(WRITE, 100));
        state.clearUser(0);
        assertEquals(ClipboardAccessState.Decision.NONE, state.getDecision(READ, 101));
        assertEquals(ClipboardAccessState.Decision.NONE,
                state.getDecision("0:org.example.notes:0", 101));
        assertEquals(ClipboardAccessState.Decision.ALLOW,
                state.getDecision("10:com.android.chrome:0", 101));
        assertTrue(state.beginPrompt(WRITE, 101));
    }

    @Test
    public void globalRuleChangesPreserveOpenPromptDeduplication() {
        assertTrue(state.beginPrompt(READ, 100));
        state.invalidateDecisions(-1, -1);
        assertFalse(state.beginPrompt(READ, 101));
    }
}
