/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.AirplanemodeInactive
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoNotDisturbOff
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FiberSmartRecord
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R

/**
 * Resolves the standard AndroidX Material Icons used by the circular tile style.
 *
 * The source icon remains untouched for the default style. Resource aliases are used only as a
 * stable hand-off between QSTile's resource icons and the Compose Material icon renderer.
 */
internal fun Context.uwuMaterialTileIconResource(@DrawableRes resourceId: Int): Int? {
    val name = runCatching { resources.getResourceEntryName(resourceId) }.getOrNull() ?: return null
    val lowerName = name.lowercase()
    val inactive =
        lowerName.contains("_off") ||
            lowerName.contains("_inactive") ||
            lowerName.contains("disconnected") ||
            lowerName.contains("unavailable")

    return when {
        lowerName.contains("bluetooth") ->
            if (inactive) R.drawable.uwu_material_bluetooth_off
            else R.drawable.uwu_material_bluetooth_on
        lowerName.contains("flashlight") || lowerName.contains("torch") ->
            if (inactive) R.drawable.uwu_material_flashlight_off
            else R.drawable.uwu_material_flashlight_on
        lowerName.contains("airplane") || lowerName.contains("airplanemode") ->
            if (inactive) R.drawable.uwu_material_airplane_off
            else R.drawable.uwu_material_airplane_on
        lowerName.contains("dnd") || lowerName.contains("do_not_disturb") ->
            if (inactive) R.drawable.uwu_material_dnd_off else R.drawable.uwu_material_dnd_on
        lowerName.contains("location") || lowerName.contains("gps") ->
            if (inactive) R.drawable.uwu_material_location_off
            else R.drawable.uwu_material_location_on
        lowerName.contains("battery_saver") || lowerName.contains("powersave") ->
            R.drawable.uwu_material_battery_saver
        lowerName.contains("screen_record") || lowerName.contains("screenrecord") ->
            R.drawable.uwu_material_screen_record
        lowerName.contains("camera_access") -> R.drawable.uwu_material_camera
        lowerName.contains("mic_access") || lowerName.contains("microphone") ->
            if (inactive) R.drawable.uwu_material_mic_off else R.drawable.uwu_material_mic_on
        lowerName.contains("nightlight") || lowerName.contains("night_light") ->
            R.drawable.uwu_material_nightlight
        lowerName.contains("auto_rotate") || lowerName.contains("rotation") ->
            R.drawable.uwu_material_rotation
        lowerName.contains("wifi") || lowerName.contains("wlan") ->
            if (inactive) R.drawable.uwu_material_wifi_off else R.drawable.uwu_material_wifi_on
        lowerName.contains("cast") || lowerName.contains("screen_share") ->
            R.drawable.uwu_material_cast
        lowerName.contains("nfc") -> R.drawable.uwu_material_nfc
        lowerName.contains("light_dark_theme") || lowerName.contains("dark_mode") ->
            R.drawable.uwu_material_dark_mode
        lowerName.contains("usb") -> R.drawable.uwu_material_usb
        else -> null
    }
}

/** Converts a circular tile's resource icon to the matching Material icon alias. */
internal fun Context.uwuMaterialTileIcon(icon: Icon): Icon {
    if (icon !is Icon.Resource) return icon
    val materialResource = uwuMaterialTileIconResource(icon.resId) ?: return icon
    return Icon.Resource(materialResource, icon.contentDescription)
}

/** Returns the AndroidX Material vector for a resource alias, or null for custom/stock icons. */
internal fun Context.uwuMaterialTileIconVector(@DrawableRes resourceId: Int): ImageVector? {
    return when (runCatching { resources.getResourceEntryName(resourceId) }.getOrNull()) {
        "uwu_material_airplane_off" -> Icons.Filled.AirplanemodeInactive
        "uwu_material_airplane_on" -> Icons.Filled.AirplanemodeActive
        "uwu_material_battery_saver" -> Icons.Filled.BatterySaver
        "uwu_material_bluetooth_off" -> Icons.Filled.BluetoothDisabled
        "uwu_material_bluetooth_on" -> Icons.Filled.Bluetooth
        "uwu_material_cast" -> Icons.Filled.Cast
        "uwu_material_camera" -> Icons.Filled.CameraAlt
        "uwu_material_dark_mode" -> Icons.Filled.DarkMode
        "uwu_material_dnd_off" -> Icons.Filled.DoNotDisturbOff
        "uwu_material_dnd_on" -> Icons.Filled.DoNotDisturbOn
        "uwu_material_flashlight_off" -> Icons.Filled.FlashlightOff
        "uwu_material_flashlight_on" -> Icons.Filled.FlashlightOn
        "uwu_material_location_off" -> Icons.Filled.LocationOff
        "uwu_material_location_on" -> Icons.Filled.MyLocation
        "uwu_material_mic_off" -> Icons.Filled.MicOff
        "uwu_material_mic_on" -> Icons.Filled.Mic
        "uwu_material_nfc" -> Icons.Filled.Nfc
        "uwu_material_nightlight" -> Icons.Filled.Nightlight
        "uwu_material_rotation" -> Icons.Filled.ScreenRotation
        "uwu_material_screen_record" -> Icons.Filled.FiberSmartRecord
        "uwu_material_usb" -> Icons.Filled.Usb
        "uwu_material_wifi_off" -> Icons.Filled.WifiOff
        "uwu_material_wifi_on" -> Icons.Filled.Wifi
        else -> null
    }
}
