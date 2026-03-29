package fuck.iosstackingscreenshots.droidvendorssuck;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import android.view.View;
import android.view.WindowManager;

final class HookState {
    private static final String TAG = "iOSStackingShots";
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
    private static volatile Bitmap lastPreviewBitmap;
    private static volatile Uri lastSavedScreenshotUri;
    private static final ArrayList<Bitmap> previewStack = new ArrayList<>();
    private static final ArrayList<Uri> activeSavedScreenshotUris = new ArrayList<>();
    private static final IdentityHashMap<Object, Integer> exportBatchIds = new IdentityHashMap<>();
    private static volatile long reentryArmedAtMs;
    private static volatile boolean reentryPreviewBound;
    private static volatile int currentBatchId = 1;
    private static volatile int pendingDeletionBatchId = -1;

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
        lastPreviewBitmap = bitmap;
    }

    static Bitmap getLastPreviewBitmap() {
        return lastPreviewBitmap;
    }

    static void setLastSavedScreenshotUri(Uri uri) {
        lastSavedScreenshotUri = uri;
    }

    static Uri getLastSavedScreenshotUri() {
        return lastSavedScreenshotUri;
    }

    static synchronized void beginFreshBatch() {
        currentBatchId++;
        pendingDeletionBatchId = -1;
        activeSavedScreenshotUris.clear();
        exportBatchIds.clear();
        lastSavedScreenshotUri = null;
    }

    static synchronized int getCurrentBatchId() {
        return currentBatchId;
    }

    static synchronized void registerExportFuture(Object futureDelegate, int batchId) {
        if (futureDelegate == null) {
            return;
        }
        exportBatchIds.put(futureDelegate, batchId);
    }

    static synchronized boolean recordSavedScreenshotUri(Object futureDelegate, Uri uri) {
        if (uri == null) {
            return false;
        }
        Integer batchId = futureDelegate == null ? null : exportBatchIds.remove(futureDelegate);
        int resolvedBatchId = batchId != null ? batchId : currentBatchId;
        lastSavedScreenshotUri = uri;
        if (resolvedBatchId == pendingDeletionBatchId) {
            return true;
        }
        if (resolvedBatchId != currentBatchId) {
            return false;
        }
        if (!activeSavedScreenshotUris.contains(uri)) {
            activeSavedScreenshotUris.add(uri);
        }
        return false;
    }

    static synchronized List<Uri> markSavedBatchForDeletion() {
        pendingDeletionBatchId = currentBatchId;
        ArrayList<Uri> uris = new ArrayList<>(activeSavedScreenshotUris);
        activeSavedScreenshotUris.clear();
        return uris;
    }

    static synchronized void removeSavedScreenshotUri(Uri uri) {
        activeSavedScreenshotUris.remove(uri);
        if (uri != null && uri.equals(lastSavedScreenshotUri)) {
            lastSavedScreenshotUri = null;
        }
    }

    static synchronized void clearSavedScreenshotTracking() {
        pendingDeletionBatchId = -1;
        activeSavedScreenshotUris.clear();
        exportBatchIds.clear();
        lastSavedScreenshotUri = null;
    }

    static synchronized void pushStackBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            Log.i(TAG, "pushStackBitmap: source bitmap was null");
            return;
        }
        Log.i(TAG, "pushStackBitmap: source=" + describeBitmap(bitmap));
        Bitmap stackBitmap = createStackBitmap(bitmap);
        if (stackBitmap == null) {
            Log.i(TAG, "pushStackBitmap: failed to create stack bitmap");
            return;
        }
        Log.i(TAG, "pushStackBitmap: stored=" + describeBitmap(stackBitmap));
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
        lastPreviewBitmap = null;
        clearSavedScreenshotTracking();
    }

    static void armReentryGrace() {
        reentryArmedAtMs = android.os.SystemClock.uptimeMillis();
        reentryPreviewBound = false;
    }

    static void clearReentryGrace() {
        reentryArmedAtMs = 0L;
        reentryPreviewBound = false;
    }

    static void markReentryPreviewBound() {
        if (isReentryActive()) {
            reentryPreviewBound = true;
        }
    }

    static boolean hasReentryPreviewBound() {
        return reentryPreviewBound;
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
            Bitmap safeBitmap = bitmap;
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (safeBitmap == null) {
                    Log.i(TAG, "createStackBitmap: hardware copy returned null");
                    return null;
                }
            }
            if (safeBitmap.getWidth() == width && safeBitmap.getHeight() == height
                    && safeBitmap.getConfig() == Bitmap.Config.ARGB_8888 && !safeBitmap.isMutable()) {
                return safeBitmap;
            }
            Bitmap copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(copy);
            canvas.drawBitmap(safeBitmap, null, new android.graphics.Rect(0, 0, width, height), STACK_BITMAP_PAINT);
            return copy;
        } catch (Throwable t) {
            Log.i(TAG, "createStackBitmap: failed for " + describeBitmap(bitmap) + " error=" + t);
            return null;
        }
    }

    private static String describeBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return "null";
        }
        return bitmap.getWidth() + "x" + bitmap.getHeight()
                + " config=" + bitmap.getConfig()
                + " mutable=" + bitmap.isMutable()
                + " recycled=" + bitmap.isRecycled();
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
