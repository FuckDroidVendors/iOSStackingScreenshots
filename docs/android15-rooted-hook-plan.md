# Android 15 Rooted Hook Plan

## Target
- Device: POCO F1 (`beryllium`)
- ROM: crDroid 11.9
- Base: LineageOS-derived
- Android: 15
- Root/tooling: KernelSU, LSPosed, Shizuku

## Goal
- Intercept the stock Android screenshot flow.
- Keep the screenshot preview visible on screen across repeated screenshots.
- Ensure the preview layer is excluded from future screenshots.
- Add a red border around the floating screenshot while the hook-based path is active.

## Best Current Strategy
- Reuse the stock SystemUI screenshot experience instead of building an unrelated overlay stack first.
- Hook the Android 15 SystemUI screenshot pipeline with LSPosed.
- Change the actual capture step so the preview layer is excluded by layer, not by hiding the UI.
- Mark the hooked mode visually by recoloring the existing screenshot preview border to red.

## Why This Is The Best Fit
- The stock screenshot pipeline already handles gesture/key-chord initiation, sound, save/share/edit flow, dismissal, and animation timing.
- Reusing the existing screenshot shelf reduces UI work and reduces flicker risk.
- Android internal capture APIs already support excluded layers, which is the capability the X11 compositor plugin relied on conceptually.
- LSPosed is a better fit than a standalone app for this because the hook target is inside SystemUI and framework code.

## Relevant Android 15 AOSP Flow

### Entry point
- `TakeScreenshotService` receives screenshot requests from the system.

### Dispatcher
- `TakeScreenshotExecutor` or `TakeScreenshotExecutorImpl` selects the display and hands the request to the active screenshot handler.

### Request shaping
- `RequestProcessor` / `ScreenshotRequestProcessor` can rewrite screenshot requests before capture.

### Capture
- `ImageCaptureImpl.captureDisplay(...)` calls `IWindowManager.captureDisplay(...)` using `ScreenCapture.CaptureArgs`.

### UI
- `ScreenshotShelfViewProxy` controls the screenshot shelf view shown to the user.
- That code references `R.id.screenshot_preview_border`, which is a direct anchor for the requested red border indicator.

## Confirmed crDroid 11.9 Findings
- The actual device `SystemUI.apk` confirms all of these classes are present on-device, not just in AOSP references.
- `TakeScreenshotService` is declared in `AndroidManifest.xml` with `android:process=":screenshot"`.
- `ImageCaptureImpl.captureDisplay(int, Rect)` is confirmed to call `IWindowManager.captureDisplay(...)` with a `ScreenCapture.CaptureArgs.Builder`.
- `ScreenshotWindow` is real on this build and hosts the screenshot UI in a dedicated window titled `ScreenshotUI`.
- `screenshot_shelf.xml` contains the existing preview border view `@id/screenshot_preview_border`.
- `WindowManager.LayoutParams.TYPE_SCREENSHOT = 2036` on this device framework.
- `ScreenCapture.CaptureArgs.Builder.setExcludeLayers(...)` is present in the device framework.
- `DisplayContent.getLayerCaptureArgs(Set<Integer>)` excludes windows by collecting matching windows' `SurfaceControl`s.
- `IWindowManager` does not directly expose the typed `takeAssistScreenshot(Set<Integer>)` path; it does expose `captureDisplay(...)`.
- Live-device prototype findings:
  - the LSPosed module loads in `com.android.systemui:screenshot`
  - `ScreenshotShelfViewProxy` border recolor works
  - on screenshot N, the new screenshot shelf is not yet attached when `captureDisplay(...)` runs
  - on screenshot N+1, the previous screenshot shelf is attached and reachable through `PhoneWindow.getRootSurfaceControl()`
  - the hook can rebuild capture args with `setExcludeLayers(...)` and successfully intercept `captureDisplay(...)`
  - the saved outputs from the repeated-shot test were byte-identical, strongly indicating the previous screenshot shelf was excluded from screenshot N+1

## Hook Candidates

### Candidate 1: Hook `ImageCaptureImpl.captureDisplay(...)`
- Replace or wrap the call so capture args are built with excluded layers.
- Advantage: narrow hook close to the actual screenshot capture step.
- Risk: may still need a way to resolve the preview surface into a `SurfaceControl` or compatible handle.
- Status on this build: confirmed method exists exactly where expected.
- New status: this is now the preferred first implementation path.

### Candidate 2: Hook lower at `IWindowManager.captureDisplay(...)` call sites
- Intercept the call and redirect to hidden/internal layer-capture APIs.
- Advantage: centralizes capture behavior.
- Risk: binder interfaces and argument shapes are more fragile and harder to patch cleanly from LSPosed.
- On this build, this is probably unnecessary for the first prototype because `CaptureArgs` itself already supports `setExcludeLayers(...)`.

