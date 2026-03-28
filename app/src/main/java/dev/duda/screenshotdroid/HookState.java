package dev.duda.screenshotdroid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import android.view.View;
import android.view.WindowManager;

final class HookState {
    private static final long REENTRY_GRACE_MS = 1500L;
    private static final int MAX_STACK_BITMAPS = 3;
    private static final int MAX_STACK_EDGE_PX = 640;
    private static final Paint STACK_BITMAP_PAINT =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private static volatile WeakReference<Object> screenshotWindowRef = new WeakReference<>(null);
    private static volatile WeakReference<View> screenshotShelfViewRef = new WeakReference<>(null);
    private static volatile WeakReference<View> continuityOverlayViewRef = new WeakReference<>(null);
    private static volatile WeakReference<WindowManager> continuityOverlayWindowManagerRef =
            new WeakReference<>(null);
    private static volatile WeakReference<Bitmap> lastPreviewBitmapRef = new WeakReference<>(null);
    private static final ArrayList<Bitmap> previewStack = new ArrayList<>();
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

    static void setLastPreviewBitmap(Bitmap bitmap) {
        lastPreviewBitmapRef = new WeakReference<>(bitmap);
    }

    static Bitmap getLastPreviewBitmap() {
        return lastPreviewBitmapRef.get();
    }

    static synchronized void pushStackBitmap(Bitmap bitmap) {
        Bitmap stackBitmap = createStackBitmap(bitmap);
        if (stackBitmap == null) {
            return;
        }
        previewStack.add(0, stackBitmap);
        while (previewStack.size() > MAX_STACK_BITMAPS) {
            recycleBitmap(previewStack.remove(previewStack.size() - 1));
        }
    }

    static synchronized List<Bitmap> getStackBitmaps() {
        return new ArrayList<>(previewStack);
    }

    static synchronized void clearPreviewStack() {
        for (Bitmap bitmap : previewStack) {
            recycleBitmap(bitmap);
        }
        previewStack.clear();
    }

    static void armReentryGrace() {
        reentryArmedAtMs = android.os.SystemClock.uptimeMillis();
    }

    static void clearReentryGrace() {
        reentryArmedAtMs = 0L;
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

    static boolean isReentryGraceActive() {
        return isReentryActive();
    }

    private static boolean isReentryActive() {
        long armedAt = reentryArmedAtMs;
        return armedAt != 0L && android.os.SystemClock.uptimeMillis() - armedAt <= REENTRY_GRACE_MS;
    }

    private static Bitmap createStackBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return null;
        }
        int maxEdge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        float scale = maxEdge > MAX_STACK_EDGE_PX ? (float) MAX_STACK_EDGE_PX / maxEdge : 1.0f;
        int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
        try {
            Bitmap copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(copy);
            canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, width, height), STACK_BITMAP_PAINT);
            return copy;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void recycleBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        try {
            bitmap.recycle();
        } catch (Throwable ignored) {
        }
    }
}
