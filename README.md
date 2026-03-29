# iOS Stacking Screenshots


https://github.com/user-attachments/assets/8a73b859-712e-423e-9079-88a1dacde5c5

LSPosed-based prototype for Android 15 screenshot UX on a rooted POCO F1 running crDroid 11.9.

Project goal:
- keep the screenshot shelf visible on screen across repeated screenshots
- exclude that visible shelf from screenshot N+1
- move toward an iOS-like stacked thumbnail UX without needing a full custom ROM

## Quick Install

```bash
./gradlew :app:assembleDebug
adb -s 192.168.2.56:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.2.56:5555 shell su -c 'killall com.android.systemui'
```

Then:
- open LSPosed
- enable `iOS Stacking Screenshots` for `com.android.systemui`
- take two screenshots in quick succession
- confirm the old shelf stays visible on-screen and does not appear in screenshot `N+1`

## Build And Install

Build:

```bash
./gradlew :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install to the current device:

```bash
adb -s 192.168.2.56:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Reload the hook immediately:

```bash
adb -s 192.168.2.56:5555 shell su -c 'killall com.android.systemui'
```

This session successfully:
- built `:app:assembleDebug`
- installed the debug APK to `192.168.2.56:5555`
- restarted `SystemUI`

## Zero-To-Active ADB Guide

This is the full deploy path from a clean device state using ADB.

Prerequisites:
- the device is rooted
- LSPosed is already installed
- ADB over USB or TCP is working
- the LSPosed manager app is installed on the device

1. Build the module:

```bash
./gradlew :app:assembleDebug
```

2. Install or update the APK:

