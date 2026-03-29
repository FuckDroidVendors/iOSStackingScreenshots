package fuck.iosstackingscreenshots.droidvendorssuck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

public final class MarkupEditorStageView extends View {
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint emptyTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();
    private Bitmap bitmap;
    private String emptyMessage = "";

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

        emptyTextPaint.setColor(0x99FFFFFF);
        emptyTextPaint.setTextAlign(Paint.Align.CENTER);
        emptyTextPaint.setTextSize(dp(7.0f));
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        invalidate();
    }

    public void setEmptyMessage(String emptyMessage) {
        this.emptyMessage = emptyMessage != null ? emptyMessage : "";
        invalidate();
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

        canvas.drawBitmap(bitmap, null, imageRect, imagePaint);
        canvas.drawRoundRect(imageRect, dp(10.0f), dp(10.0f), framePaint);
        drawCropHandles(canvas, imageRect);
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

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
