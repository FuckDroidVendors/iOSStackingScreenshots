package dev.duda.screenshotdroid;

import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class ScreenshotHooks {
    private static final String TAG = "ScreenshotDroid";
    private static volatile boolean installed;

    private ScreenshotHooks() {
    }

    static synchronized void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        installed = true;
        log("installing hooks in com.android.systemui:screenshot");
        hookScreenshotShelfViewProxy(classLoader);
        hookScreenshotController(classLoader);
        hookScreenshotWindow(classLoader);
        hookImageCapture(classLoader);
    }

    private static void hookScreenshotShelfViewProxy(ClassLoader classLoader) {
        Class<?> proxyClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.screenshot.ScreenshotShelfViewProxy", classLoader);
        if (proxyClass == null) {
            log("ScreenshotShelfViewProxy not found");
            return;
        }
        XposedBridge.hookAllConstructors(proxyClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View shelfView = (View) ReflectionHelpers.getObjectFieldIfExists(param.thisObject, "view");
                if (shelfView == null) {
                    log("ScreenshotShelfViewProxy constructed but view field was null");
                    return;
                }
                HookState.setScreenshotShelfView(shelfView);
                log("ScreenshotShelfViewProxy constructed; tinting preview border");
                tintPreviewBorder(shelfView);
            }
        });
    }

    private static void hookScreenshotController(ClassLoader classLoader) {
        Class<?> controllerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.screenshot.ScreenshotController", classLoader);
        if (controllerClass == null) {
            log("ScreenshotController not found");
            return;
        }
        XposedBridge.hookAllConstructors(controllerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object screenshotWindow = ReflectionHelpers.getObjectFieldIfExists(param.thisObject, "window");
                if (screenshotWindow != null) {
                    HookState.setScreenshotWindow(screenshotWindow);
                    log("cached ScreenshotWindow from ScreenshotController");
                } else {
                    log("ScreenshotController constructed but window field was null");
                }
            }
        });
    }

    private static void hookScreenshotWindow(ClassLoader classLoader) {
        Class<?> windowClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.screenshot.ScreenshotWindow", classLoader);
        if (windowClass == null) {
            log("ScreenshotWindow not found");
            return;
        }
        XposedBridge.hookAllMethods(windowClass, "removeWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                log("ScreenshotWindow.removeWindow called; clearing cached window");
                HookState.clearScreenshotWindow(param.thisObject);
            }
        });
    }

    private static void hookImageCapture(ClassLoader classLoader) {
        Class<?> imageCaptureClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.screenshot.ImageCaptureImpl", classLoader);
        if (imageCaptureClass == null) {
            log("ImageCaptureImpl not found");
            return;
        }
        XposedHelpers.findAndHookMethod(imageCaptureClass, "captureDisplay", int.class, Rect.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        log("ImageCaptureImpl.captureDisplay called");
                        Object screenshotSurface = resolveScreenshotSurface();
                        if (screenshotSurface == null) {
                            log("No screenshot surface available; falling back to original capture");
                            return;
                        }
                        Integer displayId = (Integer) param.args[0];
                        Rect crop = (Rect) param.args[1];
                        Object result = invokeCaptureWithExcludedLayers(param.thisObject, displayId, crop,
                                screenshotSurface);
                        if (result != null) {
                            param.setResult(result);
                        }
                    }
                });
    }

    private static void tintPreviewBorder(View shelfView) {
        int borderId = shelfView.getResources()
                .getIdentifier("screenshot_preview_border", "id", "com.android.systemui");
        if (borderId == 0) {
            log("screenshot_preview_border id not found");
            return;
        }
        View border = shelfView.findViewById(borderId);
        if (border == null) {
            log("screenshot_preview_border view not found");
            return;
        }
        border.setBackgroundColor(Color.RED);
        log("screenshot_preview_border tinted red");
    }

    private static Object resolveScreenshotSurface() {
        Object screenshotWindow = HookState.getScreenshotWindow();
        if (screenshotWindow == null) {
            log("resolveScreenshotSurface: no cached ScreenshotWindow");
            return null;
        }

        Object phoneWindow = ReflectionHelpers.getObjectFieldIfExists(screenshotWindow, "window");
        if (phoneWindow == null) {
            log("resolveScreenshotSurface: ScreenshotWindow.window was null");
            return null;
        }
        Object attachedSurfaceControl = ReflectionHelpers.callMethodIfExists(phoneWindow,
                "getRootSurfaceControl");
        if (attachedSurfaceControl != null) {
            log("resolveScreenshotSurface: got root surface from PhoneWindow: "
                    + attachedSurfaceControl.getClass().getName());
            Object directSurface = ReflectionHelpers.callMethodIfExists(attachedSurfaceControl,
                    "getSurfaceControl");
            if (isValidSurface(directSurface)) {
                log("resolveScreenshotSurface: got SurfaceControl directly from attached root surface");
                return directSurface;
            }
        }

        if (attachedSurfaceControl == null) {
            View shelfView = HookState.getScreenshotShelfView();
            if (shelfView != null) {
                log("resolveScreenshotSurface: shelf view attached=" + shelfView.isAttachedToWindow());
                attachedSurfaceControl = ReflectionHelpers.callMethodIfExists(shelfView,
                        "getRootSurfaceControl");
                if (attachedSurfaceControl != null) {
                    log("resolveScreenshotSurface: got root surface from screenshot shelf view: "
                            + attachedSurfaceControl.getClass().getName());
                    Object directSurface = ReflectionHelpers.callMethodIfExists(attachedSurfaceControl,
                            "getSurfaceControl");
                    if (isValidSurface(directSurface)) {
                        log("resolveScreenshotSurface: got SurfaceControl from screenshot shelf root surface");
                        return directSurface;
                    }
                }
            } else {
                log("resolveScreenshotSurface: no cached screenshot shelf view");
            }
        }

        View decorView = (View) ReflectionHelpers.callMethodIfExists(phoneWindow, "peekDecorView");
        if (decorView == null) {
            decorView = (View) ReflectionHelpers.callMethodIfExists(phoneWindow, "getDecorView");
        }
        if (decorView != null) {
            log("resolveScreenshotSurface: decor view attached=" + decorView.isAttachedToWindow());
            Object viewRootImpl = ReflectionHelpers.callMethodIfExists(decorView, "getViewRootImpl");
            if (viewRootImpl != null) {
                log("resolveScreenshotSurface: decor view root=" + viewRootImpl.getClass().getName());
                Object directSurface = ReflectionHelpers.callMethodIfExists(viewRootImpl, "getSurfaceControl");
                if (isValidSurface(directSurface)) {
                    log("resolveScreenshotSurface: got SurfaceControl from ViewRootImpl.getSurfaceControl()");
                    return directSurface;
                }
                directSurface = ReflectionHelpers.getObjectFieldIfExists(viewRootImpl, "mSurfaceControl");
                if (isValidSurface(directSurface)) {
                    log("resolveScreenshotSurface: got direct mSurfaceControl from ViewRootImpl");
                    return directSurface;
                }
            } else {
                log("resolveScreenshotSurface: decor view has no ViewRootImpl");
            }

            if (attachedSurfaceControl == null) {
                attachedSurfaceControl = ReflectionHelpers.callMethodIfExists(decorView,
                        "getRootSurfaceControl");
                if (attachedSurfaceControl != null) {
                    log("resolveScreenshotSurface: got root surface from decor view: "
                            + attachedSurfaceControl.getClass().getName());
                    Object directSurface = ReflectionHelpers.callMethodIfExists(attachedSurfaceControl,
                            "getSurfaceControl");
                    if (isValidSurface(directSurface)) {
                        log("resolveScreenshotSurface: got SurfaceControl from decor root surface");
                        return directSurface;
                    }
                }
            }
        } else {
            log("resolveScreenshotSurface: no decor view available");
        }

        Object rootSurface = ReflectionHelpers.getObjectFieldIfExists(attachedSurfaceControl, "mSurfaceControl");
        if (isValidSurface(rootSurface)) {
            log("resolveScreenshotSurface: extracted valid mSurfaceControl from AttachedSurfaceControl");
            return rootSurface;
        }
        log("resolveScreenshotSurface: no valid surface control found");
        return null;
    }

    private static boolean isValidSurface(Object surfaceControl) {
        if (surfaceControl == null) {
            return false;
        }
        Object valid = ReflectionHelpers.callMethodIfExists(surfaceControl, "isValid");
        return valid instanceof Boolean && (Boolean) valid;
    }

    private static Object invokeCaptureWithExcludedLayers(Object imageCapture, int displayId, Rect crop,
            Object screenshotSurface) {
        try {
            Class<?> screenCaptureClass = Class.forName("android.window.ScreenCapture");
            Class<?> captureArgsBuilderClass =
                    Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
            Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");

            Object builder = XposedHelpers.newInstance(captureArgsBuilderClass);
            XposedHelpers.callMethod(builder, "setSourceCrop", crop);

            Object excludedSurfaces = Array.newInstance(surfaceControlClass, 1);
            Array.set(excludedSurfaces, 0, screenshotSurface);
            XposedHelpers.callMethod(builder, "setExcludeLayers", excludedSurfaces);

            Object captureArgs = XposedHelpers.callMethod(builder, "build");
            Object listener = XposedHelpers.callStaticMethod(screenCaptureClass, "createSyncCaptureListener");
            Object windowManager = ReflectionHelpers.getObjectFieldIfExists(imageCapture, "windowManager");
            XposedHelpers.callMethod(windowManager, "captureDisplay", displayId, captureArgs, listener);

            Object buffer = XposedHelpers.callMethod(listener, "getBuffer");
            if (buffer == null) {
                log("capture interception returned null buffer");
                return null;
            }
            log("captureDisplay intercepted with excluded screenshot surface");
            return XposedHelpers.callMethod(buffer, "asBitmap");
        } catch (Throwable t) {
            log("capture interception failed: " + t);
            return null;
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
