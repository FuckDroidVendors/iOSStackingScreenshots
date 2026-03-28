# LSPosed Hook Blueprint

## Goal
- Implement the first rooted prototype entirely inside `com.android.systemui:screenshot`.
- Keep the existing screenshot UI visible.
- Exclude the existing screenshot UI window from the next screenshot by injecting its surface into `CaptureArgs.setExcludeLayers(...)`.
- Recolor the screenshot preview border red to indicate the hooked path is active.

## Confirmed Hook Targets

### Process
- `com.android.systemui:screenshot`

### UI classes
- `com.android.systemui.screenshot.ScreenshotController`
- `com.android.systemui.screenshot.ScreenshotShelfViewProxy`
- `com.android.systemui.screenshot.ScreenshotWindow`

### Capture classes
- `com.android.systemui.screenshot.ImageCaptureImpl`
- `com.android.systemui.screenshot.policy.PolicyRequestProcessor`

## Confirmed Framework APIs On Device
- `android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT = 2036`
- `android.window.ScreenCapture.CaptureArgs.Builder.setExcludeLayers(SurfaceControl[])`
- `android.view.Window.getRootSurfaceControl()`
- `android.view.View.getRootSurfaceControl()`
- `android.view.ViewRootImpl` contains `mSurfaceControl`

## First Prototype Strategy

### Step 1: UI proof-of-life
- Hook `ScreenshotShelfViewProxy` after construction.
- Find `R.id.screenshot_preview_border` in the inflated screenshot shelf.
- Change its background/tint to red.
- This confirms:
  - module is loaded in the correct process
  - the screenshot shelf hook point is stable on this ROM

### Step 2: Surface discovery
- Hook `ScreenshotController` after construction or after screenshot window attach.
- Read the `window` field of type `ScreenshotWindow`.
- From `ScreenshotWindow`, read the `window` field of type `PhoneWindow`.
- Call `window.getDecorView()` and/or `window.getRootSurfaceControl()`.
- Preferred surface path:
  - `Window.getRootSurfaceControl()` -> `AttachedSurfaceControl`
  - reflect `mSurfaceControl` from the concrete `ViewRootImpl`
- Fallback surface path:
  - `window.getDecorView().getViewRootImpl()` -> reflect `mSurfaceControl`

### Step 3: Capture interception
- Hook `ImageCaptureImpl.captureDisplay(int displayId, Rect crop)`.
- Recreate the original flow:
  - `new ScreenCapture.CaptureArgs.Builder()`
  - `setSourceCrop(crop)`
  - `setExcludeLayers(new SurfaceControl[]{ screenshotUiSurface })`
  - `build()`
  - `ScreenCapture.createSyncCaptureListener()`
  - `IWindowManager.captureDisplay(displayId, captureArgs, listener)`
  - `listener.getBuffer()?.asBitmap()`
- If no valid screenshot surface is currently attached, fall back to the original method.

## Why This Is The Best First Implementation
- It stays inside one process: `com.android.systemui:screenshot`.
- It reuses the existing SystemUI screenshot UI and animations.
- It avoids binder or hidden system_server hooks for the first prototype.
- It uses capabilities already confirmed on the real device framework.

## Practical Hook Details

### Candidate fields to reflect
- `ScreenshotController.window`
- `ScreenshotController.viewProxy`
- `ScreenshotWindow.window`
- `ViewRootImpl.mSurfaceControl`

### Candidate methods to hook
- `ScreenshotShelfViewProxy.<init>(...)`
- `ScreenshotController.<init>(...)`
- `ImageCaptureImpl.captureDisplay(int, Rect)`

### Runtime checks
- Surface must be non-null and valid.
- Exclusion should only be active when the screenshot shelf window is currently attached.
- If repeated screenshots happen while the shelf is visible, keep updating the cached surface reference.

## Pseudocode

```java
hook(ImageCaptureImpl.captureDisplay) {
    SurfaceControl screenshotSurface = HookState.currentScreenshotUiSurface;
    if (screenshotSurface == null || !screenshotSurface.isValid()) {
        return callOriginal();
    }

    ScreenCapture.CaptureArgs args =
        new ScreenCapture.CaptureArgs.Builder()
            .setSourceCrop(crop)
            .setExcludeLayers(new SurfaceControl[]{ screenshotSurface })
            .build();

    ScreenCapture.SynchronousScreenCaptureListener listener =
        ScreenCapture.createSyncCaptureListener();
    iWindowManager.captureDisplay(displayId, args, listener);
    ScreenCapture.ScreenshotHardwareBuffer buffer = listener.getBuffer();
    return buffer != null ? buffer.asBitmap() : null;
}
```

## Risks
- `CaptureArgs.Builder.setExcludeLayers(...)` may work differently for `captureDisplay(...)` than for `captureLayers(...)`, though the API exists on-device.
- Excluding the screenshot window may also exclude UI elements other than the preview. That may still be acceptable.
- `mSurfaceControl` reflection may be blocked or renamed on some future builds.
- Timing matters: the surface must remain attached when the next screenshot is captured.

## Fallback Paths
- Hook deeper and use hidden `ScreenCapture.DisplayCaptureArgs.Builder` if `CaptureArgs.Builder.setExcludeLayers(...)` is insufficient.
- Hook framework/server code if `IWindowManager.captureDisplay(...)` ignores excluded layers for this path.
- Exclude a child preview surface instead of the whole screenshot window if excluding the whole window removes too much UI.

## Success Criteria
- The red border appears around the screenshot preview.
- Screenshot N+1 does not contain the visible screenshot preview from screenshot N.
- The preview does not blink or disappear during repeated screenshots.

## Prototype Status On crDroid 15
- Red-border proof-of-life is confirmed.
- `ImageCaptureImpl.captureDisplay(...)` interception is confirmed.
- Surface timing behavior is now understood:
  - screenshot N cannot exclude its own freshly-created shelf because that shelf is not attached yet
  - screenshot N+1 can exclude screenshot N's already-attached `ScreenshotUI` surface
- Repeated-shot saved files from the device were byte-identical while the second shot used `setExcludeLayers(...)`, which strongly indicates the visible prior shelf was excluded successfully.
- Reentry UX mitigation now uses a continuity overlay:
  - on screenshot N+1, use `PixelCopy` to snapshot the visible old shelf
  - add a temporary transparent overlay window at the same position
  - remove it shortly after the new shelf is ready
- A stacked-shelf prototype is also now implemented in the stock shelf:
  - reuse `screenshot_preview_blur` as the first rear card
  - add one synthetic rear card for 3-shot bursts
  - repurpose `screenshot_badge` as a numeric count badge while the batch is active
  - preserve the batch across stock `removeWindow()` during screenshot reentry
- The remaining unverified pieces are still UX-level:
  - confirm on the physical display that the shelf never visually blinks during screenshot N+1 for both hardware-key and gesture screenshot paths
  - confirm that the stacked shelf looks correct for 2-shot and 3-shot bursts
