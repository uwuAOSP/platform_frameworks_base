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

package com.android.internal.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class ClipboardAccessPolicyTest {
    @Test
    public void preinstalledChromeAsks() {
        for (String pkg : new String[] {"com.android.chrome", "com.chrome.beta",
                "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome"}) {
            assertTrue(ClipboardAccessPolicy.shouldAskByDefault(true, true, pkg));
        }
    }

    @Test
    public void otherPreinstalledAppsAllow() {
        assertFalse(ClipboardAccessPolicy.shouldAskByDefault(true, true, "com.android.settings"));
        assertFalse(ClipboardAccessPolicy.shouldAskByDefault(true, true, "org.example.notes"));
    }

    @Test
    public void thirdPartyAppsStillAsk() {
        assertTrue(ClipboardAccessPolicy.shouldAskByDefault(true, false, "org.example.notes"));
        assertTrue(ClipboardAccessPolicy.shouldAskByDefault(true, false, "com.android.chrome"));
    }

    @Test
    public void globalSwitchOffAllowsUnconfiguredApps() {
        assertFalse(ClipboardAccessPolicy.shouldAskByDefault(false, true, "com.android.chrome"));
        assertFalse(ClipboardAccessPolicy.shouldAskByDefault(false, true, "com.android.settings"));
        assertFalse(ClipboardAccessPolicy.shouldAskByDefault(false, false, "org.example.notes"));
    }

    @Test
    public void chromeSubstringDoesNotMatchOtherSystemApps() {
        assertFalse(ClipboardAccessPolicy.shouldAskByDefault(true, true, "org.example.chrome"));
        assertFalse(ClipboardAccessPolicy.shouldAskByDefault(true, true, "com.android.chrome.fake"));
    }
}
