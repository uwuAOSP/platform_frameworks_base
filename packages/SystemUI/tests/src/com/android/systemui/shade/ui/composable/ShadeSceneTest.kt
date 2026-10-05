/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.ui.composable

import android.content.res.Configuration
import android.platform.test.annotations.DisableFlags
import android.testing.TestableLooper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.TestContentScope
import com.android.compose.theme.PlatformTheme
import com.android.systemui.Flags
import com.android.systemui.Flags.FLAG_DUAL_SHADE
import com.android.systemui.SysuiTestCase
import com.android.systemui.compose.modifiers.resIdToTestTag
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.notifications.intelligence.rules.ui.viewmodel.notificationRulesParentViewModelFactory
import com.android.systemui.qs.composefragment.dagger.usingMediaInComposeFragment
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import com.android.systemui.scene.session.shared.SessionStorage
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.session.ui.composable.Session
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.enableSingleShade
import com.android.systemui.shade.domain.interactor.enableSplitShade
import com.android.systemui.shade.ui.viewmodel.shadeSceneContentViewModelFactory
import com.android.systemui.shade.ui.viewmodel.shadeUserActionsViewModelFactory
import com.android.systemui.statusbar.notification.stack.ui.view.notificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModelFactory
import com.android.systemui.statusbar.phone.ui.tintedIconManagerFactory
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
@DisableFlags(FLAG_DUAL_SHADE)
class ShadeSceneTest : SysuiTestCase() {
    @get:Rule val composeTestRule = createComposeRule()

    private val kosmos = testKosmos()

