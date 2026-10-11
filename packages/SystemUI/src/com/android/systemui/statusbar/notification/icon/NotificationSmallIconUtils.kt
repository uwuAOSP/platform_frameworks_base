/*
 * SPDX-FileCopyrightText: 2026 IamCanincan
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0 AND MIT
 */

package com.android.systemui.statusbar.notification.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import com.android.internal.util.ContrastColorUtil

/** Helpers for replacing notification small icons that have no monochrome treatment. */
internal object NotificationSmallIconUtils {
    private const val ICON_SIZE = 96

    /** Keep icons that are already theme-ready, and leave icons alone if they cannot be decoded. */
    fun needsLauncherMonochrome(icon: Icon?, context: Context): Boolean {
        if (icon == null) return false
        return runCatching {
            val drawable = icon.loadDrawable(context) ?: return false
            if (drawable is AdaptiveIconDrawable && drawable.monochrome != null) return false
            !ContrastColorUtil.getInstance(context).isGrayscaleIcon(context, icon)
        }.getOrDefault(false)
    }

    /**
     * Converts a launcher icon into a grayscale drawable. Adaptive icons use their foreground so
     * that the opaque background plate does not turn into a solid notification glyph.
     */
    fun toMonochrome(drawable: Drawable): Drawable? = runCatching {
        val source = if (drawable is AdaptiveIconDrawable) drawable.foreground else drawable
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        source.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        source.draw(canvas)

        val pixels = IntArray(ICON_SIZE * ICON_SIZE)
        bitmap.getPixels(pixels, 0, ICON_SIZE, 0, 0, ICON_SIZE, ICON_SIZE)
        var hasShape = false
        for (index in pixels.indices) {
            val color = pixels[index]
            val alpha = Color.alpha(color)
            if (alpha == 0) continue
            hasShape = true
            val luminance = (Color.red(color) * 299 + Color.green(color) * 587 +
                Color.blue(color) * 114) / 1000
            pixels[index] = Color.argb(alpha, luminance, luminance, luminance)
        }
        if (!hasShape) return null
        bitmap.setPixels(pixels, 0, ICON_SIZE, 0, 0, ICON_SIZE, ICON_SIZE)
        BitmapDrawable(null, bitmap)
    }.getOrNull()

    fun toMonochromeIcon(drawable: Drawable): Icon? {
        val bitmapDrawable = toMonochrome(drawable) as? BitmapDrawable ?: return null
        return Icon.createWithBitmap(bitmapDrawable.bitmap)
    }
}
