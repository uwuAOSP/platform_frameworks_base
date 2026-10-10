/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.navigationbar.gestural;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import android.graphics.Canvas;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NavigationHandleTest extends SysuiTestCase {
    @Test
    public void onlyGestureNavigationHonorsHideSetting() {
        for (int mode = 0; mode <= 2; mode++) {
            assertFalse(NavigationHandle.shouldHideHandle(mode, 0));
            assertEquals(mode == 2, NavigationHandle.shouldHideHandle(mode, 1));
            assertFalse(NavigationHandle.shouldHideHandle(mode, -1));
        }
    }

    @Test
    public void hiddenHandleKeepsGeometryAndRestoresDrawing() {
        boolean[] hidden = {true};
        NavigationHandle handle = new NavigationHandle(mContext) {
            @Override
            protected boolean isHandleHidden() {
                return hidden[0];
            }
        };
        handle.layout(0, 0, 108, 40);
        Canvas canvas = mock(Canvas.class);
        handle.onDraw(canvas);
        verifyNoInteractions(canvas);
        assertEquals(View.VISIBLE, handle.getVisibility());
        assertEquals(108, handle.getWidth());
        assertEquals(40, handle.getHeight());
        hidden[0] = false;
        handle.onDraw(canvas);
        verify(canvas).drawRoundRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), any());
    }

    @Test
    public void hiddenQuickSwitchHandleDoesNotDraw() {
        QuickswitchOrientedNavHandle handle = new QuickswitchOrientedNavHandle(mContext) {
            @Override
            protected boolean isHandleHidden() {
                return true;
            }
        };
        Canvas canvas = mock(Canvas.class);
        handle.onDraw(canvas);
        verifyNoInteractions(canvas);
    }
}