    @Before
    fun setUp() {
        kosmos.enableSingleShade()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSingleShadeHierarchy() =
        with(kosmos) {
            testScope.runTest {
                val shadeSession =
                    object : SaveableSession, Session by Session(SessionStorage()) {
                        @Composable
                        override fun <T : Any> rememberSaveableSession(
                            vararg inputs: Any?,
                            saver: Saver<T, out Any>,
                            key: String?,
                            init: () -> T,
                        ): T = rememberSession(key, inputs = inputs, init = init)
                    }

                usingMediaInComposeFragment = true

                enableSingleShade()
                runCurrent()

                val scene =
                    ShadeScene(
                        shadeSession = shadeSession,
                        notificationStackScrollView = { notificationScrollView },
                        actionsViewModelFactory = shadeUserActionsViewModelFactory,
                        contentViewModelFactory = shadeSceneContentViewModelFactory,
                        notificationsPlaceholderViewModelFactory =
                            notificationsPlaceholderViewModelFactory,
                        notificationRulesParentViewModelFactory =
                            kosmos.notificationRulesParentViewModelFactory,
                        jankMonitor = interactionJankMonitor,
                    )

                // Set the single shade content.
                composeTestRule.setContent {
                    PlatformTheme {
                        WithStatusIconContext(tintedIconManagerFactory) {
                            with(scene) {
                                TestContentScope(currentScene = Scenes.Shade) { Content(Modifier) }
                            }
                        }
                    }
                }

                currentTilesInteractor.setTiles(listOf(TileSpec.create("small")))
                runCurrent()
                composeTestRule.waitForIdle()

                // Verify that the qs small tile exists.
                composeTestRule.onNodeWithTag(resIdToTestTag("qs_tile_small")).assertExists()

                coroutineContext.cancelChildren()
            }
        }

    @DisableFlags(Flags.FLAG_STATUS_BAR_MOBILE_ICON_KAIROS)
    @Test
    fun splitShadeHierarchy() = verifySplitShadeHierarchy()

    @DisableFlags(Flags.FLAG_STATUS_BAR_MOBILE_ICON_KAIROS)
    @Test
    fun splitShadeTabletPortraitSpacing() =
        verifySplitShadeHierarchy(shadeConfiguration(600, Configuration.ORIENTATION_PORTRAIT))

    @DisableFlags(Flags.FLAG_STATUS_BAR_MOBILE_ICON_KAIROS)
    @Test
    fun splitShadeLargeTabletPortraitSpacing() =
        verifySplitShadeHierarchy(shadeConfiguration(720, Configuration.ORIENTATION_PORTRAIT))

    @DisableFlags(Flags.FLAG_STATUS_BAR_MOBILE_ICON_KAIROS)
    @Test
    fun splitShadeTabletLandscapeSpacing() =
        verifySplitShadeHierarchy(shadeConfiguration(720, Configuration.ORIENTATION_LANDSCAPE))

    @DisableFlags(Flags.FLAG_STATUS_BAR_MOBILE_ICON_KAIROS)
    @Test
    fun splitShadePhoneLandscapeKeepsExistingSpacing() =
        verifySplitShadeHierarchy(shadeConfiguration(411, Configuration.ORIENTATION_LANDSCAPE))

    private fun shadeConfiguration(smallestWidth: Int, orientation: Int) =
        Configuration(context.resources.configuration).apply {
            smallestScreenWidthDp = smallestWidth
            this.orientation = orientation
            screenWidthDp =
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) 1280 else smallestWidth
            screenHeightDp =
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) smallestWidth else 1280
        }

    private fun verifySplitShadeHierarchy(configuration: Configuration? = null) =
        kosmos.runTest {
            val resources = configuration?.let { context.createConfigurationContext(it).resources }
            val shadeSession =
                object : SaveableSession, Session by Session(SessionStorage()) {
                    @Composable
                    override fun <T : Any> rememberSaveableSession(
                        vararg inputs: Any?,
                        saver: Saver<T, out Any>,
                        key: String?,
                        init: () -> T,
                    ): T = rememberSession(key, inputs = inputs, init = init)
                }

            usingMediaInComposeFragment = true

            enableSplitShade()
            runCurrent()

            val scene =
                ShadeScene(
                    shadeSession = shadeSession,
                    notificationStackScrollView = { notificationScrollView },
                    actionsViewModelFactory = shadeUserActionsViewModelFactory,
                    contentViewModelFactory = shadeSceneContentViewModelFactory,
                    notificationsPlaceholderViewModelFactory =
                        notificationsPlaceholderViewModelFactory,
                    notificationRulesParentViewModelFactory =
                        kosmos.notificationRulesParentViewModelFactory,
                    jankMonitor = interactionJankMonitor,
                )

            // Set the shade content.
            composeTestRule.setContent {
                val contentResources = resources ?: LocalResources.current
                CompositionLocalProvider(
                    LocalResources provides contentResources,
                    LocalConfiguration provides contentResources.configuration,
                ) {
                    PlatformTheme {
                        WithStatusIconContext(tintedIconManagerFactory) {
                            with(scene) {
                                TestContentScope(currentScene = Scenes.Shade) { Content(Modifier) }
                            }
                        }
                    }
                }
            }

            currentTilesInteractor.setTiles(listOf(TileSpec.create("small")))
            runCurrent()
            composeTestRule.waitForIdle()

            // Verify that the qs small tile exists.
            composeTestRule.onNodeWithTag(resIdToTestTag("qs_tile_small")).assertExists()

            // Verify that the split shade qs exists.
            composeTestRule.onNodeWithTag("element:SplitShadeQuickSettings").assertExists()

            if (resources != null) {
                val useTabletHeader =
                    resources.configuration.smallestScreenWidthDp >= 600 &&
                        resources.getBoolean(R.bool.config_use_large_screen_shade_header)
                val header =
                    composeTestRule
                        .onNodeWithTag(resIdToTestTag(ShadeHeader.TestTags.Root))
                        .getBoundsInRoot()
                val qs =
                    composeTestRule
                        .onNodeWithTag(resIdToTestTag("quick_settings_panel"))
                        .getBoundsInRoot()
                val notifications =
                    composeTestRule.onNodeWithTag("element:NotificationScrim").getBoundsInRoot()
                val headerHeight =
                    with(composeTestRule.density) {
                        resources
                            .getDimensionPixelSize(R.dimen.large_screen_shade_header_height)
                            .toDp()
                    }
                val qsTopPadding =
                    with(composeTestRule.density) {
                        (if (useTabletHeader) {
                                resources.getDimensionPixelSize(R.dimen.qs_panel_padding_top)
                            } else {
                                0
                            })
                            .toDp()
                    }

                // Notifications stay below the header; QS top padding is applied once.
                if (useTabletHeader) {
                    assertThat(header.height.value).isAtLeast(headerHeight.value)
                }
                assertThat(qs.top.value - header.bottom.value).isWithin(1f).of(qsTopPadding.value)
                assertThat(notifications.top.value - header.bottom.value).isWithin(1f).of(0f)
            }
        }
}
