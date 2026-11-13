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

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;

import com.android.internal.dynamicanimation.animation.SpringAnimation;

final class MomentHandleSurfaces {

    private static final float PRESSED_WIDTH_SCALE = 76f / 108f;

    private final Task mTask;
    private final float[] mBottomColor = new float[3];
    private final float[] mTopColor = new float[3];
    private final Rect mBottomBounds = new Rect();
    private final RectF mTmpBounds = new RectF();

    private SurfaceControl mBottomSurface;
    private SurfaceControl mTopSurface;
    private SpringAnimation mPressAnimator;
    private float mPressProgress;
    private boolean mDestroyed;

    MomentHandleSurfaces(Task task) {
        mTask = task;
    }

    synchronized void showOrUpdateBottom(SurfaceControl.Transaction t, Rect momentBounds) {
        if (mTask.mSurfaceControl == null || momentBounds.isEmpty()) {
            hideBottom(t);
            return;
        }
        final SurfaceControl parent = mTask.getParentSurfaceControl();
        if (parent == null) {
            hideBottom(t);
            return;
        }
        ensureBottomSurface(parent);
        if (mBottomSurface == null) {
            return;
        }

        mBottomBounds.set(momentBounds);
        applyBottomGeometry(t);
        t.reparent(mBottomSurface, parent)
                .setColor(mBottomSurface, toColorArray(resolveBottomColor(), mBottomColor))
                .setAlpha(mBottomSurface, 1f)
                .setRelativeLayer(mBottomSurface, mTask.mSurfaceControl, 1)
                .show(mBottomSurface);
    }

