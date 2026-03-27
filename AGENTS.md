# Repository Instructions

## Startup Checklist
- Run `git status --short` at the start of every session.
- Investigate uncommitted files before changing anything.
- Do not revert user work unless explicitly asked.
- If the work you completed is coherent and low-risk, try to make a sane atomic commit for your own changes.
- Read [TODO.md](/home/duda/screenshotdroid/TODO.md), [WORKLOG.md](/home/duda/screenshotdroid/WORKLOG.md), and [docs/android-screenshot-research.md](/home/duda/screenshotdroid/docs/android-screenshot-research.md) before doing substantial work.

## Project Goal
- Build an Android screenshot app with an iOS-like floating thumbnail/preview.
- The preview must stay visible on the display while new screenshots are taken.
- The preview must not appear in future screenshots.

## Current Technical Conclusion
- For a normal third-party app on stock Android, there is no public SDK path that cleanly says: "keep this overlay visible on screen, but exclude it from full-display screenshots while preserving the pixels underneath."
- `MediaProjection` is the public API for full-screen capture, but it does not expose per-layer exclusion.
- `FLAG_SECURE` and `View.setContentSensitivity(...)` can hide a window from screenshots/media projection, but that is not the same as compositor-level filtering of the overlay while revealing underlying app content.
- Android's internal/system capture stack does support excluded layers via hidden/private APIs. That is the closest match to the X11 compositor-plugin approach.

## Recommended Paths
- Stock app path: accept a compromised behavior, likely via `MediaProjection` plus an overlay and careful UX tradeoffs.
- System/OEM path: implement the preview as a privileged/system overlay and exclude its layer in the screenshot pipeline.
- If exact behavior is mandatory, bias toward AOSP/SystemUI/framework work, not a Play Store-only app architecture.

## Documentation Discipline
- Update [TODO.md](/home/duda/screenshotdroid/TODO.md) when priorities change.
- Append to [WORKLOG.md](/home/duda/screenshotdroid/WORKLOG.md) for each meaningful research or implementation session.
- Keep [docs/android-screenshot-research.md](/home/duda/screenshotdroid/docs/android-screenshot-research.md) current when findings change.
