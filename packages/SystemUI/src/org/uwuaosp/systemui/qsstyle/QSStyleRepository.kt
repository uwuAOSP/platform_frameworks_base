/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.qsstyle

import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Provides the active [QSTileStyle], tracking `Settings.Secure.UWU_QS_STYLE`.
 *
 * Consumers observe [style] and recompose when the value changes, which makes the style switch take
 * effect on the currently shown shade as well.
 */
@SysUISingleton
class QSStyleRepository
@Inject
constructor(
    @Background scope: CoroutineScope,
    secureSettingsRepository: SecureSettingsRepository,
) {
    val style: StateFlow<QSTileStyle> =
        secureSettingsRepository
            .intSetting(Settings.Secure.UWU_QS_STYLE, QSTileStyle.SETTING_DEFAULT)
            .map { QSTileStyle.fromSetting(it) }
            .distinctUntilChanged()
            // Eagerly, so the current value is available as soon as the repository exists
            // (tiles and view models read it during the first composition after boot).
            .stateIn(scope, SharingStarted.Eagerly, QSTileStyle.DEFAULT)
}
