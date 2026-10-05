/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.qsstyle

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.shared.settings.data.repository.secureSettingsRepository

val Kosmos.qsStyleRepository: QSStyleRepository by
    Kosmos.Fixture { QSStyleRepository(backgroundScope, secureSettingsRepository) }
