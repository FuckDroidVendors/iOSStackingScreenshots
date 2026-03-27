package dev.duda.screenshotdroid;

import android.view.View;
import java.lang.ref.WeakReference;

final class HookState {
    private static volatile WeakReference<Object> screenshotWindowRef = new WeakReference<>(null);
    private static volatile WeakReference<View> screenshotShelfViewRef = new WeakReference<>(null);

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
}
