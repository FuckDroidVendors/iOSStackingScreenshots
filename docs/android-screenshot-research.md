# Android Screenshot Research

## Problem Statement
- Goal: Android screenshot app with a persistent floating preview of the last screenshot.
- Requirement: when the user takes another screenshot, the floating preview must remain visible on the device display and must not appear in the new screenshot.
- Strong requirement: no visible blink or hide/show cycle while the new screenshot is taken.

## Device Environment
- The target device is rooted with KernelSU.
- LSPosed is installed.
- Shizuku is available.
- Device: POCO F1 (`beryllium`).
- ROM: crDroid 11.9, LineageOS-derived.
- Android version: 15.
- This materially changes feasibility: private APIs and framework hooks are now realistic, even if they remain non-portable and version-sensitive.

## Confirmed Device-Specific Findings
- `SystemUI.apk` lives at `/system_ext/priv-app/SystemUI/SystemUI.apk`.
- `TakeScreenshotService` is declared in `AndroidManifest.xml` and runs in the `:screenshot` process.
- This build contains the expected screenshot classes:
  - `TakeScreenshotService`
  - `TakeScreenshotExecutor`
  - `TakeScreenshotExecutorImpl`
  - `ImageCaptureImpl`
  - `PolicyRequestProcessor`
  - `ScreenshotController`
  - `ScreenshotShelfViewProxy`
  - `ScreenshotWindow`
- `ImageCaptureImpl.captureDisplay(int, Rect)` is confirmed to:
  - build `new ScreenCapture.CaptureArgs.Builder().setSourceCrop(rect).build()`
  - call `IWindowManager.captureDisplay(displayId, captureArgs, listener)`
  - return `listener.getBuffer()?.asBitmap()`
- `screenshot_shelf.xml` contains `@id/screenshot_preview_border` using `@drawable/overlay_border`.
- `ScreenshotWindow` is a dedicated screenshot UI window titled `ScreenshotUI` and created with system window type `0x7f4`.
- Framework inspection on this ROM confirms:
  - `WindowManager.LayoutParams.TYPE_SCREENSHOT = 2036`
  - `ScreenCapture.CaptureArgs.Builder.setExcludeLayers(...)` exists
  - `ScreenCapture.captureLayersExcluding(...)` exists
  - `DisplayContent.getLayerCaptureArgs(Set<Integer>)` excludes windows by type by collecting their `SurfaceControl`s
  - `WindowManagerService.takeAssistScreenshot(Set<Integer>)` feeds that exclusion set into layer capture
  - `IWindowManager` does not directly expose `takeAssistScreenshot(Set<Integer>)`
- On-device LSPosed prototype results confirm:
  - the module loads in `com.android.systemui:screenshot`
  - `ScreenshotShelfViewProxy` can recolor `screenshot_preview_border` red
  - `ImageCaptureImpl.captureDisplay(...)` can be intercepted successfully
  - on the first screenshot, the screenshot shelf is not yet attached when capture runs
  - on a rapid follow-up screenshot while the previous shelf is still visible, `PhoneWindow.getRootSurfaceControl()` returns a live attached root and the hook can call `setExcludeLayers(...)`
  - two saved screenshots from that repeated-shot test were byte-identical, which strongly indicates the previous visible screenshot shelf was excluded from the second saved image
  - the visible shelf-clearing problem comes from `ScreenshotController.handleScreenshot(...)` calling `ScreenshotShelfViewProxy.reset()` before the new screenshot is fully ready
  - the current UX mitigation path is:
    - on screenshot N+1, the module snapshots screenshot N's currently visible shelf with `PixelCopy`
    - it displays that snapshot in a temporary transparent overlay positioned over the original shelf
    - this covers the stock teardown/rebuild gap while the new shelf is prepared underneath
  - a stacked-shelf prototype is also now implemented inside the stock shelf:
    - `screenshot_preview_blur` is reused as the first rear card
    - one extra synthetic rear card is inserted behind it for 3-shot bursts
    - `screenshot_badge` is repurposed as a count badge while the batch is active
    - the stack survives stock `removeWindow()` during screenshot reentry and resets on non-reentry dismissal/timeout
  - LSPosed logs on-device confirm that the continuity overlay is added during screenshot N+1 on this ROM

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
- For Android 15 specifically, the likely hook surface is SystemUI's screenshot pipeline rather than the older pre-refactor controller classes.
- On this specific build, the `com.android.systemui:screenshot` process is the primary hook target.
- The lowest-risk first prototype is now: hook the screenshot process only, fetch the screenshot window's live `SurfaceControl`, and inject it into `CaptureArgs.setExcludeLayers(...)` before calling the existing `IWindowManager.captureDisplay(...)`.
- The prototype has now validated that this works for screenshot N+1 on crDroid 15 when the prior screenshot shelf is already attached.

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
- On Android 15, also consider reusing the existing SystemUI screenshot shelf UI instead of drawing a second independent overlay. That minimizes flicker risk and makes it easy to add a red diagnostic border while testing hooks.

## Recommended Next Investigation
- Because the device is rooted, audit SystemUI/framework screenshot flow first.
- Identify the exact classes/methods used on the target Android version for screenshot initiation and capture arg construction.
- Build a tiny rooted spike that proves one of these:
  - an LSPosed hook can exclude a known overlay layer from capture
  - a Shizuku-backed privileged call can capture while excluding that layer
- This LSPosed proof now exists for the stock `ScreenshotUI` window on screenshot N+1.
- Keep the secure-overlay test as a fallback, not the primary plan.
- Inspect the real crDroid/Lineage `SystemUI.apk` to confirm whether AOSP class names still match the expected Android 15 pipeline.
- Next concrete check: inspect framework and services jars on-device to see which window types or titles can be excluded through the window manager capture path, and whether `ScreenshotUI` can be filtered wholesale.
- Next concrete implementation task: harden the prototype for production use, reduce diagnostic logging, and verify the remaining UX requirement that the previous shelf stays continuously visible with no blink during screenshot N+1.
- Next concrete implementation task: validate the new stacked-shelf rendering on-device for 2-shot and 3-shot bursts and decide the first batch interaction model.

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
