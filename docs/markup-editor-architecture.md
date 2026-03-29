# Markup Editor Architecture

## Current decision
- The first editor version is a separate activity inside the LSPosed module APK, launched from the hooked SystemUI screenshot shelf.
- Short tap on the screenshot preview opens the editor for the latest saved screenshot `Uri`.
- Long press still falls through to the stock SystemUI preview action path.

## Why this shape
- It keeps screenshot capture and shelf interaction inside SystemUI, where the rooted hook already owns the gesture flow.
- It keeps the actual editor in the module app process, which is a safer place to build custom UI than the hooked SystemUI process.
- It gives a clean place to grow real editing tools without turning `ScreenshotHooks.java` into a UI framework.

## First-cut UI
- Full-screen editor activity.
- Top-left `Done` button that simply closes the activity.
- Dedicated screenshot stage that only renders the screenshot itself, not the shelf chrome.
- Disabled bottom tool rail as a placeholder for the next actual editing controls.
- Static crop handles are drawn to preview the intended cropping affordance.

## Data model direction
- Use a native custom Android view for rendering and interaction, not a web view.
- Keep layout in XML and keep editing state in a structured Java/Kotlin-side document model.
- Prefer a simple serializable edit document, likely JSON, for strokes, shapes, crop rect, and future metadata.
- Treat the rendered bitmap as an export target, not as the only source of truth.

## Scripting / extension decision
- Do not introduce Lua or JS in the first implementation.
- The core risks right now are gesture fidelity, screenshot persistence, and export correctness, not editor extensibility.
- If scripting becomes useful later, it should sit on top of a stable edit-document model rather than replacing it.
- A small JSON-described tool/layout configuration is much lower-risk than embedding a scripting runtime this early.

## Near-term roadmap
- Add share action in the upper-right corner.
- Add real drawing tools and shape placement.
- Add draggable crop bounds.
- Add explicit save/delete flow on `Done`.
- Decide whether multi-screenshot batches should be navigable inside the editor or handled one image at a time first.
