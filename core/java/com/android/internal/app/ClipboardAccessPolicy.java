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

/**
 * Default policy shared by the clipboard service and its settings UI.
 *
 * @hide
 */
public final class ClipboardAccessPolicy {
    private ClipboardAccessPolicy() {}

    /** Preinstalled apps are allowed by default, except Chrome. Explicit rules take priority. */
    public static boolean shouldAskByDefault(boolean promptsEnabled, boolean systemApp,
            String packageName) {
        if (!promptsEnabled) {
            return false;
        }
        if (!systemApp) {
            return true;
        }
        return "com.android.chrome".equals(packageName)
                || "com.chrome.beta".equals(packageName)
                || "com.chrome.dev".equals(packageName)
                || "com.chrome.canary".equals(packageName)
                || "org.chromium.chrome".equals(packageName);
    }
}
