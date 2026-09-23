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

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.content.Context;
import android.content.IClipboard;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;

/** Bottom sheet shown when an app with ask-policy accesses the clipboard. */
public class ClipboardAccessPromptActivity extends Activity {
    private static final String TAG = "ClipboardAccessPrompt";
    private static final String PACKAGE_NAME = "com.android.internal.app";

    private static final String EXTRA_PACKAGE_NAME = PACKAGE_NAME + ".extra.CLIPBOARD_PACKAGE";
    private static final String EXTRA_OPERATION = PACKAGE_NAME + ".extra.CLIPBOARD_OPERATION";

    public static final int OPERATION_READ = 0;
    public static final int OPERATION_WRITE = 1;

    private int mUserId;
    private int mOperation;
    private String mPackageName;
    private CheckBox mPermanentRuleView;
    private boolean mPromptResolved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        setFinishOnTouchOutside(true);

        final Intent intent = getIntent();
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_ID, -1);
        mPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        mOperation = intent.getIntExtra(EXTRA_OPERATION, -1);
        if (mUserId < 0 || mPackageName == null
                || (mOperation != OPERATION_READ && mOperation != OPERATION_WRITE)) {
            Log.wtf(TAG, "Invalid clipboard access prompt intent: " + intent);
            mPromptResolved = true;
            finish();
            return;
        }

        setContentView(createSheet());
        final Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        window.setDimAmount(0.32f);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(R.style.AutofillHalfScreenAnimation);
    }

    private FrameLayout createSheet() {
        final FrameLayout root = new FrameLayout(this);
        root.setPadding(dp(16), 0, dp(16), dp(16));

        final LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(24), dp(24), dp(24));
        sheet.setBackground(roundedBackground(R.color.materialColorSurfaceContainerLow, 28));
        final int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32),
                dp(560));
        root.addView(sheet, new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        final FrameLayout iconContainer = new FrameLayout(this);
        iconContainer.setBackground(roundedBackground(R.color.materialColorPrimaryContainer, 18));
        final LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        iconParams.bottomMargin = dp(20);
        sheet.addView(iconContainer, iconParams);
        final ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_clipboard_access_hand);
        icon.setImageTintList(ColorStateList.valueOf(
                getColor(R.color.materialColorOnPrimaryContainer)));
        iconContainer.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));

        final boolean isRead = mOperation == OPERATION_READ;
        final TextView title = new TextView(this);
        title.setText(isRead ? R.string.clipboard_access_prompt_read_title
                : R.string.clipboard_access_prompt_write_title);
        title.setTextColor(getColor(R.color.materialColorOnSurface));
        title.setTextSize(24);
        title.setTypeface(Typeface.create("google-sans", Typeface.NORMAL));
        sheet.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView message = new TextView(this);
        message.setText(getString(isRead ? R.string.clipboard_access_prompt_read_message
                : R.string.clipboard_access_prompt_write_message, loadAppLabel()));
        message.setTextColor(getColor(R.color.materialColorOnSurfaceVariant));
        message.setTextSize(16);
        final LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(12);
        sheet.addView(message, messageParams);

        mPermanentRuleView = new CheckBox(this);
        mPermanentRuleView.setText(R.string.clipboard_access_write_permanent_rule);
        mPermanentRuleView.setChecked(false);
        mPermanentRuleView.setTextColor(getColor(R.color.materialColorOnSurface));
        final LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        checkParams.topMargin = dp(16);
        sheet.addView(mPermanentRuleView, checkParams);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(20);
        sheet.addView(actions, actionsParams);
        final Button deny = createActionButton(R.string.app_jump_deny,
                R.color.materialColorPrimary, R.color.materialColorOnPrimary);
        final Button allow = createActionButton(R.string.app_jump_allow,
                R.color.materialColorSecondaryContainer,
                R.color.materialColorOnSecondaryContainer);
        final LinearLayout.LayoutParams denyParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        denyParams.setMarginEnd(dp(8));
        actions.addView(deny, denyParams);
        final LinearLayout.LayoutParams allowParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        allowParams.setMarginStart(dp(8));
        actions.addView(allow, allowParams);
        deny.setOnClickListener(view -> applyDecision(Settings.Secure.UWU_APP_CLIPBOARD_POLICY_DENY));
        allow.setOnClickListener(view -> applyDecision(Settings.Secure.UWU_APP_CLIPBOARD_POLICY_ALLOW));
        return root;
    }

    private Button createActionButton(int text, int backgroundColor, int textColor) {
        final Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(getColor(textColor));
        button.setTextSize(16);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(getColor(R.color.materialColorControlHighlight)),
                roundedBackground(backgroundColor, 28), null));
        return button;
    }

    private GradientDrawable roundedBackground(int color, int radiusDp) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(getColor(color));
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyDecision(int decision) {
        if (!resolvePrompt(decision, mPermanentRuleView.isChecked())) {
            Log.e(TAG, "Unable to apply clipboard choice for " + mPackageName);
            Toast.makeText(this, R.string.clipboard_access_policy_save_failed, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        mPromptResolved = true;
        finish();
    }

    private boolean resolvePrompt(int decision, boolean persist) {
        final IClipboard clipboard =
                IClipboard.Stub.asInterface(ServiceManager.getService(Context.CLIPBOARD_SERVICE));
        if (clipboard == null) {
            return false;
        }
        try {
            return clipboard.resolveClipboardAccessPrompt(
                    mPackageName, mUserId, mOperation, decision, persist);
        } catch (RemoteException e) {
            Log.e(TAG, "Clipboard service rejected the prompt result", e);
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && !mPromptResolved && mPackageName != null) {
            resolvePrompt(Settings.Secure.UWU_APP_CLIPBOARD_POLICY_ASK, false);
            mPromptResolved = true;
        }
        super.onDestroy();
    }

    private CharSequence loadAppLabel() {
        try {
            final ApplicationInfo appInfo =
                    getPackageManager().getApplicationInfoAsUser(mPackageName, 0, mUserId);
            return appInfo.loadSafeLabel(
                    getPackageManager(),
                    PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                    PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE
                            | PackageItemInfo.SAFE_LABEL_FLAG_TRIM);
        } catch (PackageManager.NameNotFoundException e) {
            return mPackageName;
        }
    }

    public static Intent createIntent(
            Context context, int userId, String packageName, int operation) {
        return new Intent()
                .setClass(context, ClipboardAccessPromptActivity.class)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_OPERATION, operation)
                .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
}