```bash
adb -s 192.168.2.56:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

3. If you renamed the package or are replacing an old module build, remove the obsolete package:

```bash
adb -s 192.168.2.56:5555 uninstall OLD_PACKAGE_NAME
```

4. Enable the module in LSPosed and add `com.android.systemui` to its scope.

Manual path in LSPosed Manager:
- open LSPosed Manager
- open `iOS Stacking Screenshots`
- enable the module
- set scope to `com.android.systemui`

ADB/root path used in this repo during this session:

```bash
adb -s 192.168.2.56:5555 shell "su -c 'echo \"begin; update modules set enabled=1 where module_pkg_name=\\\"fuck.iosstackingscreenshots.droidvendorssuck\\\"; insert or ignore into scope(mid, app_pkg_name, user_id) values((select mid from modules where module_pkg_name=\\\"fuck.iosstackingscreenshots.droidvendorssuck\\\"), \\\"com.android.systemui\\\", 0); commit;\" | sqlite3 /data/adb/lspd/config/modules_config.db'"
```

5. Restart `SystemUI` so the screenshot process respawns under the updated LSPosed scope:

```bash
adb -s 192.168.2.56:5555 shell su -c 'killall com.android.systemui'
```

6. Verify LSPosed state if needed:

```bash
adb -s 192.168.2.56:5555 shell "su -c 'echo \"select * from modules where module_pkg_name=\\\"fuck.iosstackingscreenshots.droidvendorssuck\\\"; select * from scope where mid=(select mid from modules where module_pkg_name=\\\"fuck.iosstackingscreenshots.droidvendorssuck\\\");\" | sqlite3 /data/adb/lspd/config/modules_config.db'"
```

Expected result:
- the module row shows `enabled=1`
- scope includes `com.android.systemui|0`

7. Verify behavior:
- take a screenshot
- take a second screenshot while the first shelf is still visible
- tap the screenshot preview to confirm the current tap-to-toast debug hook is loaded
- confirm the previous shelf remains visible on-screen and is absent from screenshot `N+1`

When `killall com.android.systemui` is enough:
- normal code changes inside the module
- enabling the module or changing its scope
- reinstalling a new APK over the same package

When a reboot may still be useful:
- LSPosed manager UI looks stale after a package rename
- Zygisk/LSPosed itself was just updated
- `SystemUI` restart did not pick up the new module state
- you suspect a cached or half-dead process outside `com.android.systemui`

## Current Status

What is already working on the target device:
- hook loads in `com.android.systemui:screenshot`
- the stock screenshot preview can be restyled, proving the hook is in the right process
- `ImageCaptureImpl.captureDisplay(...)` is intercepted
- the attached `ScreenshotUI` surface is injected into `ScreenCapture.CaptureArgs.Builder.setExcludeLayers(...)`
- repeated-shot testing showed byte-identical saved outputs when screenshot N+1 was taken while screenshot N's shelf was visible
- the stock screenshot timeout is overridden to `5000 ms`
- stacked-shelf rendering for burst screenshots exists as a prototype
- left-swipe dismissal now tracks a screenshot batch and deletes already-saved files plus late export completions from that same dismissed batch

What is still not fully closed out:
- visual confirmation that there is no blink on the physical display for every entry path
- cleanup of diagnostic logging and hardening of runtime guards
- deciding how much stock screenshot UI to keep versus replacing with custom interactions

## Why Root / LSPosed

Public Android APIs are close, but not enough for the exact requirement.

Relevant Android docs:
- https://developer.android.com/about/versions/14/behavior-changes-14#media-projection-consent
- https://developer.android.com/about/versions/14/features/app-screen-sharing
- https://developer.android.com/security/fraud-prevention/activities#flag_secure
- https://developer.android.com/reference/android/view/View#setContentSensitivity(int)
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY

Important platform constraints:
- `MediaProjection` requires user consent for each capture session on Android 14+.
- Android app screen sharing can exclude system UI and capture only one app window, but that is not the same thing as "capture full display except my overlay".
- `FLAG_SECURE` prevents the secure window from appearing in screenshots, but it blanks protected content instead of revealing the pixels underneath.
- `View.setContentSensitivity(...)` hides marked content during screen sharing, again aimed at protection rather than compositor-style subtraction.
- A normal app overlay uses `TYPE_APPLICATION_OVERLAY`, but public docs do not expose any full-display screenshot exclusion list for arbitrary app layers.

That is why this repo targets the rooted/system path: hook SystemUI's real screenshot pipeline and inject excluded layers directly.

## Current Architecture

- Target process: `com.android.systemui:screenshot`
- Entry point: [HookEntry.java](app/src/main/java/fuck/iosstackingscreenshots/droidvendorssuck/HookEntry.java)
- Main hook logic: [ScreenshotHooks.java](app/src/main/java/fuck/iosstackingscreenshots/droidvendorssuck/ScreenshotHooks.java)
- Runtime state: [HookState.java](app/src/main/java/fuck/iosstackingscreenshots/droidvendorssuck/HookState.java)

Current strategy:
1. Let stock SystemUI own the screenshot shelf.
2. Resolve the live `ScreenshotUI` surface from the attached screenshot window.
3. Intercept `ImageCaptureImpl.captureDisplay(...)`.
4. Rebuild capture args with `setExcludeLayers(...)`.
5. Keep burst state in the hook so repeated screenshots can form a stack and support batch actions.

## iOS-Inspired Feature Map

Features observed from iOS screenshot UX and how they map to this project:

- Swipe left to dismiss without saving
  - Status: mostly implemented in the rooted prototype.
  - Notes: left-swipe explicit dismissal now deletes the active batch's saved `Uri`s and late exports for that batch. This still needs on-device UX validation.

- Auto close after 5 seconds while still saving screenshots
  - Status: implemented.
  - Notes: the hook overrides SystemUI's stock screenshot timeout to `5000 ms`.

- Share screenshot menu on long press
  - Status: not implemented.
  - Feasibility: easy to medium.
  - Notes: the current hook hides stock chrome, so this is mainly a UX decision. It can be done either by restoring selected stock actions or by attaching a custom long-press menu to the shelf.

- Markup mode on single tap
  - Status: not implemented.
  - Feasibility: easy to medium.
  - Notes: Android's stock screenshot flow already has edit/share affordances in SystemUI. Rewiring single tap to launch markup for the latest screenshot should be straightforward from the current hook position.

- Swipe horizontally through screenshots in a stack
  - Status: not implemented.
  - Feasibility: medium.
  - Notes: the hook already maintains stack state and saved `Uri`s, so this is mostly gesture handling plus view-state switching.

- Drag screenshot into another app / onto an app icon / paste
  - Status: not implemented.
  - Feasibility: medium to hard.
  - Notes: Android has drag-and-drop APIs, but cross-app drag from a transient SystemUI shelf is a bigger integration task than the other items. It is plausible with LSPosed/SystemUI control, but it is not a small follow-up patch.

Practical completion view:
- the hard platform problem, "visible shelf but excluded from screenshot N+1", is already prototyped
- the remaining iOS-style interactions are mostly product/UI work on top of that rooted capture path
- among those, single-tap edit, long-press share, and swipe-through-stack are the most straightforward next additions

## Repository Notes

Read these before substantial work:
- [TODO.md](TODO.md)
- [WORKLOG.md](WORKLOG.md)
- [docs/android-screenshot-research.md](docs/android-screenshot-research.md)
- [docs/android15-rooted-hook-plan.md](docs/android15-rooted-hook-plan.md)
- [docs/lsposed-hook-blueprint.md](docs/lsposed-hook-blueprint.md)

## Target Environment

- Device: POCO F1 (`beryllium`)
- ROM: crDroid 11.9
- Android: 15
- Root/tooling: KernelSU, LSPosed, Shizuku
- Module package: `fuck.iosstackingscreenshots.droidvendorssuck`
