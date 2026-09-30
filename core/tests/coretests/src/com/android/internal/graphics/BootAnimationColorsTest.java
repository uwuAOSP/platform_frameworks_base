/*
 * Copyright (C) 2026 UwUniverse
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
public class BootAnimationColorsTest {
    private static final int[] COLORS = {0xffadc6ff, 0xffd0bcff, 0xff80d5e8, 0xff839dff};

    @Test
    public void writesAllFourSignedDecimalColors() {
        Map<String, String> properties = new HashMap<>();
        BootAnimationColors.update(COLORS, properties::get, properties::put);
        assertThat(properties).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(Integer.parseInt(properties.get("persist.bootanim.color" + (i + 1))))
                    .isEqualTo(COLORS[i]);
        }
    }

    @Test
    public void samePaletteAfterProcessRestartDoesNotWriteAgain() {
        Map<String, String> properties = new HashMap<>();
        BootAnimationColors.update(COLORS, properties::get, properties::put);
        List<String> writes = new ArrayList<>();
        BootAnimationColors.update(COLORS, properties::get, (key, value) -> writes.add(key));
        assertThat(writes).isEmpty();
    }

    @Test
    public void onlyChangedOrMissingColorsAreWritten() {
        Map<String, String> properties = new HashMap<>();
        BootAnimationColors.update(COLORS, properties::get, properties::put);
        properties.remove("persist.bootanim.color2");
        properties.put("persist.bootanim.color4", "0");
        List<String> writes = new ArrayList<>();
        BootAnimationColors.update(COLORS, properties::get, (key, value) -> writes.add(key));
        assertThat(writes).containsExactly("persist.bootanim.color2", "persist.bootanim.color4");
    }

    @Test
    public void malformedPaletteIsRejectedBeforeWriting() {
        Map<String, String> properties = new HashMap<>();
        assertThrows(IllegalArgumentException.class, () -> BootAnimationColors.update(
                new int[]{0, 1, 2}, properties::get, properties::put));
        assertThat(properties).isEmpty();
    }

    @Test
    public void unavailablePropertyDoesNotBreakOtherColorsOrThemeApplication() {
        Map<String, String> properties = new HashMap<>();
        BootAnimationColors.update(COLORS, properties::get, (key, value) -> {
            if (key.endsWith("2")) throw new IllegalStateException("Property unavailable");
            properties.put(key, value);
        });
        assertThat(properties).hasSize(3);
        assertThat(properties).containsKey("persist.bootanim.color4");
    }
}
