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

package org.uwuaosp.systemui.lyric;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Drawable.ConstantState;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.NotificationListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays lyrics for the currently playing MediaSession. */
public abstract class LyricViewController implements DarkIconDispatcher.DarkReceiver {
    public static final int LYRIC_POSITION_OVERLAY = 0;
    public static final int LYRIC_POSITION_CLOCK_RIGHT = 1;

    private static final int HIDE_LYRIC_DELAY = 1200;
    private static final long POSITION_UPDATE_INTERVAL_MS = 250;

    private final Context mContext;
    private final LyricViewHolder mOverlayLyricViewHolder;
    private final LyricViewHolder mInlineLyricViewHolder;
    private final View mTintReferenceView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mLyricExecutor = Executors.newSingleThreadExecutor();
    private final MediaSessionManager mMediaSessionManager;
    private final ComponentName mNotificationListenerComponent;
    private final ContrastColorUtil mNotificationColorUtil;

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsChangedListener =
            this::onActiveSessionsChanged;
    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateCurrentSession();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateCurrentSession();
        }
    };
    private final Runnable mPositionUpdateRunnable = () -> {
        updateDisplayedLyric();
        if (mEnabled && mCurrentMediaController != null) {
            mHandler.postDelayed(mPositionUpdateRunnable, POSITION_UPDATE_INTERVAL_MS);
        }
    };
    private final Runnable mRestoreLyricRunnable = () -> {
        mTemporarilyHidden = false;
        showLyricView(true);
    };
    private final ContentObserver mLyricSourcesObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (mEnabled) {
                mCurrentTrackKey = null;
                updateCurrentSession();
            }
        }
    };

    private MediaController mCurrentMediaController;
    private LyricSource.Lyrics mCurrentLyrics;
    private String mCurrentTrackKey;
    private boolean mEnabled;
    private boolean mStarted;
    private boolean mShowOnClockRight;
    private boolean mShowTranslation;
    private boolean mHideIconOnClockRight;
    private boolean mTemporarilyHidden;

    private int mOverlayTintColor = DarkIconDispatcher.DEFAULT_ICON_TINT;
    private int mInlineTintColor = DarkIconDispatcher.DEFAULT_ICON_TINT;
    private CharSequence mCurrentLyricText;
    private CharSequence mCurrentTranslatedText;

    public LyricViewController(Context context, View statusBar, View tintReferenceView) {
        mContext = context;
        mTintReferenceView = tintReferenceView;
        mOverlayLyricViewHolder = createLyricViewHolder(
                statusBar, R.id.lyric_container, R.id.lyric_icon, R.id.lyric_text,
                R.id.lyric_translation, true);
        mInlineLyricViewHolder = createLyricViewHolder(
                statusBar, R.id.lyric_inline_container, R.id.lyric_inline_icon,
                R.id.lyric_inline_text, R.id.lyric_inline_translation, false);

        mNotificationColorUtil = ContrastColorUtil.getInstance(mContext);
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mNotificationListenerComponent = new ComponentName(mContext, NotificationListener.class);

        Animation animationIn = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.push_up_in);
        Animation animationOut = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.push_up_out);
        setUpAnimations(mOverlayLyricViewHolder, animationIn, animationOut);
        if (mInlineLyricViewHolder != null) {
            setUpAnimations(mInlineLyricViewHolder, animationIn, animationOut);
        }

        View.OnTouchListener touchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mOverlayLyricViewHolder.mLyricContainer.removeCallbacks(mRestoreLyricRunnable);
                mTemporarilyHidden = true;
                hideLyricView(true);
                mOverlayLyricViewHolder.mLyricContainer.postDelayed(
                        mRestoreLyricRunnable, HIDE_LYRIC_DELAY);
            }
            return false;
        };
        mOverlayLyricViewHolder.mLyricContainer.setOnTouchListener(touchListener);
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mLyricContainer.setOnTouchListener(touchListener);
        }

        hideInactiveLyricViewsImmediately();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        mMediaSessionManager.addOnActiveSessionsChangedListener(
                mSessionsChangedListener, mNotificationListenerComponent, mHandler);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.STATUS_BAR_LYRIC_SOURCES), false,
                mLyricSourcesObserver);
    }

    public void destroy() {
        mOverlayLyricViewHolder.mLyricContainer.removeCallbacks(mRestoreLyricRunnable);
        mHandler.removeCallbacks(mPositionUpdateRunnable);
        if (mMediaSessionManager != null) {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(mSessionsChangedListener);
        }
        mContext.getContentResolver().unregisterContentObserver(mLyricSourcesObserver);
        detachCurrentController();
        mLyricExecutor.shutdownNow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    public void setEnabled(boolean enabled) {
        boolean wasEnabled = mEnabled;
        mEnabled = enabled;
        if (!mEnabled) {
            stopLyric();
        } else if (!wasEnabled) {
            refreshActiveSessions();
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setLyricPosition(int position) {
        boolean showOnClockRight =
                position == LYRIC_POSITION_CLOCK_RIGHT && mInlineLyricViewHolder != null;
        if (mShowOnClockRight == showOnClockRight) {
            return;
        }

        LyricViewHolder previousHolder = getActiveLyricViewHolder();
        mShowOnClockRight = showOnClockRight;
        syncHolderContent(previousHolder, getActiveLyricViewHolder());
        updateIconVisibility();
        hideInactiveLyricViewsImmediately();
        postApplyTextTint();
        if (mStarted) {
            onLyricPositionChanged();
        }
    }

    public void setHideIconOnClockRight(boolean hideIconOnClockRight) {
        if (mHideIconOnClockRight == hideIconOnClockRight) {
            return;
        }
        mHideIconOnClockRight = hideIconOnClockRight;
        updateIconVisibility();
        postApplyTextTint();
    }

    public void setShowTranslation(boolean showTranslation) {
        if (mShowTranslation == showTranslation) {
            return;
        }
        mShowTranslation = showTranslation;
        CharSequence translatedText = getVisibleTranslatedText();
        setSubtitle(mOverlayLyricViewHolder, translatedText);
        if (mInlineLyricViewHolder != null) {
            setSubtitle(mInlineLyricViewHolder, translatedText);
        }
    }

    protected void onLyricPositionChanged() {
    }

    protected final boolean isClockRightMode() {
        return mShowOnClockRight && mInlineLyricViewHolder != null;
    }

    protected final boolean shouldShowLyricNow() {
        return mStarted && !mTemporarilyHidden;
    }

    private void onActiveSessionsChanged(List<MediaController> controllers) {
        selectActiveSession(controllers);
    }

    private void refreshActiveSessions() {
        if (!mEnabled || mMediaSessionManager == null) {
            return;
        }
        try {
            selectActiveSession(mMediaSessionManager.getActiveSessions(mNotificationListenerComponent));
        } catch (SecurityException e) {
            stopLyric();
        }
    }

    private void selectActiveSession(List<MediaController> controllers) {
        if (!mEnabled) {
            return;
        }
        MediaController activeController = null;
        for (MediaController controller : controllers) {
            if (hasPlayableState(controller)) {
                activeController = controller;
                break;
            }
        }
        if (activeController == mCurrentMediaController) {
            updateCurrentSession();
            return;
        }

        detachCurrentController();
        mCurrentMediaController = activeController;
        mCurrentLyrics = null;
        mCurrentTrackKey = null;
        if (mCurrentMediaController == null) {
            stopLyric();
            return;
        }

        mCurrentMediaController.registerCallback(mMediaCallback, mHandler);
        updateCurrentSession();
        mHandler.removeCallbacks(mPositionUpdateRunnable);
        mHandler.post(mPositionUpdateRunnable);
    }

    private boolean hasPlayableState(MediaController controller) {
        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return false;
        }
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_CONNECTING:
                return controller.getMetadata() != null;
            default:
                return false;
        }
    }

    private void updateCurrentSession() {
        if (!mEnabled || mCurrentMediaController == null) {
            return;
        }
        if (!hasPlayableState(mCurrentMediaController)) {
            refreshActiveSessions();
            return;
        }
        MediaMetadata metadata = mCurrentMediaController.getMetadata();
        String title = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title) && metadata != null) {
            title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        }
        if (TextUtils.isEmpty(title)) {
            mCurrentLyrics = null;
            mCurrentTrackKey = null;
            stopLyric();
            return;
        }
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) {
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        long durationMs = metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)
                ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        String sourceSetting = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.STATUS_BAR_LYRIC_SOURCES);
        String trackKey = title + "\u0000" + artist + "\u0000" + durationMs
                + "\u0000" + sourceSetting;
        if (TextUtils.equals(mCurrentTrackKey, trackKey)) {
            updateDisplayedLyric();
            return;
        }

        mCurrentTrackKey = trackKey;
        mCurrentLyrics = null;
        stopLyric();
        final MediaController requestedController = mCurrentMediaController;
        final String requestedTitle = title;
        final String requestedArtist = artist;
        final long requestedDurationMs = durationMs;
        final String requestedSourceSetting = sourceSetting;
        mLyricExecutor.execute(() -> {
            LyricSource.Lyrics lyrics = null;
            for (LyricSource source : LyricSourceFactory.create(requestedSourceSetting)) {
                lyrics = source.fetch(requestedTitle, requestedArtist, requestedDurationMs);
                if (lyrics != null) {
                    break;
                }
            }
            final LyricSource.Lyrics fetchedLyrics = lyrics;
            mHandler.post(() -> {
                if (requestedController != mCurrentMediaController
                        || !TextUtils.equals(trackKey, mCurrentTrackKey)) {
                    return;
                }
                mCurrentLyrics = fetchedLyrics;
                if (mCurrentLyrics == null) {
                    return;
                }
                setIconForAllHolders(resolveSessionIcon(requestedController));
                updateDisplayedLyric();
            });
        });
    }

    private void updateDisplayedLyric() {
        if (mCurrentLyrics == null || mCurrentMediaController == null) {
            return;
        }
        PlaybackState state = mCurrentMediaController.getPlaybackState();
        if (state == null) {
            return;
        }
        LyricSource.Cue cue = mCurrentLyrics.getCueAt(getPlaybackPosition(state));
        if (cue == null) {
            setTextForAllHolders("", null);
            if (mStarted) {
                hideLyricView(false);
            }
            return;
        }
        setTextForAllHolders(cue.text, cue.translatedText);
        if (!mStarted) {
            startLyric();
        }
    }

    private long getPlaybackPosition(PlaybackState state) {
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            position += (long) (elapsed * state.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    private Drawable resolveSessionIcon(MediaController controller) {
        try {
            return mContext.getPackageManager().getApplicationIcon(controller.getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void detachCurrentController() {
        if (mCurrentMediaController != null) {
            mCurrentMediaController.unregisterCallback(mMediaCallback);
            mCurrentMediaController = null;
        }
        mHandler.removeCallbacks(mPositionUpdateRunnable);
    }

    public void startLyric() {
        if (!mStarted) {
            mStarted = true;
            showLyricView(true);
            postApplyTextTint();
        }
    }

    public void stopLyric() {
        if (mStarted) {
            mStarted = false;
            mTemporarilyHidden = false;
            mOverlayLyricViewHolder.mLyricContainer.removeCallbacks(mRestoreLyricRunnable);
            hideLyricView(true);
        }
        mCurrentLyricText = null;
        mCurrentTranslatedText = null;
        setTextForAllHolders(null, null);
    }

    public abstract void showLyricView(boolean animate);

    public abstract void hideLyricView(boolean animate);

    public boolean isLyricStarted() {
        return mStarted;
    }

    protected final View getLyricView() {
        return getActiveLyricViewHolder().mLyricContainer;
    }

    protected final View getOverlayLyricView() {
        return mOverlayLyricViewHolder.mLyricContainer;
    }

    protected final View getInlineLyricView() {
        return mInlineLyricViewHolder == null ? null : mInlineLyricViewHolder.mLyricContainer;
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> area, float darkIntensity, int tint) {
        int textTint = mTintReferenceView != null
                ? DarkIconDispatcher.getTint(area, mTintReferenceView, tint) : tint;
        mOverlayTintColor = textTint;
        mInlineTintColor = textTint;
        applyTextTint();
    }

    private LyricViewHolder createLyricViewHolder(View statusBar, int containerId, int iconId,
            int textId, int subtitleTextId, boolean required) {
        View lyricContainer = statusBar.findViewById(containerId);
        if (lyricContainer == null) {
            if (required) {
                throw new IllegalStateException("Missing lyric container: " + containerId);
            }
            return null;
        }
        TextSwitcher subtitleTextSwitcher = lyricContainer.findViewById(subtitleTextId);
        return new LyricViewHolder(lyricContainer, lyricContainer.requireViewById(iconId),
                lyricContainer.requireViewById(textId), subtitleTextSwitcher);
    }

    private void setUpAnimations(LyricViewHolder holder, Animation animationIn,
            Animation animationOut) {
        holder.mTextSwitcher.setInAnimation(animationIn);
        holder.mTextSwitcher.setOutAnimation(animationOut);
        holder.mIconSwitcher.setInAnimation(animationIn);
        holder.mIconSwitcher.setOutAnimation(animationOut);
        if (holder.mSubtitleTextSwitcher != null) {
            holder.mSubtitleTextSwitcher.setInAnimation(null);
            holder.mSubtitleTextSwitcher.setOutAnimation(null);
        }
    }

    private LyricViewHolder getActiveLyricViewHolder() {
        return isClockRightMode() ? mInlineLyricViewHolder : mOverlayLyricViewHolder;
    }

    private void hideInactiveLyricViewsImmediately() {
        if (mOverlayLyricViewHolder != getActiveLyricViewHolder()) {
            hideViewImmediately(mOverlayLyricViewHolder.mLyricContainer);
        }
        if (mInlineLyricViewHolder != null && mInlineLyricViewHolder != getActiveLyricViewHolder()) {
            hideViewImmediately(mInlineLyricViewHolder.mLyricContainer);
        }
    }

    private void hideViewImmediately(View view) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
    }

    private void syncHolderContent(LyricViewHolder from, LyricViewHolder to) {
        if (from == null || to == null || from == to) {
            return;
        }
        Drawable currentDrawable = ((ImageView) from.mIconSwitcher.getCurrentView()).getDrawable();
        if (currentDrawable != null) {
            to.mIconSwitcher.setImageDrawable(copyDrawable(currentDrawable));
        }
        CharSequence currentText = ((TextView) from.mTextSwitcher.getCurrentView()).getText();
        if (!TextUtils.isEmpty(currentText)) {
            to.mTextSwitcher.setCurrentText(currentText);
        }
        syncSubtitle(from, to);
        if (to.mSubtitleTextSwitcher != null) {
            setSubtitle(to, getVisibleTranslatedText());
        }
        updateIconTint(to, getTintColorForHolder(to));
    }

    private void setIconForAllHolders(Drawable icon) {
        if (icon == null) {
            return;
        }
        mOverlayLyricViewHolder.mIconSwitcher.setImageDrawable(copyDrawable(icon));
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mIconSwitcher.setImageDrawable(copyDrawable(icon));
        }
        updateIconTint();
        updateIconVisibility();
    }

    private void setTextForAllHolders(CharSequence text, CharSequence translatedText) {
        boolean lyricChanged = !TextUtils.equals(mCurrentLyricText, text);
        boolean translationChanged = !TextUtils.equals(mCurrentTranslatedText, translatedText);
        mCurrentLyricText = text;
        mCurrentTranslatedText = translatedText;
        if (lyricChanged) {
            mOverlayLyricViewHolder.mTextSwitcher.setText(text);
            if (mInlineLyricViewHolder != null) {
                mInlineLyricViewHolder.mTextSwitcher.setText(text);
            }
        }
        if (translationChanged) {
            setSubtitle(mOverlayLyricViewHolder, getVisibleTranslatedText());
            if (mInlineLyricViewHolder != null) {
                setSubtitle(mInlineLyricViewHolder, getVisibleTranslatedText());
            }
        }
        postApplyTextTint();
    }

    private CharSequence getVisibleTranslatedText() {
        return mShowTranslation ? mCurrentTranslatedText : null;
    }

    private void updateIconVisibility() {
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mIconSwitcher.setVisibility(
                    mHideIconOnClockRight && isClockRightMode() ? View.GONE : View.VISIBLE);
        }
    }

    private Drawable copyDrawable(Drawable drawable) {
        ConstantState constantState = drawable.getConstantState();
        return constantState != null ? constantState.newDrawable().mutate() : drawable;
    }

    private void updateIconTint() {
        updateIconTint(mOverlayLyricViewHolder, mOverlayTintColor);
        if (mInlineLyricViewHolder != null) {
            updateIconTint(mInlineLyricViewHolder, mInlineTintColor);
        }
    }

    private void updateIconTint(LyricViewHolder holder, int tintColor) {
        Drawable drawable = ((ImageView) holder.mIconSwitcher.getCurrentView()).getDrawable();
        if (drawable == null) {
            return;
        }
        boolean isGrayscale = mNotificationColorUtil.isGrayscaleIcon(drawable);
        ColorStateList tintList = ColorStateList.valueOf(tintColor);
        ImageView currentView = (ImageView) holder.mIconSwitcher.getCurrentView();
        ImageView nextView = (ImageView) holder.mIconSwitcher.getNextView();
        currentView.setImageTintList(isGrayscale ? tintList : null);
        nextView.setImageTintList(isGrayscale ? tintList : null);
    }

    private void updateTextTint(LyricViewHolder holder, int tintColor) {
        ((TextView) holder.mTextSwitcher.getCurrentView()).setTextColor(tintColor);
        ((TextView) holder.mTextSwitcher.getNextView()).setTextColor(tintColor);
        if (holder.mSubtitleTextSwitcher != null) {
            ((TextView) holder.mSubtitleTextSwitcher.getCurrentView()).setTextColor(tintColor);
            ((TextView) holder.mSubtitleTextSwitcher.getNextView()).setTextColor(tintColor);
        }
    }

    private void setSubtitle(LyricViewHolder holder, CharSequence translatedText) {
        if (holder.mSubtitleTextSwitcher == null) {
            return;
        }
        if (TextUtils.isEmpty(translatedText)) {
            holder.mSubtitleTextSwitcher.setCurrentText("");
            holder.mSubtitleTextSwitcher.setVisibility(View.GONE);
            return;
        }
        int tintColor = getTintColorForHolder(holder);
        ((TextView) holder.mSubtitleTextSwitcher.getCurrentView()).setTextColor(tintColor);
        ((TextView) holder.mSubtitleTextSwitcher.getNextView()).setTextColor(tintColor);
        holder.mSubtitleTextSwitcher.setVisibility(View.VISIBLE);
        holder.mSubtitleTextSwitcher.setText(translatedText);
    }

    private void syncSubtitle(LyricViewHolder from, LyricViewHolder to) {
        if (from.mSubtitleTextSwitcher == null || to.mSubtitleTextSwitcher == null) {
            return;
        }
        CharSequence currentText = ((TextView) from.mSubtitleTextSwitcher.getCurrentView()).getText();
        if (TextUtils.isEmpty(currentText)) {
            to.mSubtitleTextSwitcher.setCurrentText("");
            to.mSubtitleTextSwitcher.setVisibility(View.GONE);
            return;
        }
        to.mSubtitleTextSwitcher.setVisibility(View.VISIBLE);
        to.mSubtitleTextSwitcher.setCurrentText(currentText);
    }

    private void applyTextTint() {
        updateTextTint(mOverlayLyricViewHolder, mOverlayTintColor);
        if (mInlineLyricViewHolder != null) {
            updateTextTint(mInlineLyricViewHolder, mInlineTintColor);
        }
        updateIconTint();
    }

    private int getTintColorForHolder(LyricViewHolder holder) {
        return holder == mInlineLyricViewHolder ? mInlineTintColor : mOverlayTintColor;
    }

    private void postApplyTextTint() {
        mOverlayLyricViewHolder.mLyricContainer.post(this::applyTextTint);
    }

    private static final class LyricViewHolder {
        final View mLyricContainer;
        final ImageSwitcher mIconSwitcher;
        final TextSwitcher mTextSwitcher;
        final TextSwitcher mSubtitleTextSwitcher;

        LyricViewHolder(View lyricContainer, ImageSwitcher iconSwitcher,
                TextSwitcher textSwitcher, TextSwitcher subtitleTextSwitcher) {
            mLyricContainer = lyricContainer;
            mIconSwitcher = iconSwitcher;
            mTextSwitcher = textSwitcher;
            mSubtitleTextSwitcher = subtitleTextSwitcher;
        }
    }
}
