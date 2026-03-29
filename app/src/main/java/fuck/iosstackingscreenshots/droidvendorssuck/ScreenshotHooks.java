package fuck.iosstackingscreenshots.droidvendorssuck;

import android.animation.AnimatorSet;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class ScreenshotHooks {
    private static final String TAG = "iOSStackingShots";
    private static final long CONTINUITY_OVERLAY_MS = 1200L;
    private static final long CONTINUITY_HANDOFF_MS = 96L;
    private static final int SCREENSHOT_TIMEOUT_MS = 15000;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Paint CARD_BITMAP_PAINT =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint CARD_CONTENT_BACKGROUND_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final String STACK_CARD_TAG_PREFIX = "IOSStackingShotsCard";
    private static final int IOS_FRAME_COLOR = Color.parseColor("#FFFFFF");
    private static final int IOS_FRAME_STROKE_COLOR = Color.parseColor("#D6D9DE");
    private static final int IOS_CARD_BACKGROUND_COLOR = Color.BLACK;
    private static final float STACK_CARD_X_OFFSET_DP = 2.0f;
    private static final float STACK_CARD_Y_OFFSET_DP = 1.0f;
    private static final float CARD_MAX_WIDTH_DP = 88.0f;
    private static final float CARD_MAX_HEIGHT_DP = 160.0f;
    private static final float CARD_FRAME_INSET_DP = 2.0f;
    private static final long STACK_UI_SETTLE_DELAY_MS = 16L;
    private static final long PREVIEW_CHOOSER_HOLD_MS = 500L;
    private static final Executor DIRECT_EXECUTOR = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };
    private static final ArrayList<Drawable> overlayStackCards = new ArrayList<>();
    private static volatile boolean installed;
    private static volatile WeakReference<View> activePreviewTouchViewRef = new WeakReference<>(null);
    private static volatile long activePreviewTouchDownMs;
    private static volatile boolean activePreviewLongPressTriggered;
    private static Runnable activePreviewLongPressRunnable;
    private static volatile WeakReference<View> lastPreviewClickTargetRef = new WeakReference<>(null);
    private static volatile long lastPreviewClickHeldMs;
    private static volatile long lastPreviewClickRecordedAtMs;
    private static Runnable continuityOverlayRemoval;

    static {
        CARD_CONTENT_BACKGROUND_PAINT.setColor(IOS_CARD_BACKGROUND_COLOR);
    }

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
        hookScreenshotCallbacks(classLoader);
        hookScreenshotShelfBinder(classLoader);
        hookPreviewTouchRouting(classLoader);
        hookPreviewActionModel(classLoader);
        hookScreenshotWindow(classLoader);
        hookImageExporter(classLoader);
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
                hideShelfChrome(shelfView);
                installPreviewTapToast(shelfView);
            }
        });
        XposedHelpers.findAndHookMethod(proxyClass, "createScreenshotDropInAnimation", Rect.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.args[1] = Boolean.FALSE;
                        if (!HookState.isReentryGraceActive()) {
                            return;
                        }
                        forcePreviewVisible(param.thisObject);
                        log("Skipping drop-in animation during screenshot reentry");
                        param.setResult(new AnimatorSet());
                    }
                });
        XposedBridge.hookAllMethods(proxyClass, "setScreenshot", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object screenshotData = param.args[0];
                Bitmap previousBitmap = HookState.getLastPreviewBitmap();
                if (previousBitmap != null && HookState.isReentryGraceActive()) {
                    HookState.pushStackBitmap(previousBitmap);
                }
                Object bitmap = ReflectionHelpers.getObjectFieldIfExists(screenshotData, "bitmap");
                if (bitmap instanceof Bitmap) {
                    HookState.setLastPreviewBitmap((Bitmap) bitmap);
                    log("Cached screenshot preview bitmap");
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object screenshotData = param.args[0];
                Object bitmap = ReflectionHelpers.getObjectFieldIfExists(screenshotData, "bitmap");
                HookState.markReentryPreviewBound();
                View shelfView = HookState.getScreenshotShelfView();
                if (shelfView != null) {
                    if (bitmap instanceof Bitmap) {
                        forceCurrentPreviewBitmap(shelfView, (Bitmap) bitmap);
                    }
                    hideShelfChrome(shelfView);
                    scheduleStackUiUpdate(shelfView);
                }
                removeContinuityOverlay(false);
            }
        });
        XposedBridge.hookAllMethods(proxyClass, "requestDismissal", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String eventName = String.valueOf(param.args[0]);
                if ("SCREENSHOT_EXPLICIT_DISMISSAL".equals(eventName)) {
                    Float velocity = param.args.length > 1 && param.args[1] instanceof Float
                            ? (Float) param.args[1] : null;
                    if (velocity != null && velocity.floatValue() < 0.0f) {
                        log("Deleting active screenshot batch after left-swipe dismissal");
                        deleteSavedScreenshotBatch(HookState.markSavedBatchForDeletion());
                    }
                }
                if ("SCREENSHOT_DISMISSED_OTHER".equals(eventName)) {
                    log("Ignoring stock dismissal event " + eventName);
                    param.setResult(null);
                }
            }
        });
        XposedBridge.hookAllMethods(proxyClass, "reset", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (HookState.isReentryGraceActive()) {
                    log("ScreenshotShelfViewProxy.reset() called during reentry grace");
                }
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
                Object timeoutHandler = ReflectionHelpers.getObjectFieldIfExists(param.thisObject, "screenshotHandler");
                if (timeoutHandler != null) {
                    XposedHelpers.setIntField(timeoutHandler, "mDefaultTimeout", SCREENSHOT_TIMEOUT_MS);
                    log("Set stock screenshot timeout to " + SCREENSHOT_TIMEOUT_MS + "ms");
                }
            }
        });
        XposedBridge.hookAllMethods(controllerClass, "handleScreenshot", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View shelfView = HookState.getScreenshotShelfView();
                if (shelfView != null && shelfView.isAttachedToWindow()) {
                    removeContinuityOverlay(false);
                    HookState.armReentryGrace();
                    log("Armed screenshot reentry without continuity overlay");
                } else {
                    HookState.clearReentryGrace();
                    HookState.beginFreshBatch();
                    HookState.clearPreviewStack();
                }
            }
        });
    }

    private static void hookScreenshotCallbacks(ClassLoader classLoader) {
        Class<?> callbackClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.screenshot.ScreenshotController$reloadAssets$1", classLoader);
        if (callbackClass == null) {
            log("ScreenshotController callback class not found");
            return;
        }
        XposedBridge.hookAllMethods(callbackClass, "onTouchOutside", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
            }
        });
    }

    private static void hookScreenshotShelfBinder(ClassLoader classLoader) {
        Class<?> binderClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.screenshot.ui.binder.ScreenshotShelfViewBinder", classLoader);
        if (binderClass == null) {
            log("ScreenshotShelfViewBinder not found");
            return;
        }
        XposedBridge.hookAllMethods(binderClass, "access$updateActions", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object shelfView = param.args.length > 3 ? param.args[3] : null;
                if (shelfView instanceof View) {
                    hideShelfChrome((View) shelfView);
                }
                log("Suppressing stock screenshot actions row");
                param.setResult(null);
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
            protected void beforeHookedMethod(MethodHookParam param) {
                if (HookState.getContinuityOverlayView() != null && HookState.isReentryGraceActive()) {
                    log("Allowing ScreenshotWindow.removeWindow while continuity overlay is active");
                    return;
                }
                HookState.clearReentryGrace();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                log("ScreenshotWindow.removeWindow called; preserving cached window for reuse");
                if (!HookState.isReentryGraceActive()) {
                    HookState.clearPreviewStack();
                    View shelfView = HookState.getScreenshotShelfView();
                    if (shelfView != null) {
                        clearStackUi(shelfView);
                    }
                }
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

    private static void hookImageExporter(ClassLoader classLoader) {
        Class<?> imageExporterClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.screenshot.ImageExporter", classLoader);
        if (imageExporterClass == null) {
            log("ImageExporter not found");
            return;
        }
        XposedBridge.hookAllMethods(imageExporterClass, "export", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Object safeFuture = param.getResult();
                if (safeFuture == null) {
                    return;
                }
                final Object delegate = ReflectionHelpers.getObjectFieldIfExists(safeFuture, "delegate");
                if (delegate == null) {
                    return;
                }
                final int batchId = HookState.getCurrentBatchId();
                HookState.registerExportFuture(delegate, batchId);
                try {
                    XposedHelpers.callMethod(delegate, "addListener", new Runnable() {
                        @Override
                        public void run() {
                            onImageExportCompleted(delegate);
                        }
                    }, DIRECT_EXECUTOR);
                } catch (Throwable t) {
                    log("Failed to attach ImageExporter listener: " + t);
                }
            }
        });
    }

    private static void onImageExportCompleted(Object futureDelegate) {
        try {
            Object result = XposedHelpers.callMethod(futureDelegate, "get");
            Object uriObject = ReflectionHelpers.getObjectFieldIfExists(result, "uri");
            if (!(uriObject instanceof Uri)) {
                return;
            }
            Uri uri = (Uri) uriObject;
            if (HookState.recordSavedScreenshotUri(futureDelegate, uri)) {
                deleteSavedScreenshotUri(uri);
            } else {
                log("Tracked saved screenshot uri " + uri);
            }
        } catch (Throwable t) {
            log("Failed to read ImageExporter result: " + t);
        }
    }

    private static void tintPreviewBorder(View shelfView) {
        ImageView preview = findImageView(shelfView, "screenshot_preview");
        if (preview != null) {
            int inset = dp(preview, CARD_FRAME_INSET_DP);
            preview.setBackground(createCardFrameDrawable(preview));
            preview.setPadding(inset, inset, inset, inset);
            preview.setClipToOutline(false);
            preview.setScaleType(ImageView.ScaleType.FIT_XY);
        }
        int borderId = shelfView.getResources()
                .getIdentifier("screenshot_preview_border", "id", "com.android.systemui");
        if (borderId != 0) {
            View border = shelfView.findViewById(borderId);
            if (border != null) {
                border.setVisibility(View.GONE);
                border.setAlpha(0.0f);
                border.setBackground(null);
            }
        }
        log("screenshot chrome styled with white frame and black card fill");
    }

    private static void installPreviewTapToast(View shelfView) {
        final ImageView preview = findImageView(shelfView, "screenshot_preview");
        if (preview == null) {
            return;
        }
        preview.setLongClickable(false);
        preview.setHapticFeedbackEnabled(false);
    }

    private static void showPreviewTapToast(View view) {
        Toast.makeText(view.getContext(), "Tap ignored. Hold for actions.", Toast.LENGTH_SHORT).show();
        log("Preview tap consumed; waiting for deliberate hold before stock actions");
    }

    private static void launchMarkupEditor(View view) {
        Uri screenshotUri = HookState.getLastSavedScreenshotUri();
        if (screenshotUri == null) {
            showPreviewTapToast(view);
            return;
        }
        try {
            Context context = view.getContext();
            ArrayList<Uri> editorBatch = buildEditorBatch(screenshotUri);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "fuck.iosstackingscreenshots.droidvendorssuck",
                    MarkupEditorActivity.class.getName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setData(screenshotUri);
            intent.setClipData(buildEditorClipData(editorBatch));
            intent.putExtra(MarkupEditorActivity.EXTRA_SCREENSHOT_URI, screenshotUri);
            intent.putParcelableArrayListExtra(MarkupEditorActivity.EXTRA_SCREENSHOT_BATCH_URIS, editorBatch);
            intent.putExtra(MarkupEditorActivity.EXTRA_SCREENSHOT_INDEX, 0);
            context.startActivity(intent);
            dismissScreenshotShelf();
            log("Launched markup editor for " + screenshotUri);
        } catch (Throwable t) {
            log("Failed to launch markup editor: " + t);
            Toast.makeText(view.getContext(), "Failed to open editor", Toast.LENGTH_SHORT).show();
        }
    }

    private static ArrayList<Uri> buildEditorBatch(Uri selectedUri) {
        ArrayList<Uri> batch = new ArrayList<>(HookState.getActiveSavedScreenshotUris());
        if (batch.isEmpty()) {
            batch.add(selectedUri);
            return batch;
        }
        if (!batch.contains(selectedUri)) {
            batch.add(selectedUri);
        }
        Collections.reverse(batch);
        return batch;
    }

    private static ClipData buildEditorClipData(List<Uri> batchUris) {
        if (batchUris == null || batchUris.isEmpty()) {
            return null;
        }
        ClipData clipData = new ClipData(
                "screenshot-batch",
                new String[]{"image/*"},
                new ClipData.Item(batchUris.get(0)));
        for (int i = 1; i < batchUris.size(); i++) {
            clipData.addItem(new ClipData.Item(batchUris.get(i)));
        }
        return clipData;
    }

    private static void dismissScreenshotShelf() {
        Object screenshotWindow = HookState.getScreenshotWindow();
        if (screenshotWindow == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(screenshotWindow, "removeWindow");
            log("Dismissed screenshot shelf after handing off to markup editor");
        } catch (Throwable t) {
            log("Failed to dismiss screenshot shelf after editor launch: " + t);
        }
    }

    private static void hookPreviewTouchRouting(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent", android.view.MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View) || !(param.args[0] instanceof android.view.MotionEvent)) {
                            return;
                        }
                        View targetView = (View) param.thisObject;
                        if (!isScreenshotPreviewClickView(targetView)) {
                            return;
                        }
                        android.view.MotionEvent event = (android.view.MotionEvent) param.args[0];
                        View activeView = activePreviewTouchViewRef.get();
                        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                                && !isPointInsideView(targetView, event.getRawX(), event.getRawY())) {
                            clearActivePreviewTouch();
                            return;
                        }
                        int action = event.getActionMasked();
                        switch (action) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                activePreviewTouchViewRef = new WeakReference<>(targetView);
                                activePreviewTouchDownMs = event.getEventTime();
                                activePreviewLongPressTriggered = false;
                                schedulePreviewLongPress(targetView);
                                param.setResult(Boolean.TRUE);
                                return;
                            case android.view.MotionEvent.ACTION_MOVE:
                                if (activeView == null || activeView != targetView) {
                                    clearActivePreviewTouch();
                                }
                                param.setResult(Boolean.TRUE);
                                return;
                            case android.view.MotionEvent.ACTION_UP:
                                if (activeView == null) {
                                    clearActivePreviewTouch();
                                    param.setResult(Boolean.TRUE);
                                    return;
                                }
                                long heldMs = Math.max(0L, event.getEventTime() - activePreviewTouchDownMs);
                                boolean longPressTriggered = activePreviewLongPressTriggered;
                                clearActivePreviewTouch();
                                if (!longPressTriggered) {
                                    log("Preview tap intercepted at " + heldMs + "ms");
                                    launchPreviewTapAction(activeView);
                                }
                                param.setResult(Boolean.TRUE);
                                return;
                            case android.view.MotionEvent.ACTION_CANCEL:
                                clearActivePreviewTouch();
                                param.setResult(Boolean.TRUE);
                                return;
                            default:
                                if (activeView != null) {
                                    param.setResult(Boolean.TRUE);
                                }
                                return;
                        }
                    }
                });
    }

    private static long consumeRecentPreviewHoldMs(View clickedView) {
        View recordedView = lastPreviewClickTargetRef.get();
        long recordedAtMs = lastPreviewClickRecordedAtMs;
        lastPreviewClickTargetRef = new WeakReference<>(null);
        lastPreviewClickRecordedAtMs = 0L;
        long heldMs = lastPreviewClickHeldMs;
        lastPreviewClickHeldMs = 0L;
        if (recordedView != clickedView) {
            return -1L;
        }
        if (recordedAtMs == 0L || android.os.SystemClock.uptimeMillis() - recordedAtMs > 1000L) {
            return -1L;
        }
        return heldMs;
    }

    private static void hookPreviewActionModel(ClassLoader classLoader) {
        final Class<?> previewActionClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.screenshot.ui.viewmodel.PreviewAction", classLoader);
        if (previewActionClass == null) {
            log("PreviewAction not found");
            return;
        }
        XposedBridge.hookAllConstructors(previewActionClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Object previewAction = param.thisObject;
                final Object originalFunction = XposedHelpers.getObjectField(previewAction, "onClick");
                if (originalFunction == null) {
                    return;
                }
                Class<?>[] interfaces = originalFunction.getClass().getInterfaces();
                if (interfaces.length == 0) {
                    return;
                }
                Object wrappedFunction = Proxy.newProxyInstance(
                        originalFunction.getClass().getClassLoader(),
                        interfaces,
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                if (!"invoke".equals(method.getName())) {
                                    return method.invoke(originalFunction, args);
                                }
                                long heldMs = consumeRecentPreviewHoldMs(lastPreviewClickTargetRef.get());
                                if (heldMs >= PREVIEW_CHOOSER_HOLD_MS) {
                                    log("PreviewAction allowed stock invoke for hold=" + heldMs + "ms");
                                    return method.invoke(originalFunction, args);
                                }
                                View shelfView = HookState.getScreenshotShelfView();
                                if (shelfView != null) {
                                    log("PreviewAction intercepted short tap hold=" + heldMs + "ms");
                                    launchPreviewTapAction(shelfView);
                                    return null;
                                }
                                return method.invoke(originalFunction, args);
                            }
                        });
                XposedHelpers.setObjectField(previewAction, "onClick", wrappedFunction);
            }
        });
    }

    private static boolean isScreenshotPreviewClickView(View view) {
        if (view.getId() == View.NO_ID) {
            return false;
        }
        try {
            if (!"com.android.systemui".equals(view.getResources().getResourcePackageName(view.getId()))) {
                return false;
            }
            String entryName = view.getResources().getResourceEntryName(view.getId());
            if (!"screenshot_preview".equals(entryName)
                    && !"screenshot_scrolling_scrim".equals(entryName)) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    private static void clearActivePreviewTouch() {
        if (activePreviewLongPressRunnable != null) {
            MAIN_HANDLER.removeCallbacks(activePreviewLongPressRunnable);
            activePreviewLongPressRunnable = null;
        }
        activePreviewTouchViewRef = new WeakReference<>(null);
        activePreviewTouchDownMs = 0L;
        activePreviewLongPressTriggered = false;
    }

    private static void schedulePreviewLongPress(final View targetView) {
        if (activePreviewLongPressRunnable != null) {
            MAIN_HANDLER.removeCallbacks(activePreviewLongPressRunnable);
        }
        activePreviewLongPressRunnable = new Runnable() {
            @Override
            public void run() {
                View activeView = activePreviewTouchViewRef.get();
                if (activeView == null || activeView != targetView) {
                    return;
                }
                activePreviewLongPressTriggered = true;
                long heldMs = Math.max(0L, android.os.SystemClock.uptimeMillis() - activePreviewTouchDownMs);
                recordPreviewHold(activeView, heldMs);
                log("Preview hold reached chooser threshold at " + heldMs + "ms");
                try {
                    activeView.performClick();
                } catch (Throwable t) {
                    log("Failed to forward preview hold to stock action: " + t);
                }
            }
        };
        MAIN_HANDLER.postDelayed(activePreviewLongPressRunnable, PREVIEW_CHOOSER_HOLD_MS);
    }

    private static void recordPreviewHold(View view, long heldMs) {
        lastPreviewClickTargetRef = new WeakReference<>(view);
        lastPreviewClickHeldMs = Math.max(0L, heldMs);
        lastPreviewClickRecordedAtMs = android.os.SystemClock.uptimeMillis();
    }

    private static void launchPreviewTapAction(View view) {
        launchMarkupEditor(view);
    }

    private static boolean isPointInsideView(View view, float rawX, float rawY) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0]
                && rawX < location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY < location[1] + view.getHeight();
    }

    private static void hideShelfChrome(View shelfView) {
        hideBoundView(ReflectionHelpers.getObjectFieldIfExists(shelfView, "actionsContainerBackground"));
        hideBoundView(ReflectionHelpers.getObjectFieldIfExists(shelfView, "actionsContainer"));
        hideBoundView(ReflectionHelpers.getObjectFieldIfExists(shelfView, "dismissButton"));
        hideView(shelfView, "actions_container_background");
        hideView(shelfView, "actions_container");
        hideView(shelfView, "screenshot_actions");
        hideView(shelfView, "screenshot_dismiss_button");
    }

    private static void hideBoundView(Object candidate) {
        if (candidate instanceof View) {
            hideViewInstance((View) candidate);
        }
    }

    private static void hideView(View root, String idName) {
        int id = root.getResources().getIdentifier(idName, "id", "com.android.systemui");
        if (id == 0) {
            return;
        }
        View view = root.findViewById(id);
        if (view == null) {
            return;
        }
        hideViewInstance(view);
    }

    private static void hideViewInstance(View view) {
        view.setVisibility(View.GONE);
        view.setAlpha(0.0f);
        view.setClickable(false);
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.width = 0;
            layoutParams.height = 0;
            view.setLayoutParams(layoutParams);
        }
    }

    private static void updateStackUi(View shelfView) {
        ViewGroup shelfStatic = findViewGroup(shelfView, "screenshot_static");
        ImageView preview = findImageView(shelfView, "screenshot_preview");
        ImageView previewBlur = findImageView(shelfView, "screenshot_preview_blur");
        if (shelfStatic == null || preview == null || previewBlur == null) {
            return;
        }

        logViewGeometry("preview before update", preview);
        logViewGeometry("previewBlur before update", previewBlur);
        tintPreviewBorder(shelfView);
        clearSyntheticStackUi(shelfStatic);
        List<Bitmap> stackBitmaps = HookState.getStackBitmaps();
        resetRearPreview(previewBlur);
        if (stackBitmaps.isEmpty()) {
            log("updateStackUi: empty stack");
            return;
        }

        previewBlur.setVisibility(View.INVISIBLE);
        preview.setAlpha(0.0f);
        int visibleRearCards = Math.min(2, stackBitmaps.size());
        for (int depth = visibleRearCards - 1; depth >= 0; depth--) {
            addOverlayStackCard(shelfStatic, preview, stackBitmaps.get(depth), depth + 1);
        }
        Bitmap currentBitmap = HookState.getLastPreviewBitmap();
        if (currentBitmap != null) {
            addOverlayStackCard(shelfStatic, preview, currentBitmap, 0);
        }

        int totalCount = stackBitmaps.size() + (HookState.getLastPreviewBitmap() != null ? 1 : 0);
        log("updateStackUi: stack=" + stackBitmaps.size() + " total=" + totalCount);
    }

    private static void clearStackUi(View shelfView) {
        ViewGroup shelfStatic = findViewGroup(shelfView, "screenshot_static");
        ImageView preview = findImageView(shelfView, "screenshot_preview");
        ImageView previewBlur = findImageView(shelfView, "screenshot_preview_blur");
        if (shelfStatic != null) {
            clearSyntheticStackUi(shelfStatic);
        }
        if (preview != null) {
            preview.setAlpha(1.0f);
            preview.setTranslationX(0.0f);
            preview.setTranslationY(0.0f);
        }
        if (previewBlur != null) {
            resetRearPreview(previewBlur);
        }
    }

    private static void clearSyntheticStackUi(ViewGroup shelfStatic) {
        clearOverlayStackCards(shelfStatic);
        for (int i = shelfStatic.getChildCount() - 1; i >= 0; i--) {
            View child = shelfStatic.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && ((String) tag).startsWith(STACK_CARD_TAG_PREFIX)) {
                shelfStatic.removeViewAt(i);
            }
        }
    }

    private static void applyRearStackCard(ImageView stackCard, ImageView preview, Bitmap bitmap, int depth) {
        Bitmap cardBitmap = createCardBitmap(preview, bitmap, CARD_FRAME_INSET_DP);
        if (cardBitmap == null) {
            return;
        }
        applyScreenshotBitmap(stackCard, cardBitmap);
        syncPreviewLayout(preview, stackCard);
        layoutStackCardToPreviewBounds(preview, stackCard);
        applyStackCard(stackCard, preview, depth);
    }

    private static void applyStackCard(ImageView stackCard, ImageView preview, int depth) {
        stackCard.setAdjustViewBounds(false);
        stackCard.setClickable(false);
        stackCard.setVisibility(View.VISIBLE);
        stackCard.setAlpha(1.0f);
        stackCard.setScaleX(1.0f);
        stackCard.setScaleY(1.0f);
        float offsetX = dp(stackCard, STACK_CARD_X_OFFSET_DP) * (depth + 1);
        float offsetY = dp(stackCard, STACK_CARD_Y_OFFSET_DP) * (depth + 1);
        stackCard.setTranslationX(preview.getTranslationX() - offsetX);
        stackCard.setTranslationY(preview.getTranslationY() + offsetY);
        stackCard.setElevation(Math.max(0.0f, preview.getElevation() - (depth + 1)));
    }

    private static void addOverlayStackCard(ViewGroup shelfStatic, ImageView preview, Bitmap bitmap, int depth) {
        Bitmap cardBitmap = createCardBitmap(preview, bitmap, CARD_FRAME_INSET_DP);
        if (cardBitmap == null) {
            return;
        }
        BitmapDrawable drawable = new BitmapDrawable(shelfStatic.getResources(), cardBitmap);
        int offsetX = dp(preview, STACK_CARD_X_OFFSET_DP) * depth;
        int offsetY = dp(preview, STACK_CARD_Y_OFFSET_DP) * depth;
        int left = Math.round(preview.getX()) + offsetX;
        int top = Math.round(preview.getY()) - offsetY;
        drawable.setBounds(left, top, left + cardBitmap.getWidth(), top + cardBitmap.getHeight());
        shelfStatic.getOverlay().add(drawable);
        overlayStackCards.add(drawable);
    }

    private static ImageView ensureSyntheticStackCard(ViewGroup shelfStatic, ImageView preview, int depth) {
        String tag = STACK_CARD_TAG_PREFIX + depth;
        for (int i = 0; i < shelfStatic.getChildCount(); i++) {
            View child = shelfStatic.getChildAt(i);
            if (tag.equals(child.getTag()) && child instanceof ImageView) {
                return (ImageView) child;
            }
        }

        ImageView stackCard = new ImageView(shelfStatic.getContext());
        stackCard.setTag(tag);
        ViewGroup.LayoutParams layoutParams = cloneLayoutParams(preview.getLayoutParams());
        if (layoutParams != null) {
            stackCard.setLayoutParams(layoutParams);
        }
        int previewIndex = shelfStatic.indexOfChild(preview);
        int insertIndex = previewIndex >= 0 ? previewIndex : shelfStatic.getChildCount();
        shelfStatic.addView(stackCard, insertIndex);
        return stackCard;
    }

    private static void resetRearPreview(ImageView previewBlur) {
        previewBlur.setImageDrawable(null);
        previewBlur.setBackground(null);
        previewBlur.setPadding(0, 0, 0, 0);
        previewBlur.setTranslationX(0.0f);
        previewBlur.setTranslationY(0.0f);
        previewBlur.setScaleX(1.0f);
        previewBlur.setScaleY(1.0f);
        previewBlur.setAlpha(1.0f);
        previewBlur.setVisibility(View.INVISIBLE);
    }

    private static void logViewGeometry(String label, View view) {
        if (view == null) {
            log(label + ": null");
            return;
        }
        log(label
                + " left=" + view.getLeft()
                + " top=" + view.getTop()
                + " x=" + view.getX()
                + " y=" + view.getY()
                + " tx=" + view.getTranslationX()
                + " ty=" + view.getTranslationY()
                + " w=" + view.getWidth()
                + " h=" + view.getHeight()
                + " vis=" + view.getVisibility());
    }

    private static void scheduleStackUiUpdate(final View shelfView) {
        shelfView.post(new Runnable() {
            @Override
            public void run() {
                updateStackUi(shelfView);
                shelfView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        updateStackUi(shelfView);
                    }
                }, STACK_UI_SETTLE_DELAY_MS);
            }
        });
    }

    private static void clearOverlayStackCards(ViewGroup shelfStatic) {
        for (Drawable drawable : overlayStackCards) {
            shelfStatic.getOverlay().remove(drawable);
        }
        overlayStackCards.clear();
    }

    private static Drawable createCardFrameDrawable(View view) {
        int inset = dp(view, CARD_FRAME_INSET_DP);
        float outerRadius = dp(view, 2.0f);
        float innerRadius = Math.max(0.0f, outerRadius - inset);

        GradientDrawable base = new GradientDrawable();
        base.setShape(GradientDrawable.RECTANGLE);
        base.setColor(IOS_FRAME_COLOR);
        base.setCornerRadius(outerRadius);

        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.RECTANGLE);
        outer.setColor(IOS_FRAME_COLOR);
        outer.setCornerRadius(outerRadius);
        outer.setStroke(1, IOS_FRAME_STROKE_COLOR);

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.RECTANGLE);
        inner.setColor(IOS_CARD_BACKGROUND_COLOR);
        inner.setCornerRadius(innerRadius);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, outer, inner});
        layers.setLayerInset(2, inset, inset, inset, inset);
        return layers;
    }

    private static void syncPreviewLayout(ImageView preview, ImageView target) {
        int width = preview.getWidth();
        int height = preview.getHeight();
        if (width <= 0 || height <= 0) {
            ViewGroup.LayoutParams previewParams = preview.getLayoutParams();
            if (previewParams != null) {
                width = previewParams.width;
                height = previewParams.height;
            }
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        ViewGroup.LayoutParams targetParams = target.getLayoutParams();
        if (targetParams == null) {
            targetParams = new ViewGroup.LayoutParams(width, height);
        } else {
            targetParams.width = width;
            targetParams.height = height;
        }
        target.setLayoutParams(targetParams);
    }

    private static void layoutStackCardToPreviewBounds(ImageView preview, ImageView target) {
        int width = preview.getWidth();
        int height = preview.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        target.measure(widthSpec, heightSpec);
        target.layout(preview.getLeft(), preview.getTop(), preview.getLeft() + width, preview.getTop() + height);
    }

    private static View findViewById(View root, String idName) {
        int id = root.getResources().getIdentifier(idName, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }

    private static void applyScreenshotBitmap(ImageView imageView, Bitmap bitmap) {
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        imageView.requestLayout();
    }

    private static void forceCurrentPreviewBitmap(View shelfView, Bitmap bitmap) {
        ImageView preview = findImageView(shelfView, "screenshot_preview");
        if (preview == null || bitmap == null) {
            return;
        }
        preview.setImageBitmap(bitmap);
        preview.invalidate();
    }

    private static void applyCardLayout(ImageView imageView, Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        int maxWidth = dp(imageView, CARD_MAX_WIDTH_DP);
        int maxHeight = dp(imageView, CARD_MAX_HEIGHT_DP);
        float aspect = bitmap.getHeight() == 0 ? 1.0f : (float) bitmap.getWidth() / (float) bitmap.getHeight();
        int width = maxWidth;
        int height = Math.round(width / Math.max(0.01f, aspect));
        if (height > maxHeight) {
            height = maxHeight;
            width = Math.round(height * aspect);
        }
        ViewGroup.LayoutParams layoutParams = imageView.getLayoutParams();
        if (layoutParams == null) {
            layoutParams = new ViewGroup.LayoutParams(width, height);
        } else {
            layoutParams.width = width;
            layoutParams.height = height;
        }
        imageView.setLayoutParams(layoutParams);
    }

    private static Bitmap createCardBitmap(ImageView preview, Bitmap bitmap, float insetDp) {
        if (bitmap == null) {
            return null;
        }
        int width = preview.getWidth();
        int height = preview.getHeight();
        if (width <= 0 || height <= 0) {
            ViewGroup.LayoutParams params = preview.getLayoutParams();
            if (params != null) {
                width = params.width;
                height = params.height;
            }
        }
        if (width <= 0 || height <= 0) {
            return null;
        }
        int inset = dp(preview, insetDp);
        Bitmap card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(card);
        Drawable frame = createCardFrameDrawable(preview);
        frame.setBounds(0, 0, width, height);
        frame.draw(canvas);
        Rect dst = new Rect(inset, inset, width - inset, height - inset);
        Bitmap safeBitmap = bitmap;
        if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (safeBitmap == null) {
                return null;
            }
        }
        canvas.drawRect(dst, CARD_CONTENT_BACKGROUND_PAINT);
        canvas.drawBitmap(safeBitmap, null, dst, CARD_BITMAP_PAINT);
        return card;
    }

    private static void applyFramedPreview(ImageView imageView, Bitmap bitmap) {
        applyCardLayout(imageView, bitmap);
        Bitmap cardBitmap = createCardBitmap(imageView, bitmap, CARD_FRAME_INSET_DP);
        if (cardBitmap == null) {
            return;
        }
        imageView.setBackground(null);
        imageView.setPadding(0, 0, 0, 0);
        imageView.setClipToOutline(false);
        applyScreenshotBitmap(imageView, cardBitmap);
    }

    private static ViewGroup.LayoutParams cloneLayoutParams(ViewGroup.LayoutParams source) {
        if (source == null) {
            return null;
        }
        try {
            return (ViewGroup.LayoutParams) source.getClass().getConstructor(source.getClass()).newInstance(source);
        } catch (Throwable ignored) {
        }
        try {
            return (ViewGroup.LayoutParams) source.getClass().getConstructor(ViewGroup.LayoutParams.class)
                    .newInstance(source);
        } catch (Throwable ignored) {
        }
        return new ViewGroup.LayoutParams(source.width, source.height);
    }

    private static ViewGroup findViewGroup(View root, String idName) {
        int id = root.getResources().getIdentifier(idName, "id", "com.android.systemui");
        View found = id == 0 ? null : root.findViewById(id);
        if (found instanceof ViewGroup) {
            return (ViewGroup) found;
        }
        return null;
    }

    private static ImageView findImageView(View root, String idName) {
        int id = root.getResources().getIdentifier(idName, "id", "com.android.systemui");
        View found = id == 0 ? null : root.findViewById(id);
        if (found instanceof ImageView) {
            return (ImageView) found;
        }
        return null;
    }

    private static int dp(View view, float dp) {
        return Math.round(dp * view.getResources().getDisplayMetrics().density);
    }

    private static int getSystemUiDimensionPixelSize(View view, String name, float fallbackDp) {
        int dimenId = view.getResources().getIdentifier(name, "dimen", "com.android.systemui");
        if (dimenId != 0) {
            try {
                return view.getResources().getDimensionPixelSize(dimenId);
            } catch (Throwable ignored) {
            }
        }
        return dp(view, fallbackDp);
    }

    private static void forcePreviewVisible(Object proxy) {
        Object animationController = ReflectionHelpers.getObjectFieldIfExists(proxy, "animationController");
        View shelfView = (View) ReflectionHelpers.getObjectFieldIfExists(proxy, "view");
        Object screenshotPreviewObject = ReflectionHelpers.getObjectFieldIfExists(proxy, "screenshotPreview");
        if (screenshotPreviewObject instanceof View) {
            View screenshotPreview = (View) screenshotPreviewObject;
            screenshotPreview.setAlpha(1.0f);
            screenshotPreview.setVisibility(View.VISIBLE);
        }
        if (shelfView != null) {
            shelfView.setAlpha(1.0f);
            shelfView.setVisibility(View.VISIBLE);
        }
        Object flashView = ReflectionHelpers.getObjectFieldIfExists(animationController, "flashView");
        if (flashView instanceof View) {
            View flash = (View) flashView;
            flash.setAlpha(0.0f);
            flash.setVisibility(View.GONE);
        }
    }

    private static void createContinuityOverlayFromShelf(View shelfView) {
        if (shelfView.getWidth() <= 0 || shelfView.getHeight() <= 0) {
            log("Skipping continuity overlay; shelf has no size");
            return;
        }
        Object screenshotWindow = HookState.getScreenshotWindow();
        Object phoneWindow = ReflectionHelpers.getObjectFieldIfExists(screenshotWindow, "window");
        if (!(phoneWindow instanceof android.view.Window)) {
            log("Skipping continuity overlay; screenshot PhoneWindow unavailable");
            return;
        }
        Object context = ReflectionHelpers.callMethodIfExists(phoneWindow, "getContext");
        if (context == null) {
            log("Skipping continuity overlay; no window context");
            return;
        }

        WindowManager windowManager = (WindowManager) ReflectionHelpers.callMethodIfExists(context,
                "getSystemService", "window");
        if (windowManager == null) {
            log("Skipping continuity overlay; no WindowManager");
            return;
        }

        int[] location = new int[2];
        shelfView.getLocationInWindow(location);
        Rect sourceRect = new Rect(location[0], location[1], location[0] + shelfView.getWidth(),
                location[1] + shelfView.getHeight());
        Bitmap snapshot = Bitmap.createBitmap(shelfView.getWidth(), shelfView.getHeight(), Bitmap.Config.ARGB_8888);

        removeContinuityOverlay(false);
        android.view.Window window = (android.view.Window) phoneWindow;
        PixelCopy.request(window, sourceRect, snapshot, new PixelCopy.OnPixelCopyFinishedListener() {
            @Override
            public void onPixelCopyFinished(int copyResult) {
                if (copyResult != PixelCopy.SUCCESS) {
                    log("Continuity overlay PixelCopy failed: " + copyResult);
                    return;
                }
                addContinuityOverlay(windowManager, (android.content.Context) context, snapshot, sourceRect);
            }
        }, MAIN_HANDLER);
    }

    private static void deleteSavedScreenshotBatch(List<Uri> uris) {
        for (Uri uri : uris) {
            deleteSavedScreenshotUri(uri);
        }
    }

    private static void deleteSavedScreenshotUri(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            android.content.Context context = resolveScreenshotContext();
            if (context == null) {
                log("Unable to delete screenshot uri; no screenshot context for " + uri);
                return;
            }
            int deleted = context.getContentResolver().delete(uri, null, null);
            HookState.removeSavedScreenshotUri(uri);
            log("Deleted screenshot uri " + uri + " count=" + deleted);
        } catch (Throwable t) {
            log("Failed to delete screenshot uri " + uri + ": " + t);
        }
    }

    private static android.content.Context resolveScreenshotContext() {
        Object screenshotWindow = HookState.getScreenshotWindow();
        Object phoneWindow = ReflectionHelpers.getObjectFieldIfExists(screenshotWindow, "window");
        Object context = ReflectionHelpers.callMethodIfExists(phoneWindow, "getContext");
        if (context instanceof android.content.Context) {
            return (android.content.Context) context;
        }
        View shelfView = HookState.getScreenshotShelfView();
        if (shelfView != null) {
            return shelfView.getContext();
        }
        return null;
    }

    private static void addContinuityOverlay(WindowManager windowManager, android.content.Context context,
            Bitmap snapshot, Rect sourceRect) {
        if (HookState.hasReentryPreviewBound()) {
            log("Skipping stale continuity overlay because new preview is already bound");
            return;
        }
        ImageView overlay = new ImageView((android.content.Context) context);
        overlay.setImageBitmap(snapshot);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);
        overlay.setAlpha(1.0f);
        overlay.setBackgroundColor(Color.TRANSPARENT);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                sourceRect.width(),
                sourceRect.height(),
                2036,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.setTitle("IOSStackingShotsContinuity");
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = sourceRect.left;
        params.y = sourceRect.top;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);

        try {
            windowManager.addView(overlay, params);
            HookState.setContinuityOverlay(overlay, windowManager);
            scheduleContinuityOverlayRemoval(CONTINUITY_OVERLAY_MS, true);
            log("Continuity overlay added");
        } catch (Throwable t) {
            log("Failed to add continuity overlay: " + t);
        }
    }

    private static void scheduleContinuityOverlayRemoval(long delayMs, boolean clearReentryGrace) {
        if (continuityOverlayRemoval != null) {
            MAIN_HANDLER.removeCallbacks(continuityOverlayRemoval);
        }
        final boolean shouldClearReentryGrace = clearReentryGrace;
        continuityOverlayRemoval = new Runnable() {
            @Override
            public void run() {
                removeContinuityOverlay(shouldClearReentryGrace);
            }
        };
        MAIN_HANDLER.postDelayed(continuityOverlayRemoval, delayMs);
    }

    private static void removeContinuityOverlay(boolean clearReentryGrace) {
        View overlay = HookState.getContinuityOverlayView();
        WindowManager windowManager = HookState.getContinuityOverlayWindowManager();
        HookState.clearContinuityOverlay();
        if (clearReentryGrace) {
            HookState.clearReentryGrace();
        }
        if (overlay == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(overlay);
            log("Continuity overlay removed");
        } catch (Throwable t) {
            log("Failed to remove continuity overlay: " + t);
        }
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

            ArrayList<Object> surfacesToExclude = new ArrayList<>();
            surfacesToExclude.add(screenshotSurface);
            Object continuityOverlaySurface = HookState.getContinuityOverlaySurface();
            if (isValidSurface(continuityOverlaySurface)) {
                surfacesToExclude.add(continuityOverlaySurface);
                log("Also excluding continuity overlay surface");
            }

            Object excludedSurfaces = Array.newInstance(surfaceControlClass, surfacesToExclude.size());
            for (int i = 0; i < surfacesToExclude.size(); i++) {
                Array.set(excludedSurfaces, i, surfacesToExclude.get(i));
            }
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
