package fuck.iosstackingscreenshots.droidvendorssuck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class MarkupEditorStageView extends View {
    static final int TOOL_BROWSE = 0;
    static final int TOOL_PEN = 1;
    static final int TOOL_CROP = 2;

    interface Listener {
        void onRequestNavigate(int direction);
        void onEditStateChanged(boolean hasEdits);
    }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_MOVE = 1;
    private static final int HANDLE_LEFT = 2;
    private static final int HANDLE_TOP = 3;
    private static final int HANDLE_RIGHT = 4;
    private static final int HANDLE_BOTTOM = 5;
    private static final int HANDLE_TOP_LEFT = 6;
    private static final int HANDLE_TOP_RIGHT = 7;
    private static final int HANDLE_BOTTOM_RIGHT = 8;
    private static final int HANDLE_BOTTOM_LEFT = 9;

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint emptyTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();
    private final RectF cropRectNormalized = new RectF(0.0f, 0.0f, 1.0f, 1.0f);
    private final RectF scratchRect = new RectF();
    private final Rect tempCropRect = new Rect();
    private final ArrayList<Stroke> strokes = new ArrayList<>();

    private Bitmap bitmap;
    private String emptyMessage = "";
    private Listener listener;
    private int toolMode = TOOL_BROWSE;
    private int strokeColor = 0xFFFFD54F;
    private Stroke activeStroke;
    private int activeHandle = HANDLE_NONE;
    private float dragStartNormalizedX;
    private float dragStartNormalizedY;
    private final RectF dragStartCropRect = new RectF();
    private float swipeDownX;
    private float swipeDownY;
    private boolean editStateNotified;

    public MarkupEditorStageView(Context context) {
        super(context);
        init();
    }

    public MarkupEditorStageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarkupEditorStageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(1.5f));
        framePaint.setColor(0x40FFFFFF);

        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setColor(0xFF000000);

        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeCap(Paint.Cap.ROUND);
        handlePaint.setStrokeWidth(dp(3.0f));
        handlePaint.setColor(Color.WHITE);

        cropFramePaint.setStyle(Paint.Style.STROKE);
        cropFramePaint.setStrokeWidth(dp(1.5f));
        cropFramePaint.setColor(0xE6FFFFFF);

        cropOverlayPaint.setStyle(Paint.Style.FILL);
        cropOverlayPaint.setColor(0x88000000);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(dp(4.0f));

        emptyTextPaint.setColor(0x99FFFFFF);
        emptyTextPaint.setTextAlign(Paint.Align.CENTER);
        emptyTextPaint.setTextSize(dp(7.0f));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        resetEdits();
        invalidate();
    }

    public void setEmptyMessage(String emptyMessage) {
        this.emptyMessage = emptyMessage != null ? emptyMessage : "";
        invalidate();
    }

    public void setToolMode(int toolMode) {
        this.toolMode = toolMode;
        activeStroke = null;
        activeHandle = HANDLE_NONE;
        invalidate();
    }

    public int getToolMode() {
        return toolMode;
    }

    public void setStrokeColor(int strokeColor) {
        this.strokeColor = strokeColor;
        invalidate();
    }

    public boolean hasEdits() {
        return !isCropIdentity() || !strokes.isEmpty();
    }

    public Bitmap renderEditedBitmap() {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        Bitmap composed = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(composed);
        canvas.drawBitmap(bitmap, 0.0f, 0.0f, null);
        drawStrokes(canvas, new RectF(0.0f, 0.0f, bitmap.getWidth(), bitmap.getHeight()));

        Rect crop = getCropPixelRect(bitmap.getWidth(), bitmap.getHeight());
        Bitmap cropped = Bitmap.createBitmap(composed, crop.left, crop.top, crop.width(), crop.height());
        if (cropped != composed) {
            composed.recycle();
        }
        return cropped;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float outerPadding = dp(20.0f);
        float innerPadding = dp(10.0f);
        RectF cardRect = new RectF(outerPadding, outerPadding, width - outerPadding, height - outerPadding);
        float cardRadius = dp(18.0f);
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint);

        if (bitmap == null || bitmap.isRecycled()) {
            float centerY = height * 0.5f - ((emptyTextPaint.descent() + emptyTextPaint.ascent()) * 0.5f);
            canvas.drawText(emptyMessage, width * 0.5f, centerY, emptyTextPaint);
            return;
        }

        layoutImageRect(cardRect, innerPadding);
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint);
        drawStrokes(canvas, imageRect);
        canvas.drawRoundRect(imageRect, dp(10.0f), dp(10.0f), framePaint);
        drawCropUi(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null || bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeDownX = event.getX();
                swipeDownY = event.getY();
                if (toolMode == TOOL_PEN && imageRect.contains(event.getX(), event.getY())) {
                    activeStroke = new Stroke(strokeColor);
                    addPointToStroke(activeStroke, event.getX(), event.getY());
                    strokes.add(activeStroke);
                    notifyEditStateIfNeeded();
                    invalidate();
                    return true;
                }
                if (toolMode == TOOL_CROP) {
                    activeHandle = findHandle(event.getX(), event.getY());
                    if (activeHandle != HANDLE_NONE) {
                        PointF normalized = toNormalizedPoint(event.getX(), event.getY(), true);
                        dragStartNormalizedX = normalized.x;
                        dragStartNormalizedY = normalized.y;
                        dragStartCropRect.set(cropRectNormalized);
                        return true;
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activeStroke != null) {
                    addPointToStroke(activeStroke, event.getX(), event.getY());
                    invalidate();
                    return true;
                }
                if (activeHandle != HANDLE_NONE) {
                    updateCropRect(event.getX(), event.getY());
                    notifyEditStateIfNeeded();
                    invalidate();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (activeStroke != null) {
                    addPointToStroke(activeStroke, event.getX(), event.getY());
                    activeStroke = null;
                    invalidate();
                    return true;
                }
                if (activeHandle != HANDLE_NONE) {
                    updateCropRect(event.getX(), event.getY());
                    activeHandle = HANDLE_NONE;
                    invalidate();
                    return true;
                }
                if (toolMode == TOOL_BROWSE) {
                    float deltaX = event.getX() - swipeDownX;
                    float deltaY = event.getY() - swipeDownY;
                    if (Math.abs(deltaX) >= dp(48.0f) && Math.abs(deltaX) > Math.abs(deltaY) * 1.25f
                            && listener != null) {
                        listener.onRequestNavigate(deltaX < 0.0f ? 1 : -1);
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                activeStroke = null;
                activeHandle = HANDLE_NONE;
                return true;
            default:
                return true;
        }
    }

    private void drawCropUi(Canvas canvas) {
        RectF cropDisplayRect = getCropDisplayRect();
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropDisplayRect.top, cropOverlayPaint);
        canvas.drawRect(imageRect.left, cropDisplayRect.bottom, imageRect.right, imageRect.bottom, cropOverlayPaint);
        canvas.drawRect(imageRect.left, cropDisplayRect.top, cropDisplayRect.left, cropDisplayRect.bottom, cropOverlayPaint);
        canvas.drawRect(cropDisplayRect.right, cropDisplayRect.top, imageRect.right, cropDisplayRect.bottom, cropOverlayPaint);
        canvas.drawRect(cropDisplayRect, cropFramePaint);
        drawCropHandles(canvas, cropDisplayRect);
    }

    private void drawStrokes(Canvas canvas, RectF targetRect) {
        for (Stroke stroke : strokes) {
            if (stroke.points.size() < 2) {
                continue;
            }
            Path path = new Path();
            PointF first = stroke.points.get(0);
            path.moveTo(
                    targetRect.left + targetRect.width() * first.x,
                    targetRect.top + targetRect.height() * first.y);
            for (int i = 1; i < stroke.points.size(); i++) {
                PointF point = stroke.points.get(i);
                path.lineTo(
                        targetRect.left + targetRect.width() * point.x,
                        targetRect.top + targetRect.height() * point.y);
            }
            strokePaint.setColor(stroke.color);
            strokePaint.setStrokeWidth(Math.max(dp(4.0f), targetRect.width() * 0.006f));
            canvas.drawPath(path, strokePaint);
        }
    }

    private void drawCropHandles(Canvas canvas, RectF rect) {
        float handle = dp(18.0f);
        drawCorner(canvas, rect.left, rect.top, handle, true, true);
        drawCorner(canvas, rect.right, rect.top, handle, false, true);
        drawCorner(canvas, rect.left, rect.bottom, handle, true, false);
        drawCorner(canvas, rect.right, rect.bottom, handle, false, false);
        drawMidHandle(canvas, rect.centerX(), rect.top, true);
        drawMidHandle(canvas, rect.centerX(), rect.bottom, true);
        drawMidHandle(canvas, rect.left, rect.centerY(), false);
        drawMidHandle(canvas, rect.right, rect.centerY(), false);
    }

    private void drawCorner(Canvas canvas, float x, float y, float size, boolean left, boolean top) {
        float x2 = left ? x + size : x - size;
        float y2 = top ? y + size : y - size;
        canvas.drawLine(x, y, x2, y, handlePaint);
        canvas.drawLine(x, y, x, y2, handlePaint);
    }

    private void drawMidHandle(Canvas canvas, float x, float y, boolean horizontal) {
        float half = dp(10.0f);
        if (horizontal) {
            canvas.drawLine(x - half, y, x + half, y, handlePaint);
        } else {
            canvas.drawLine(x, y - half, x, y + half, handlePaint);
        }
    }

    private void layoutImageRect(RectF cardRect, float innerPadding) {
        float availableWidth = cardRect.width() - innerPadding * 2.0f;
        float availableHeight = cardRect.height() - innerPadding * 2.0f;
        float scale = Math.min(availableWidth / bitmap.getWidth(), availableHeight / bitmap.getHeight());
        float imageWidth = bitmap.getWidth() * scale;
        float imageHeight = bitmap.getHeight() * scale;
        imageRect.set(
                cardRect.centerX() - imageWidth * 0.5f,
                cardRect.centerY() - imageHeight * 0.5f,
                cardRect.centerX() + imageWidth * 0.5f,
                cardRect.centerY() + imageHeight * 0.5f
        );
    }

    private RectF getCropDisplayRect() {
        scratchRect.set(
                imageRect.left + imageRect.width() * cropRectNormalized.left,
                imageRect.top + imageRect.height() * cropRectNormalized.top,
                imageRect.left + imageRect.width() * cropRectNormalized.right,
                imageRect.top + imageRect.height() * cropRectNormalized.bottom
        );
        return scratchRect;
    }

    private Rect getCropPixelRect(int width, int height) {
        tempCropRect.set(
                Math.round(cropRectNormalized.left * width),
                Math.round(cropRectNormalized.top * height),
                Math.round(cropRectNormalized.right * width),
                Math.round(cropRectNormalized.bottom * height)
        );
        int minWidth = Math.min(width, Math.max(1, Math.round(width * 0.05f)));
        int minHeight = Math.min(height, Math.max(1, Math.round(height * 0.05f)));
        if (tempCropRect.width() < minWidth) {
            tempCropRect.right = Math.min(width, tempCropRect.left + minWidth);
        }
        if (tempCropRect.height() < minHeight) {
            tempCropRect.bottom = Math.min(height, tempCropRect.top + minHeight);
        }
        tempCropRect.left = clamp(tempCropRect.left, 0, Math.max(0, width - 1));
        tempCropRect.top = clamp(tempCropRect.top, 0, Math.max(0, height - 1));
        tempCropRect.right = clamp(tempCropRect.right, tempCropRect.left + 1, width);
        tempCropRect.bottom = clamp(tempCropRect.bottom, tempCropRect.top + 1, height);
        return tempCropRect;
    }

    private void updateCropRect(float x, float y) {
        PointF normalized = toNormalizedPoint(x, y, false);
        float dx = normalized.x - dragStartNormalizedX;
        float dy = normalized.y - dragStartNormalizedY;
        float minSize = 0.12f;
        cropRectNormalized.set(dragStartCropRect);
        switch (activeHandle) {
            case HANDLE_MOVE:
                float width = dragStartCropRect.width();
                float height = dragStartCropRect.height();
                float left = clamp(dragStartCropRect.left + dx, 0.0f, 1.0f - width);
                float top = clamp(dragStartCropRect.top + dy, 0.0f, 1.0f - height);
                cropRectNormalized.set(left, top, left + width, top + height);
                break;
            case HANDLE_LEFT:
                cropRectNormalized.left = clamp(dragStartCropRect.left + dx, 0.0f,
                        dragStartCropRect.right - minSize);
                break;
            case HANDLE_TOP:
                cropRectNormalized.top = clamp(dragStartCropRect.top + dy, 0.0f,
                        dragStartCropRect.bottom - minSize);
                break;
            case HANDLE_RIGHT:
                cropRectNormalized.right = clamp(dragStartCropRect.right + dx,
                        dragStartCropRect.left + minSize, 1.0f);
                break;
            case HANDLE_BOTTOM:
                cropRectNormalized.bottom = clamp(dragStartCropRect.bottom + dy,
                        dragStartCropRect.top + minSize, 1.0f);
                break;
            case HANDLE_TOP_LEFT:
                cropRectNormalized.left = clamp(dragStartCropRect.left + dx, 0.0f,
                        dragStartCropRect.right - minSize);
                cropRectNormalized.top = clamp(dragStartCropRect.top + dy, 0.0f,
                        dragStartCropRect.bottom - minSize);
                break;
            case HANDLE_TOP_RIGHT:
                cropRectNormalized.right = clamp(dragStartCropRect.right + dx,
                        dragStartCropRect.left + minSize, 1.0f);
                cropRectNormalized.top = clamp(dragStartCropRect.top + dy, 0.0f,
                        dragStartCropRect.bottom - minSize);
                break;
            case HANDLE_BOTTOM_RIGHT:
                cropRectNormalized.right = clamp(dragStartCropRect.right + dx,
                        dragStartCropRect.left + minSize, 1.0f);
                cropRectNormalized.bottom = clamp(dragStartCropRect.bottom + dy,
                        dragStartCropRect.top + minSize, 1.0f);
                break;
            case HANDLE_BOTTOM_LEFT:
                cropRectNormalized.left = clamp(dragStartCropRect.left + dx, 0.0f,
                        dragStartCropRect.right - minSize);
                cropRectNormalized.bottom = clamp(dragStartCropRect.bottom + dy,
                        dragStartCropRect.top + minSize, 1.0f);
                break;
            default:
                break;
        }
    }

    private int findHandle(float x, float y) {
        RectF cropDisplayRect = getCropDisplayRect();
        float hit = dp(22.0f);
        if (isNearPoint(x, y, cropDisplayRect.left, cropDisplayRect.top, hit)) {
            return HANDLE_TOP_LEFT;
        }
        if (isNearPoint(x, y, cropDisplayRect.right, cropDisplayRect.top, hit)) {
            return HANDLE_TOP_RIGHT;
        }
        if (isNearPoint(x, y, cropDisplayRect.right, cropDisplayRect.bottom, hit)) {
            return HANDLE_BOTTOM_RIGHT;
        }
        if (isNearPoint(x, y, cropDisplayRect.left, cropDisplayRect.bottom, hit)) {
            return HANDLE_BOTTOM_LEFT;
        }
        if (Math.abs(y - cropDisplayRect.top) <= hit && x >= cropDisplayRect.left && x <= cropDisplayRect.right) {
            return HANDLE_TOP;
        }
        if (Math.abs(y - cropDisplayRect.bottom) <= hit && x >= cropDisplayRect.left && x <= cropDisplayRect.right) {
            return HANDLE_BOTTOM;
        }
        if (Math.abs(x - cropDisplayRect.left) <= hit && y >= cropDisplayRect.top && y <= cropDisplayRect.bottom) {
            return HANDLE_LEFT;
        }
        if (Math.abs(x - cropDisplayRect.right) <= hit && y >= cropDisplayRect.top && y <= cropDisplayRect.bottom) {
            return HANDLE_RIGHT;
        }
        if (cropDisplayRect.contains(x, y)) {
            return HANDLE_MOVE;
        }
        return HANDLE_NONE;
    }

    private boolean isNearPoint(float x, float y, float targetX, float targetY, float radius) {
        float dx = x - targetX;
        float dy = y - targetY;
        return (dx * dx) + (dy * dy) <= radius * radius;
    }

    private void addPointToStroke(Stroke stroke, float x, float y) {
        PointF point = toNormalizedPoint(x, y, true);
        stroke.points.add(point);
    }

    private PointF toNormalizedPoint(float x, float y, boolean clampToImage) {
        if (imageRect.width() <= 0.0f || imageRect.height() <= 0.0f) {
            return new PointF(0.0f, 0.0f);
        }
        float normalizedX = (x - imageRect.left) / imageRect.width();
        float normalizedY = (y - imageRect.top) / imageRect.height();
        if (clampToImage) {
            normalizedX = clamp(normalizedX, 0.0f, 1.0f);
            normalizedY = clamp(normalizedY, 0.0f, 1.0f);
        }
        return new PointF(normalizedX, normalizedY);
    }

    private void resetEdits() {
        cropRectNormalized.set(0.0f, 0.0f, 1.0f, 1.0f);
        strokes.clear();
        activeStroke = null;
        activeHandle = HANDLE_NONE;
        notifyEditState(false);
    }

    private boolean isCropIdentity() {
        return Math.abs(cropRectNormalized.left) < 0.0001f
                && Math.abs(cropRectNormalized.top) < 0.0001f
                && Math.abs(cropRectNormalized.right - 1.0f) < 0.0001f
                && Math.abs(cropRectNormalized.bottom - 1.0f) < 0.0001f;
    }

    private void notifyEditStateIfNeeded() {
        notifyEditState(hasEdits());
    }

    private void notifyEditState(boolean hasEdits) {
        if (editStateNotified == hasEdits) {
            return;
        }
        editStateNotified = hasEdits;
        if (listener != null) {
            listener.onEditStateChanged(hasEdits);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class Stroke {
        final int color;
        final List<PointF> points = new ArrayList<>();

        Stroke(int color) {
            this.color = color;
        }
    }
}
