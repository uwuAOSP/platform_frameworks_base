<!--
SPDX-FileCopyrightText: The uwuAOSP Project
SPDX-License-Identifier: Apache-2.0
-->

# Camera activity capsule

This is an independent phone status-bar presentation. It reuses native activity models,
ranking, content formatting and click actions, but owns its geometry, compact/expanded
interaction, animation, ripple and lyric coordination.

## Developing another presentation

Set `OngoingActivityPresentationConfig.presentation` in
`org/uwuaosp/systemui/statusbar/OngoingActivityPresentation.kt` before creating the status bar:

- `NATIVE`: native layout, `OngoingActivityChips` / `OngoingActivityChip`, Headline and visibility
- `CAMERA_CAPSULE`: our current camera-adjacent capsule (the default)
- `STATUS_BAR_CARD`: reserved for the other designer's right-side capsule / expanded card

The card renderer is not implemented here; its selection currently falls back to native UI.
The selection is a normal variable, not a resource flag or user setting. A future style switch
can recreate the status bar after changing it. Desktop does not use this capsule.

## Boundaries

- `OngoingActivityPresentationConfig`: the shared three-way presentation selection
- `CapsuleStatusBarIntegration`: the camera presentation's integration policy
- `CapsuleStatusBarState`: capsule-only chip visibility, occupied bounds and lyric state
- `OngoingActivityCapsule`: camera geometry, activity selection and group rendering
- `CapsuleActivityChip`: the capsule's content and click presentation
- `CapsuleChipContent`: ellipsized expanded notification text, without changing native text fitting
  (navigation activities instead show their preferred text in full, without the ordinary width cap)
- `OngoingActivityCapsuleStyle`: design ratios, icon compensation and timing
- `CapsuleHomeStatusBarViewBinder`: phone view and lyric integration
- `res/layout/uwu_camera_capsule_status_bar.xml`: the capsule's full-width lyric layout

The shared integration surface is intentionally small:

1. `StatusBarRoot` chooses the layout and renderer instead of deleting the native branches.
2. `HomeStatusBarViewBinder` delegates only when this presentation is selected.
3. `HomeStatusBarViewModelImpl` exposes an optional `CapsuleStatusBarHost` bridge, without
   changing the native `HomeStatusBarViewModel` interface or its visibility rules.
4. `OngoingActivityChipsViewModel.presentationChips` exposes ranked/refined models before
   native row-specific truncation. Its original `chips` flow is unchanged.
5. The lyric controller has an optional started-state callback used by this binder.

No capsule interaction flags are added to native activity models or screen-recording
view models. The default auto-collapse delay is three seconds, extended when requested
by accessibility services.
