package dev.duda.screenshotdroid;

import android.graphics.Bitmap;
import android.view.WindowManager;
import android.view.View;
import java.lang.ref.WeakReference;

final class HookState {
    private static final long REENTRY_GRACE_MS = 1500L;
    private static volatile WeakReference<Object> screenshotWindowRef = new WeakReference<>(null);
    private static volatile WeakReference<View> screenshotShelfViewRef = new WeakReference<>(null);
    private static volatile WeakReference<View> continuityOverlayViewRef = new WeakReference<>(null);
    private static volatile WeakReference<WindowManager> continuityOverlayWindowManagerRef =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> lastScreenshotDataRef = new WeakReference<>(null);
    private static volatile WeakReference<Bitmap> lastPreviewBitmapRef = new WeakReference<>(null);
    private static volatile boolean suppressNextShelfReset;
    private static volatile boolean suppressNextWindowRemoval;
    private static volatile long reentryArmedAtMs;

    private HookState() {
    }

    static void setScreenshotWindow(Object screenshotWindow) {
        screenshotWindowRef = new WeakReference<>(screenshotWindow);
    }

    static void clearScreenshotWindow(Object screenshotWindow) {
        Object current = screenshotWindowRef.get();
        if (current == screenshotWindow) {
            screenshotWindowRef = new WeakReference<>(null);
        }
    }

    static Object getScreenshotWindow() {
        return screenshotWindowRef.get();
    }

    static void setScreenshotShelfView(View view) {
        screenshotShelfViewRef = new WeakReference<>(view);
    }

    static View getScreenshotShelfView() {
        return screenshotShelfViewRef.get();
    }

    static void setContinuityOverlay(View view, WindowManager windowManager) {
        continuityOverlayViewRef = new WeakReference<>(view);
        continuityOverlayWindowManagerRef = new WeakReference<>(windowManager);
    }

    static View getContinuityOverlayView() {
        return continuityOverlayViewRef.get();
    }

    static WindowManager getContinuityOverlayWindowManager() {
        return continuityOverlayWindowManagerRef.get();
    }

    static void clearContinuityOverlay() {
        continuityOverlayViewRef = new WeakReference<>(null);
        continuityOverlayWindowManagerRef = new WeakReference<>(null);
    }

    static void setLastScreenshotData(Object screenshotData) {
        lastScreenshotDataRef = new WeakReference<>(screenshotData);
    }

    static Object getLastScreenshotData() {
        return lastScreenshotDataRef.get();
    }

    static void setLastPreviewBitmap(Bitmap bitmap) {
        lastPreviewBitmapRef = new WeakReference<>(bitmap);
    }

    static Bitmap getLastPreviewBitmap() {
        return lastPreviewBitmapRef.get();
    }

    static void armSuppressNextShelfReset() {
        reentryArmedAtMs = android.os.SystemClock.uptimeMillis();
        suppressNextShelfReset = true;
        suppressNextWindowRemoval = true;
    }

    static void armReentryGrace() {
        reentryArmedAtMs = android.os.SystemClock.uptimeMillis();
    }

    static Object getContinuityOverlaySurface() {
        View overlayView = continuityOverlayViewRef.get();
        if (overlayView == null) {
            return null;
        }
        Object viewRootImpl = ReflectionHelpers.callMethodIfExists(overlayView, "getViewRootImpl");
        if (viewRootImpl == null) {
            return null;
        }
        Object directSurface = ReflectionHelpers.callMethodIfExists(viewRootImpl, "getSurfaceControl");
        if (directSurface != null) {
            return directSurface;
        }
        return ReflectionHelpers.getObjectFieldIfExists(viewRootImpl, "mSurfaceControl");
    }

    static boolean consumeSuppressNextShelfReset() {
        if (!isReentryActive()) {
            clearSuppressNextShelfReset();
            return false;
        }
        if (!suppressNextShelfReset) {
            return false;
        }
        suppressNextShelfReset = false;
        return true;
    }

    static boolean consumeSuppressNextWindowRemoval() {
        if (!isReentryActive()) {
            clearSuppressNextShelfReset();
            return false;
        }
        if (!suppressNextWindowRemoval) {
            return false;
        }
        suppressNextWindowRemoval = false;
        return true;
    }

    static void clearSuppressNextShelfReset() {
        reentryArmedAtMs = 0L;
        suppressNextShelfReset = false;
        suppressNextWindowRemoval = false;
    }

    static boolean isReentryGraceActive() {
        return isReentryActive();
    }

    private static boolean isReentryActive() {
        long armedAt = reentryArmedAtMs;
        return armedAt != 0L && android.os.SystemClock.uptimeMillis() - armedAt <= REENTRY_GRACE_MS;
    }
}
