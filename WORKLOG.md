# Work Log

## 2026-03-27
- Inspected repository state. Found only one untracked file: [screenshot_plugin.c](/home/duda/screenshotdroid/screenshot_plugin.c).
- Read the existing X11 compositor plugin as the conceptual reference: it keeps a thumbnail visible while excluding it during subsequent captures by controlling composition.
- Investigated Android public APIs and AOSP internals.
- Conclusion: exact "visible overlay but filtered out from full-screen capture without flicker" behavior is not available to ordinary third-party apps through the public SDK.
- Documented the likely split between:
  - stock-app approach using `MediaProjection` and overlay windows, with compromises
  - system/OEM approach using private screenshot APIs with excluded layers
- Added repo instructions so future LLMs start by checking uncommitted files and keep documentation current.
- New constraint discovered: target device is rooted with KernelSU and also has LSPosed and Shizuku available.
- This makes rooted/privileged designs first-class options and substantially reduces the need to constrain the design to public SDK APIs.
