# TODO

- Verify with a real prototype whether a secure overlay produces black/empty pixels or preserved underlying content in screenshots and `MediaProjection`.
- Build a minimal Android spike using `MediaProjection` plus `TYPE_APPLICATION_OVERLAY` to measure practical limitations.
- Investigate a rooted implementation that drives screenshot capture through privileged/system APIs instead of public `MediaProjection`.
- Evaluate LSPosed hooks into SystemUI / framework screenshot flow to inject an excluded preview layer or temporarily redirect capture args.
- Evaluate a Shizuku-backed service for calling privileged screenshot or window-management APIs from the app process.
- Investigate whether Android 14+ app-window sharing is useful for a scoped variant that captures only the selected app window.
- Inspect AOSP/SystemUI screenshot flow in more detail and identify the smallest privileged patch that excludes the thumbnail overlay layer.
- Decide target product model:
  - stock unprivileged app with compromises
  - rooted app using Shizuku / LSPosed / hidden APIs
  - privileged app on rooted/device-owner builds
  - AOSP/SystemUI modification
- If Android implementation starts, add a concrete architecture doc and test matrix.
