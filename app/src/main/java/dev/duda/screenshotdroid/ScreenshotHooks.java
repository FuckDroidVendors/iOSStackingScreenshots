package dev.duda.screenshotdroid;

import android.animation.AnimatorSet;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class ScreenshotHooks {
    private static final String TAG = "ScreenshotDroid";
    private static final long CONTINUITY_OVERLAY_MS = 1200L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String STACK_CARD_TAG_PREFIX = "ScreenshotDroidStackCard";
    private static final float STACK_CARD_X_OFFSET_DP = 8.0f;
    private static final float STACK_CARD_Y_OFFSET_DP = 6.0f;
    private static volatile boolean installed;
    private static Runnable continuityOverlayRemoval;

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
                View shelfView = HookState.getScreenshotShelfView();
                if (shelfView != null) {
                    shelfView.post(new Runnable() {
                        @Override
                        public void run() {
                            updateStackUi(shelfView);
                        }
                    });
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
            }
        });
        XposedBridge.hookAllMethods(controllerClass, "handleScreenshot", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View shelfView = HookState.getScreenshotShelfView();
                if (shelfView != null && shelfView.isAttachedToWindow()) {
                    createContinuityOverlayFromShelf(shelfView);
                    HookState.armReentryGrace();
                    log("Prepared continuity overlay for screenshot reentry");
                } else {
                    HookState.clearReentryGrace();
                    HookState.clearPreviewStack();
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
            protected void beforeHookedMethod(MethodHookParam param) {
                if (HookState.getContinuityOverlayView() != null && HookState.isReentryGraceActive()) {
                    log("Allowing ScreenshotWindow.removeWindow while continuity overlay is active");
                    return;
                }
                HookState.clearReentryGrace();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                log("ScreenshotWindow.removeWindow called; clearing cached window");
                HookState.clearScreenshotWindow(param.thisObject);
                if (!HookState.isReentryGraceActive()) {
                    HookState.clearPreviewStack();
                    View shelfView = HookState.getScreenshotShelfView();
                    if (shelfView != null) {
                        shelfView.post(new Runnable() {
                            @Override
                            public void run() {
                                clearStackUi(shelfView);
                            }
                        });
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

    private static void updateStackUi(View shelfView) {
        ViewGroup shelfStatic = findViewGroup(shelfView, "screenshot_static");
        ImageView preview = findImageView(shelfView, "screenshot_preview");
        ImageView previewBlur = findImageView(shelfView, "screenshot_preview_blur");
        ImageView badge = findImageView(shelfView, "screenshot_badge");
        if (shelfStatic == null || preview == null || previewBlur == null || badge == null) {
            return;
        }

        clearSyntheticStackCards(shelfStatic);
        List<Bitmap> stackBitmaps = HookState.getStackBitmaps();
        if (stackBitmaps.isEmpty()) {
            return;
        }

        applyStackCard(previewBlur, preview, stackBitmaps.get(0), 0);
        if (stackBitmaps.size() > 1) {
            addSyntheticStackCard(shelfStatic, previewBlur, preview, stackBitmaps.get(1), 1);
        }

        int totalCount = stackBitmaps.size() + (HookState.getLastPreviewBitmap() != null ? 1 : 0);
        badge.setImageBitmap(createCountBadgeBitmap(badge, totalCount));
        badge.setVisibility(totalCount > 1 ? View.VISIBLE : View.GONE);
        badge.setContentDescription(totalCount + " screenshots");
    }

    private static void clearStackUi(View shelfView) {
        ViewGroup shelfStatic = findViewGroup(shelfView, "screenshot_static");
        ImageView previewBlur = findImageView(shelfView, "screenshot_preview_blur");
        ImageView badge = findImageView(shelfView, "screenshot_badge");
        if (shelfStatic != null) {
            clearSyntheticStackCards(shelfStatic);
        }
        if (previewBlur != null) {
            previewBlur.setImageDrawable(null);
            previewBlur.setTranslationX(0.0f);
            previewBlur.setTranslationY(0.0f);
            previewBlur.setScaleX(1.0f);
            previewBlur.setScaleY(1.0f);
            previewBlur.setAlpha(1.0f);
            previewBlur.setVisibility(View.INVISIBLE);
        }
        if (badge != null) {
            badge.setImageDrawable(null);
            badge.setVisibility(View.GONE);
            badge.setContentDescription(null);
        }
    }

    private static void clearSyntheticStackCards(ViewGroup shelfStatic) {
        for (int i = shelfStatic.getChildCount() - 1; i >= 0; i--) {
            View child = shelfStatic.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && ((String) tag).startsWith(STACK_CARD_TAG_PREFIX)) {
                shelfStatic.removeViewAt(i);
            }
        }
    }

    private static void addSyntheticStackCard(ViewGroup shelfStatic, View insertBefore, ImageView preview,
            Bitmap bitmap, int depth) {
        ImageView stackCard = new ImageView(shelfStatic.getContext());
        stackCard.setTag(STACK_CARD_TAG_PREFIX + depth);
        ViewGroup.LayoutParams layoutParams = cloneLayoutParams(preview.getLayoutParams());
        if (layoutParams != null) {
            stackCard.setLayoutParams(layoutParams);
        }
        applyPreviewBackground(preview, stackCard);
        stackCard.setClipToOutline(preview.getClipToOutline());
        applyStackCard(stackCard, preview, bitmap, depth);
        int insertIndex = shelfStatic.indexOfChild(insertBefore);
        shelfStatic.addView(stackCard, Math.max(0, insertIndex));
    }

    private static void applyStackCard(ImageView stackCard, ImageView preview, Bitmap bitmap, int depth) {
        applyScreenshotBitmap(stackCard, bitmap);
        stackCard.setAdjustViewBounds(true);
        stackCard.setClickable(false);
        stackCard.setVisibility(View.VISIBLE);
        stackCard.setAlpha(depth == 0 ? 0.92f : 0.78f);
        stackCard.setScaleX(depth == 0 ? 0.985f : 0.97f);
        stackCard.setScaleY(depth == 0 ? 0.985f : 0.97f);
        stackCard.setTranslationX(dp(stackCard, STACK_CARD_X_OFFSET_DP) * (depth + 1));
        stackCard.setTranslationY(-dp(stackCard, STACK_CARD_Y_OFFSET_DP) * (depth + 1));
        stackCard.setElevation(Math.max(0.0f, preview.getElevation() - (depth + 1)));
    }

    private static void applyPreviewBackground(ImageView preview, ImageView target) {
        if (preview.getBackground() == null || preview.getBackground().getConstantState() == null) {
            return;
        }
        target.setBackground(preview.getBackground().getConstantState().newDrawable().mutate());
    }

    private static void applyScreenshotBitmap(ImageView imageView, Bitmap bitmap) {
        imageView.setImageBitmap(bitmap);
        boolean portrait = bitmap.getWidth() < bitmap.getHeight();
        int overlayScale = getSystemUiDimensionPixelSize(imageView, "overlay_x_scale", 240.0f);
        ViewGroup.LayoutParams layoutParams = imageView.getLayoutParams();
        if (layoutParams == null) {
            layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (portrait) {
            layoutParams.width = overlayScale;
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            imageView.setScaleType(ImageView.ScaleType.FIT_START);
        } else {
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            layoutParams.height = overlayScale;
            imageView.setScaleType(ImageView.ScaleType.FIT_END);
        }
        imageView.setLayoutParams(layoutParams);
        imageView.requestLayout();
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

    private static Bitmap createCountBadgeBitmap(ImageView badge, int totalCount) {
        int width = Math.max(1, badge.getWidth() > 0 ? badge.getWidth() : dp(badge, 56f));
        int height = Math.max(1, badge.getHeight() > 0 ? badge.getHeight() : dp(badge, 56f));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float radius = Math.min(width, height) / 2f;
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(Color.parseColor("#D32F2F"));
        canvas.drawCircle(width / 2f, height / 2f, radius, circle);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(height * (totalCount >= 10 ? 0.34f : 0.45f));

        Paint.FontMetrics metrics = text.getFontMetrics();
        float baseline = (height / 2f) - ((metrics.ascent + metrics.descent) / 2f);
        canvas.drawText(String.valueOf(totalCount), width / 2f, baseline, text);
        return bitmap;
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

        removeContinuityOverlay();
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

    private static void addContinuityOverlay(WindowManager windowManager, android.content.Context context,
            Bitmap snapshot, Rect sourceRect) {
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
        params.setTitle("ScreenshotDroidContinuity");
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = sourceRect.left;
        params.y = sourceRect.top;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);

        try {
            windowManager.addView(overlay, params);
            HookState.setContinuityOverlay(overlay, windowManager);
            scheduleContinuityOverlayRemoval();
            log("Continuity overlay added");
        } catch (Throwable t) {
            log("Failed to add continuity overlay: " + t);
        }
    }

    private static void scheduleContinuityOverlayRemoval() {
        if (continuityOverlayRemoval != null) {
            MAIN_HANDLER.removeCallbacks(continuityOverlayRemoval);
        }
        continuityOverlayRemoval = new Runnable() {
            @Override
            public void run() {
                removeContinuityOverlay();
            }
        };
        MAIN_HANDLER.postDelayed(continuityOverlayRemoval, CONTINUITY_OVERLAY_MS);
    }

    private static void removeContinuityOverlay() {
        View overlay = HookState.getContinuityOverlayView();
        WindowManager windowManager = HookState.getContinuityOverlayWindowManager();
        HookState.clearContinuityOverlay();
        HookState.clearReentryGrace();
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