    void showOrUpdateTop(Rect momentBounds, float shapeProgress) {
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            synchronized (this) {
                if (mDestroyed || mTask.mSurfaceControl == null || momentBounds.isEmpty()) {
                    hideTopLocked(t);
                } else {
                    final SurfaceControl parent = mTask.getParentSurfaceControl();
                    if (parent == null) {
                        hideTopLocked(t);
                    } else {
                        ensureTopSurface(parent);
                        if (mTopSurface != null) {
                            final float density = getDensity();
                            MomentGeometry.getTopHandleBounds(momentBounds, density,
                                    MomentGeometry.getDisplayedCornerRadius(
                                            mTask, momentBounds, density),
                                    shapeProgress, false /* includeCollapsedTouchTarget */,
                                    mTmpBounds);
                            applySurfaceBounds(t, mTopSurface, mTmpBounds);
                            t.reparent(mTopSurface, parent)
                                    .setColor(mTopSurface,
                                            toColorArray(Color.WHITE, mTopColor))
                                    .setAlpha(mTopSurface, 1f)
                                    .setRelativeLayer(mTopSurface, mTask.mSurfaceControl, 2)
                                    .show(mTopSurface);
                        }
                    }
                }
            }
            t.apply();
        }
    }

    void animateBottomPress(boolean isTouchDown) {
        mTask.mWmService.mAnimationHandler.post(() -> startPressAnimation(isTouchDown));
    }

    synchronized void hideBottom(SurfaceControl.Transaction t) {
        mBottomBounds.setEmpty();
        mPressProgress = 0f;
        cancelPressAnimationLocked();
        if (mBottomSurface != null) {
            t.hide(mBottomSurface);
        }
    }

    void hideTop() {
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            synchronized (this) {
                hideTopLocked(t);
            }
            t.apply();
        }
    }

    synchronized void destroy(SurfaceControl.Transaction t) {
        mDestroyed = true;
        mBottomBounds.setEmpty();
        mPressProgress = 0f;
        cancelPressAnimationLocked();
        if (mBottomSurface != null) {
            t.remove(mBottomSurface);
            mBottomSurface.release();
            mBottomSurface = null;
        }
        if (mTopSurface != null) {
            t.remove(mTopSurface);
            mTopSurface.release();
            mTopSurface = null;
        }
    }

    private void startPressAnimation(boolean isTouchDown) {
        synchronized (this) {
            if (mDestroyed || mBottomSurface == null || mBottomBounds.isEmpty()) {
                return;
            }
            final float targetProgress = isTouchDown ? 1f : 0f;
            if (mPressAnimator != null && mPressAnimator.isRunning()) {
                mPressAnimator.animateToFinalPosition(targetProgress);
                return;
            }
            if (mPressProgress == targetProgress) {
                return;
            }
            final SpringAnimation animator = MomentMotion.spring(mPressProgress, targetProgress,
                    0f, MomentMotion.FAST_SPATIAL, 0.001f);
            mPressAnimator = animator;
            animator.addUpdateListener((animation, value, velocity) ->
                    applyPressAnimationFrame(animator, value));
            animator.addEndListener((animation, cancelled, value, velocity) -> {
                synchronized (MomentHandleSurfaces.this) {
                    if (mPressAnimator == animation) {
                        mPressAnimator = null;
                    }
                }
            });
            animator.start();
        }
    }

    private synchronized void applyPressAnimationFrame(SpringAnimation animator, float value) {
        if (mPressAnimator != animator || mDestroyed || mBottomSurface == null
                || !mBottomSurface.isValid() || mBottomBounds.isEmpty()) {
            return;
        }
        mPressProgress = value;
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            applyBottomGeometry(t);
            t.apply();
        }
    }

    private void applyBottomGeometry(SurfaceControl.Transaction t) {
        final float widthScale = 1f - (1f - PRESSED_WIDTH_SCALE) * mPressProgress;
        MomentGeometry.getBottomHandleBounds(mBottomBounds, getDensity(), widthScale, mTmpBounds);
        applySurfaceBounds(t, mBottomSurface, mTmpBounds);
    }

    private void applySurfaceBounds(SurfaceControl.Transaction t, SurfaceControl surface,
            RectF bounds) {
        final int width = Math.max(1, Math.round(bounds.width()));
        final int height = Math.max(1, Math.round(bounds.height()));
        t.setWindowCrop(surface, width, height)
                .setPosition(surface, bounds.centerX() - width / 2f,
                        bounds.centerY() - height / 2f)
                .setCornerRadius(surface, height / 2f);
    }

    private void hideTopLocked(SurfaceControl.Transaction t) {
        if (mTopSurface != null) {
            t.hide(mTopSurface);
        }
    }

    private void cancelPressAnimationLocked() {
        final SpringAnimation animator = mPressAnimator;
        mPressAnimator = null;
        if (animator != null) {
            mTask.mWmService.mAnimationHandler.post(animator::cancel);
        }
    }

    private void ensureBottomSurface(SurfaceControl parent) {
        if (mBottomSurface != null || mDestroyed) {
            return;
        }
        try {
            mBottomSurface = makeColorLayer(parent, "MomentBottomHandle#" + mTask.mTaskId);
        } catch (OutOfResourcesException e) {
            mBottomSurface = null;
        }
    }

    private void ensureTopSurface(SurfaceControl parent) {
        if (mTopSurface != null || mDestroyed) {
            return;
        }
        try {
            mTopSurface = makeColorLayer(parent, "MomentTopHandle#" + mTask.mTaskId);
        } catch (OutOfResourcesException e) {
            mTopSurface = null;
        }
    }

    private SurfaceControl makeColorLayer(SurfaceControl parent, String title)
            throws OutOfResourcesException {
        return mTask.mWmService.makeSurfaceBuilder()
                .setName(title)
                .setParent(parent)
                .setColorLayer()
                .setCallsite("MomentHandleSurfaces")
                .build();
    }

    private float[] toColorArray(int color, float[] outColor) {
        outColor[0] = Color.red(color) / 255f;
        outColor[1] = Color.green(color) / 255f;
        outColor[2] = Color.blue(color) / 255f;
        return outColor;
    }

    private int resolveBottomColor() {
        return mTask.mWmService.mContext.getColor(android.R.color.system_accent1_100);
    }

    private float getDensity() {
        final DisplayContent displayContent = mTask.getDisplayContent();
        return displayContent != null ? displayContent.getDisplayMetrics().density : 1f;
    }
}
