/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.custom;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyboxPolicyTest {
    private static final String[] PACKAGES = {
        "com.google.android.gms", "com.example.shared"
    };

    @Test
    public void shouldUseKeybox_requiresExplicitOptIn() {
        assertThat(KeyboxPolicy.shouldUseKeybox(false, false, PACKAGES, null)).isFalse();
        assertThat(KeyboxPolicy.shouldUseKeybox(true, false, PACKAGES, null)).isTrue();
        assertThat(KeyboxPolicy.shouldUseKeybox(false, true, PACKAGES, null)).isTrue();
        assertThat(KeyboxPolicy.shouldUseKeybox(true, true, PACKAGES, null)).isTrue();
    }

    @Test
    public void shouldUseKeybox_exclusionWinsAfterOptIn() {
        assertThat(
                        KeyboxPolicy.shouldUseKeybox(
                                true, false, PACKAGES, "com.other:com.example.shared"))
                .isFalse();
        assertThat(
                        KeyboxPolicy.shouldUseKeybox(
                                false, true, PACKAGES, "com.google.android.gms"))
                .isFalse();
        assertThat(KeyboxPolicy.shouldUseKeybox(true, false, PACKAGES, "com.other")).isTrue();
    }

    @Test
    public void shouldUseKeybox_unknownPackagesStayEnabledAfterOptIn() {
        assertThat(KeyboxPolicy.shouldUseKeybox(true, false, null, "com.google.android.gms"))
                .isTrue();
        assertThat(
                        KeyboxPolicy.shouldUseKeybox(
                                false, true, new String[0], "com.google.android.gms"))
                .isTrue();
    }
}