### Candidate 3: Hook framework screenshot capture arg construction
- Intercept the framework path that creates `LayerCaptureArgs` or equivalent internal capture args and inject the preview layer into `excludeLayers`.
- Advantage: most faithful to Android's own internal exclusion model.
- Risk: this may require framework-process hooks rather than SystemUI-only hooks.

## Preview Layer Ownership

### Preferred
- Keep the preview inside stock SystemUI screenshot UI and identify its underlying window/surface.
- Because this build uses a dedicated `ScreenshotWindow` titled `ScreenshotUI`, first test whether excluding that entire window is sufficient.
- If excluding the whole screenshot UI window works, it is cleaner than tracking child surfaces.
- Current best bet: use the attached `ScreenshotWindow` decor view to retrieve its live `SurfaceControl`, then exclude that surface during the next capture.

### Fallback
- Create a separate privileged overlay window managed by the hook module.
- Track its `SurfaceControl` or window token.
- Exclude that surface from capture.
- This is mechanically simpler in some cases, but risks visual mismatch with stock screenshot UI.

## Red Border Plan
- Do not create a second debug overlay just for the border.
- Instead, modify the existing screenshot shelf border view inside SystemUI.
- The AOSP screenshot UI already exposes a preview border view via `R.id.screenshot_preview_border`.
- Hook that view after inflate/bind and recolor it red while hook mode is enabled.
- This gives a direct visual signal that the rooted hook path is active.

## Likely Implementation Shape

### LSPosed module
- Package hooks for:
  - `com.android.systemui`
  - possibly `android` / system_server if lower framework hooks are needed

### Shared config
- Small config store for:
  - hook enabled/disabled
  - red-border enabled
  - logging level
  - maybe selected capture strategy

### Optional companion app
- Provides settings UI, gallery/history, export/share, and diagnostics.
- Talks to the hook side over binder/content provider/file-based config.
- Shizuku can be used if the companion app needs privileged commands outside the hooked process.

## Concrete First Prototype
1. Hook the `com.android.systemui:screenshot` process.
2. Hook `ScreenshotShelfViewProxy` or nearby screenshot shelf UI code.
3. Confirm the border view can be recolored red reliably.
4. Hook `ImageCaptureImpl.captureDisplay(...)` and log when a screenshot is initiated.
5. Retrieve the live `SurfaceControl` for the attached `ScreenshotUI` window.
6. Rebuild the capture args with `setExcludeLayers(new SurfaceControl[]{ screenshotUiSurface })`.
7. Call the existing `IWindowManager.captureDisplay(...)`.
8. Validate repeated screenshots while the previous preview remains visible.

## Current Prototype Status
- Steps 1 through 7 are now implemented in the LSPosed proof-of-concept module.
- Step 8 is partially validated:
  - repeated screenshot capture used the attached previous `ScreenshotUI` surface
  - the saved screenshot pair from that test was byte-identical
- A first UX-focused reentry fix is now in place:
  - `ScreenshotController.handleScreenshot(...)` reentry arms a one-shot suppression of `ScreenshotShelfViewProxy.reset()`
  - on rapid screenshot N+1, that suppression fires while excluded-layer capture still works
  - immediate teardown was not observed in logs; `removeWindow()` happened later on normal timeout
- A second UX-focused reentry fix is now prototyped:
  - on screenshot N+1, capture the visible screenshot shelf with `PixelCopy`
  - place that snapshot into a short-lived continuity overlay window at the same bounds
  - let stock SystemUI tear down and rebuild underneath while the user keeps seeing the old shelf snapshot
- Remaining validation:
  - visually confirm there is no blink on the physical display during screenshot N+1
  - check whether excluding the whole `ScreenshotUI` window removes any stock controls that should remain

## Validation Criteria
- Taking screenshot N+1 does not visually hide screenshot N's preview.
- Screenshot N+1 does not contain screenshot N's preview.
- The preview remains interactive and visually stable.
- The red border appears only when the hook is active.
- No bootloop or SystemUI crash loop after enabling the module.

## Risks
- crDroid/Lineage may have renamed or refactored AOSP classes.
- The screenshot shelf may not correspond to a conveniently excludable surface.
- Hidden API signatures may differ across Android 15 builds.
- Excluding the whole screenshot UI window may remove more than just the preview, which could or could not be acceptable.
- Some strategies may require hooks in both SystemUI and system_server.

## Immediate Next Step
- `SystemUI.apk` inspection is done.
- Framework/services inspection is done.
- First LSPosed module is built and verified on-device.
- Next: polish the prototype into a more robust module and validate the remaining UX details on the physical display.
