/*
 * Copyright (C) 2026 UwUniverse
 * SPDX-FileCopyrightText: UwUniverse
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

package com.android.internal.graphics;

import android.os.SystemProperties;
import android.util.Log;

import java.util.function.BiConsumer;
import java.util.function.Function;

/** Persists the applied Material palette for native bootanimation, before user unlock. @hide */
public final class BootAnimationColors {
    private static final String TAG = "BootAnimationColors";

    private BootAnimationColors() {}

    public static void update(int primary, int secondary, int tertiary, int primaryVariant) {
        update(new int[]{primary, secondary, tertiary, primaryVariant},
                SystemProperties::get, SystemProperties::set);
    }

    // Allow framework/SystemUI callers to supply their testable property accessors.
    // Compare against persisted values, not a process-local cache.
    public static synchronized void update(int[] colors, Function<String, String> reader,
            BiConsumer<String, String> writer) {
        if (colors.length != 4) {
            throw new IllegalArgumentException("Boot animation requires four palette colors");
        }
        for (int i = 0; i < colors.length; i++) {
            String property = "persist.bootanim.color" + (i + 1);
            // The native parser and property schema expect a signed decimal int, not hex.
            String value = Integer.toString(colors[i]);
            try {
                if (!value.equals(reader.apply(property))) {
                    writer.accept(property, value);
                }
            } catch (RuntimeException e) {
                // Theme application must still succeed if property persistence is unavailable.
                Log.w(TAG, "Unable to persist " + property, e);
            }
        }
    }
}
