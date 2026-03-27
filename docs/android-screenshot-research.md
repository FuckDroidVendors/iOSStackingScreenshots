# Android Screenshot Research

## Problem Statement
- Goal: Android screenshot app with a persistent floating preview of the last screenshot.
- Requirement: when the user takes another screenshot, the floating preview must remain visible on the device display and must not appear in the new screenshot.
- Strong requirement: no visible blink or hide/show cycle while the new screenshot is taken.

## Device Environment
- The target device is rooted with KernelSU.
- LSPosed is installed.
- Shizuku is available.
- This materially changes feasibility: private APIs and framework hooks are now realistic, even if they remain non-portable and version-sensitive.

## Existing Reference
- [screenshot_plugin.c](/home/duda/screenshotdroid/screenshot_plugin.c) shows the X11/compositor-style solution already used elsewhere.
- That approach works because the compositor can decide what enters the captured composition and can exclude the thumbnail overlay during capture.

## What Public Android Gives You

### Screen capture
- Public full-screen capture is done with `MediaProjection`.
- On Android 14+, `MediaProjection` can also capture a single app window instead of the whole display.

### Overlay UI
- A normal app can show a floating overlay with `TYPE_APPLICATION_OVERLAY` if it has `SYSTEM_ALERT_WINDOW`.
- An accessibility service can use `TYPE_ACCESSIBILITY_OVERLAY`.

### Secure content
- `FLAG_SECURE` prevents a window from appearing in screenshots or non-secure displays.
- `View.setContentSensitivity(...)` can cause a window to become secure during media projection.

## Why This Does Not Fully Solve The Requirement
- Public `MediaProjection` captures a display or app window, not "everything except these specific overlay layers."
- Public overlay APIs let an app draw above other apps, but they do not provide a public exclusion list for screenshots.
- `FLAG_SECURE` hides the secure window's content from capture, but the public docs do not describe it as a compositor filter that reveals the pixels behind that window.
- Inference from AOSP internals: true layer exclusion is handled by private/system capture APIs, which exist specifically because secure-content handling is not the same as "subtract this overlay and keep the underlying scene."

## AOSP / System-Level Findings

### There is an internal exclude-layers capture path
- AOSP `DisplayContent.getLayerCaptureArgs(...)` builds capture args and calls `setExcludeLayers(...)` for matching windows.
- Hidden/internal `ScreenCapture` APIs support excluding specific `SurfaceControl` layers from capture.

### Android already excludes some system overlays internally
- AOSP contains platform behavior where windows marked with `PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY` are excluded from screenshots by default.
- This is very close in spirit to the requested feature: a visible overlay layer that is not part of the captured result.

## Practical Conclusion

### Stock third-party app
- Feasible:
  - capture screenshots with `MediaProjection`
  - show the last screenshot in a floating overlay
  - update that overlay without blinking by double-buffering the app's own UI
- Not feasible through public SDK alone:
  - keep the overlay visible on the physical display
  - exclude it from full-display screenshots
  - preserve the pixels underneath that overlay area

### Privileged/system path
- Feasible in principle:
  - run the screenshot feature as a privileged/system component
  - own the overlay surface in SystemUI or another platform-signed component
  - mark or track that surface
  - exclude it in the screenshot capture path using internal exclude-layer support
- This is the Android architecture that best matches the existing X11 compositor-plugin approach.

### Rooted-device path
- Feasible in principle:
  - hook screenshot initiation in SystemUI or framework code using LSPosed
  - create or control the preview overlay from a privileged context
  - route capture through hidden/internal screenshot APIs that support excluded layers
  - or patch capture args before the screenshot is taken
- This is likely the best path for the stated device environment because it does not require shipping a full custom ROM, but still permits framework-level behavior.

## Architecture Options

### Option 1: Stock app with compromises
- Use `MediaProjection` for capture.
- Use `TYPE_APPLICATION_OVERLAY` for the floating preview.
- Keep the overlay visible between screenshots.
- Accept that exact filtering is not available publicly.
- Value: shippable without OS modification.
- Cost: does not meet the exact no-overlay-in-screenshot requirement.

### Option 2: Stock app using secure overlay as an experiment
- Put the thumbnail overlay in a secure window.
- Test what the screenshot contains in the covered region.
- This may hide the thumbnail itself from capture, but it is not yet validated as a correct "show underlying pixels instead" solution.
- Treat this as an experiment, not an assumed fix.

### Option 3: Android 14+ scoped variant
- Use app-window sharing instead of full-display sharing.
- Keep the thumbnail overlay outside the selected target app window.
- This can avoid capturing some non-target UI, but it is not equivalent to a normal device-wide screenshot.

### Option 4: AOSP/SystemUI implementation
- Add the screenshot preview as a system-managed overlay surface.
- Exclude that surface from the capture args.
- Update the preview atomically so the on-screen thumbnail never disappears while a new screenshot is captured.
- This is the only path found so far that matches the requirement precisely.

### Option 5: Rooted implementation with LSPosed/Shizuku
- Hook the stock screenshot flow in SystemUI or window manager code.
- Keep the preview surface owned by a privileged or hooked component.
- Use hidden screenshot APIs or patched capture args to exclude the preview layer.
- Use Shizuku if binder access is needed from the app side.
- Value: much closer to exact behavior on the target device without building a full ROM.
- Cost: Android-version fragility, private API churn, SELinux / permission edge cases, and more maintenance than an ordinary app.

## Recommended Next Investigation
- Because the device is rooted, audit SystemUI/framework screenshot flow first.
- Identify the exact classes/methods used on the target Android version for screenshot initiation and capture arg construction.
- Build a tiny rooted spike that proves one of these:
  - an LSPosed hook can exclude a known overlay layer from capture
  - a Shizuku-backed privileged call can capture while excluding that layer
- Keep the secure-overlay test as a fallback, not the primary plan.

## Sources
- Android Developers: Media projection
  - https://developer.android.com/media/grow/media-projection
- Android Developers: `WindowManager.LayoutParams`
  - https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- Android Developers: `View`
  - https://developer.android.com/reference/android/view/View
- Android Developers: `AccessibilityWindowInfo`
  - https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo
- AOSP: `DisplayContent.getLayerCaptureArgs(...)` with `setExcludeLayers(...)`
  - https://android.googlesource.com/platform/frameworks/base/%2B/master/services/core/java/com/android/server/wm/DisplayContent.java
- AOSP: `WindowManagerService.takeAssistScreenshot(...)`
  - https://android.googlesource.com/platform/frameworks/base/%2B/master/services/core/java/com/android/server/wm/WindowManagerService.java
- AOSP hidden API: `ScreenCapture` layer exclusion support
  - https://android.googlesource.com/platform/prebuilts/fullsdk/sources/%2B/refs/heads/androidx-graphics-release/android-34/android/window/ScreenCapture.java
- AOSP native capture args: exclude handles
  - https://android.googlesource.com/platform/frameworks/native/%2B/2f8fa1367c/libs/gui/include/gui/LayerCaptureArgs.h
- AOSP commit note: rounded-corner overlays excluded from screenshots by default
  - https://android.googlesource.com/platform/frameworks/base/%2B/806bd739bf8a4cdd185db41df9e73f64df92d44f%5E2
