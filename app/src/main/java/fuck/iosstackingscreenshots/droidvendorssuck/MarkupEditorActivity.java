package fuck.iosstackingscreenshots.droidvendorssuck;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

public final class MarkupEditorActivity extends Activity implements MarkupEditorStageView.Listener {
    static final String EXTRA_SCREENSHOT_URI = "fuck.iosstackingscreenshots.droidvendorssuck.extra.SCREENSHOT_URI";
    static final String EXTRA_SCREENSHOT_BATCH_URIS =
            "fuck.iosstackingscreenshots.droidvendorssuck.extra.SCREENSHOT_BATCH_URIS";
    static final String EXTRA_SCREENSHOT_INDEX =
            "fuck.iosstackingscreenshots.droidvendorssuck.extra.SCREENSHOT_INDEX";

    private static final int[] SWATCH_COLORS = new int[]{
            0xFFFFFFFF,
            0xFFFFD54F,
            0xFFFF6B6B,
            0xFF5ED8A5,
            0xFF59B7FF,
            0xFFC792EA
    };

    private MarkupEditorStageView stageView;
    private TextView subtitleView;
    private View topBarView;
    private View toolScrollerView;
    private Button shareButton;
    private Button penButton;
    private Button cropButton;
    private LinearLayout colorRow;
    private volatile int loadGeneration;
    private Bitmap loadedBitmap;
    private final ArrayList<Uri> screenshotBatch = new ArrayList<>();
    private final ArrayList<View> colorSwatches = new ArrayList<>();
    private int currentScreenshotIndex;
    private Uri currentScreenshotUri;
    private int selectedColor = SWATCH_COLORS[1];
    private boolean saveInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_markup_editor);
        getWindow().setStatusBarColor(0xFF090B10);
        getWindow().setNavigationBarColor(0xFF090B10);
        excludeTaskFromRecents();

        View rootView = findViewById(R.id.editor_root);
        stageView = findViewById(R.id.editor_stage);
        subtitleView = findViewById(R.id.editor_subtitle);
        topBarView = findViewById(R.id.editor_top_bar);
        toolScrollerView = findViewById(R.id.editor_tool_scroller);
        shareButton = findViewById(R.id.share_button);
        penButton = findViewById(R.id.tool_pen_button);
        cropButton = findViewById(R.id.tool_crop_button);
        colorRow = findViewById(R.id.editor_color_row);

        stageView.setListener(this);
        stageView.setStrokeColor(selectedColor);
        stageView.setToolMode(MarkupEditorStageView.TOOL_BROWSE);

        Button doneButton = findViewById(R.id.done_button);
        doneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDoneDialog();
            }
        });
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareCurrentScreenshot();
            }
        });
        penButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTool(MarkupEditorStageView.TOOL_PEN);
            }
        });
        cropButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTool(MarkupEditorStageView.TOOL_CROP);
            }
        });

        buildColorSwatches();
        updateToolButtons();
        configureInsets(rootView);
        applyIntent(getIntent(), false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recycleLoadedBitmap();
    }

    @Override
    public void onRequestNavigate(int direction) {
        if (direction == 0) {
            return;
        }
        showScreenshotAtIndex(currentScreenshotIndex + direction);
    }

    @Override
    public void onEditStateChanged(boolean hasEdits) {
        updateSubtitleForReady();
    }

    private void applyIntent(Intent intent, boolean preserveCurrentSelection) {
        Uri previousUri = preserveCurrentSelection ? currentScreenshotUri : null;
        populateBatch(intent);
        if (screenshotBatch.isEmpty()) {
            currentScreenshotUri = null;
            currentScreenshotIndex = 0;
            showEmptyState();
            return;
        }

        int requestedIndex = intent != null ? intent.getIntExtra(EXTRA_SCREENSHOT_INDEX, 0) : 0;
        if (previousUri != null) {
            int preservedIndex = screenshotBatch.indexOf(previousUri);
            if (preservedIndex >= 0) {
                requestedIndex = preservedIndex;
            }
        }
        loadScreenshotAtIndex(requestedIndex);
    }

    private void populateBatch(Intent intent) {
        screenshotBatch.clear();
        if (intent != null) {
            ArrayList<Uri> batchUris = intent.getParcelableArrayListExtra(EXTRA_SCREENSHOT_BATCH_URIS);
            if (batchUris != null) {
                for (Uri uri : batchUris) {
                    if (uri != null && !screenshotBatch.contains(uri)) {
                        screenshotBatch.add(uri);
                    }
                }
            }
            Uri selectedUri = intent.getParcelableExtra(EXTRA_SCREENSHOT_URI);
            if (selectedUri != null && !screenshotBatch.contains(selectedUri)) {
                screenshotBatch.add(0, selectedUri);
            }
        }
    }

    private void loadScreenshotAtIndex(int requestedIndex) {
        if (screenshotBatch.isEmpty()) {
            currentScreenshotIndex = 0;
            currentScreenshotUri = null;
            showEmptyState();
            return;
        }
        currentScreenshotIndex = clampIndex(requestedIndex, screenshotBatch.size());
        currentScreenshotUri = screenshotBatch.get(currentScreenshotIndex);
        loadScreenshot(currentScreenshotUri);
    }

    private void loadScreenshot(final Uri screenshotUri) {
        if (screenshotUri == null) {
            showEmptyState();
            return;
        }

        final int generation = ++loadGeneration;
        stageView.setBitmap(null);
        stageView.setEmptyMessage(getString(R.string.editor_loading_state));
        updateSubtitleForLoading();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), screenshotUri);
                    final Bitmap bitmap = ImageDecoder.decodeBitmap(source, new ImageDecoder.OnHeaderDecodedListener() {
                        @Override
                        public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
                                ImageDecoder.Source src) {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (generation != loadGeneration) {
                                if (bitmap != null && !bitmap.isRecycled()) {
                                    bitmap.recycle();
                                }
                                return;
                            }
                            recycleLoadedBitmap();
                            loadedBitmap = bitmap;
                            stageView.setBitmap(bitmap);
                            updateSubtitleForReady();
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (generation != loadGeneration) {
                                return;
                            }
                            stageView.setBitmap(null);
                            stageView.setEmptyMessage(getString(R.string.editor_failed_state));
                            updateSubtitleForFailure();
                        }
                    });
                }
            }
        }, "markup-editor-load").start();
    }

    private void recycleLoadedBitmap() {
        if (loadedBitmap != null && !loadedBitmap.isRecycled()) {
            loadedBitmap.recycle();
        }
        loadedBitmap = null;
    }

    private void showEmptyState() {
        recycleLoadedBitmap();
        stageView.setBitmap(null);
        stageView.setEmptyMessage(getString(R.string.editor_empty_state));
        subtitleView.setText(R.string.editor_empty_subtitle);
        shareButton.setEnabled(false);
    }

    private void updateSubtitleForLoading() {
        subtitleView.setText(getString(
                R.string.editor_loading_batch_subtitle,
                currentScreenshotIndex + 1,
                Math.max(1, screenshotBatch.size())));
    }

    private void updateSubtitleForReady() {
        shareButton.setEnabled(currentScreenshotUri != null && !saveInFlight);
        int toolMode = stageView.getToolMode();
        if (toolMode == MarkupEditorStageView.TOOL_PEN) {
            subtitleView.setText(R.string.editor_tool_active_pen);
            return;
        }
        if (toolMode == MarkupEditorStageView.TOOL_CROP) {
            subtitleView.setText(R.string.editor_tool_active_crop);
            return;
        }
        subtitleView.setText(getString(
                R.string.editor_tool_active_browse,
                currentScreenshotIndex + 1,
                Math.max(1, screenshotBatch.size())));
    }

    private void updateSubtitleForFailure() {
        subtitleView.setText(getString(
                R.string.editor_failed_batch_subtitle,
                currentScreenshotIndex + 1,
                Math.max(1, screenshotBatch.size())));
        shareButton.setEnabled(false);
    }

    private void toggleTool(int requestedTool) {
        if (stageView.getToolMode() == requestedTool) {
            stageView.setToolMode(MarkupEditorStageView.TOOL_BROWSE);
        } else {
            stageView.setToolMode(requestedTool);
        }
        updateToolButtons();
        updateSubtitleForReady();
    }

    private void updateToolButtons() {
        styleToolButton(penButton, stageView.getToolMode() == MarkupEditorStageView.TOOL_PEN);
        styleToolButton(cropButton, stageView.getToolMode() == MarkupEditorStageView.TOOL_CROP);
        updateColorSwatchSelection();
    }

    private void styleToolButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                selected ? 0xFF1A73E8 : 0xFF1F252D));
        button.setTextColor(0xFFFFFFFF);
    }

    private void buildColorSwatches() {
        colorRow.removeAllViews();
        colorSwatches.clear();
        final int size = Math.round(dp(28.0f));
        final int marginEnd = Math.round(dp(10.0f));
        for (int i = 0; i < SWATCH_COLORS.length; i++) {
            final int color = SWATCH_COLORS[i];
            View swatch = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.rightMargin = marginEnd;
            swatch.setLayoutParams(params);
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedColor = color;
                    stageView.setStrokeColor(color);
                    updateColorSwatchSelection();
                }
            });
            colorSwatches.add(swatch);
            colorRow.addView(swatch);
        }
        updateColorSwatchSelection();
    }

    private void updateColorSwatchSelection() {
        for (int i = 0; i < colorSwatches.size(); i++) {
            View swatch = colorSwatches.get(i);
            int color = SWATCH_COLORS[i];
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(color);
            background.setStroke(
                    Math.round(dp(color == selectedColor ? 3.0f : 1.5f)),
                    color == selectedColor ? 0xFFFFFFFF : 0x66FFFFFF);
            swatch.setBackground(background);
            swatch.setAlpha(stageView.getToolMode() == MarkupEditorStageView.TOOL_CROP ? 0.45f : 1.0f);
        }
    }

    private void showDoneDialog() {
        if (currentScreenshotUri == null) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_save_changes_title)
                .setMessage(R.string.editor_save_changes_message)
                .setPositiveButton(R.string.editor_save, (dialog, which) -> saveCurrentScreenshot(true, false))
                .setNeutralButton(R.string.editor_delete, (dialog, which) -> deleteCurrentScreenshot())
                .setNegativeButton(R.string.editor_cancel, null)
                .show();
    }

    private void shareCurrentScreenshot() {
        saveCurrentScreenshot(false, true);
    }

    private void saveCurrentScreenshot(final boolean finishAfterSave, final boolean shareAfterSave) {
        if (saveInFlight || currentScreenshotUri == null || loadedBitmap == null) {
            return;
        }
        saveInFlight = true;
        shareButton.setEnabled(false);
        subtitleView.setText(R.string.editor_saving);

        final Uri targetUri = currentScreenshotUri;
        final Bitmap rendered = stageView.renderEditedBitmap();
        if (rendered == null) {
            saveInFlight = false;
            updateSubtitleForFailure();
            Toast.makeText(this, R.string.editor_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try (OutputStream outputStream = getContentResolver().openOutputStream(targetUri, "wt")) {
                    if (outputStream != null) {
                        success = rendered.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                        outputStream.flush();
                    }
                } catch (IOException | RuntimeException ignored) {
                    success = false;
                } finally {
                    if (!rendered.isRecycled()) {
                        rendered.recycle();
                    }
                }

                final boolean finalSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        saveInFlight = false;
                        shareButton.setEnabled(currentScreenshotUri != null);
                        if (!finalSuccess) {
                            updateSubtitleForReady();
                            Toast.makeText(MarkupEditorActivity.this,
                                    R.string.editor_save_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (shareAfterSave) {
                            launchShareSheet(targetUri);
                        } else {
                            Toast.makeText(MarkupEditorActivity.this,
                                    R.string.editor_saved, Toast.LENGTH_SHORT).show();
                        }
                        loadScreenshot(targetUri);
                        if (finishAfterSave) {
                            finish();
                        }
                    }
                });
            }
        }, "markup-editor-save").start();
    }

    private void launchShareSheet(Uri shareUri) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.editor_share)));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.editor_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteCurrentScreenshot() {
        if (currentScreenshotUri == null || saveInFlight) {
            return;
        }
        final Uri targetUri = currentScreenshotUri;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    success = getContentResolver().delete(targetUri, null, null) > 0;
                } catch (RuntimeException ignored) {
                    success = false;
                }
                final boolean finalSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!finalSuccess) {
                            Toast.makeText(MarkupEditorActivity.this,
                                    R.string.editor_delete_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        screenshotBatch.remove(targetUri);
                        Toast.makeText(MarkupEditorActivity.this,
                                R.string.editor_deleted, Toast.LENGTH_SHORT).show();
                        if (screenshotBatch.isEmpty()) {
                            finish();
                            return;
                        }
                        int nextIndex = clampIndex(currentScreenshotIndex, screenshotBatch.size());
                        loadScreenshotAtIndex(nextIndex);
                    }
                });
            }
        }, "markup-editor-delete").start();
    }

    private void showScreenshotAtIndex(int index) {
        int clampedIndex = clampIndex(index, screenshotBatch.size());
        if (clampedIndex == currentScreenshotIndex) {
            return;
        }
        loadScreenshotAtIndex(clampedIndex);
    }

    private int clampIndex(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        if (index < 0) {
            return 0;
        }
        if (index >= size) {
            return size - 1;
        }
        return index;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void configureInsets(View rootView) {
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        final int rootPaddingLeft = rootView.getPaddingLeft();
        final int rootPaddingTop = rootView.getPaddingTop();
        final int rootPaddingRight = rootView.getPaddingRight();
        final int rootPaddingBottom = rootView.getPaddingBottom();
        final int topBarPaddingTop = topBarView.getPaddingTop();
        final int toolPaddingBottom = toolScrollerView.getPaddingBottom();

        rootView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(
                        rootPaddingLeft + systemBars.left,
                        rootPaddingTop,
                        rootPaddingRight + systemBars.right,
                        rootPaddingBottom
                );
                topBarView.setPadding(
                        topBarView.getPaddingLeft(),
                        topBarPaddingTop + systemBars.top,
                        topBarView.getPaddingRight(),
                        topBarView.getPaddingBottom()
                );
                toolScrollerView.setPadding(
                        toolScrollerView.getPaddingLeft(),
                        toolScrollerView.getPaddingTop(),
                        toolScrollerView.getPaddingRight(),
                        toolPaddingBottom + systemBars.bottom
                );
                return insets;
            }
        });
        rootView.requestApplyInsets();
    }

    private void excludeTaskFromRecents() {
        try {
            ActivityManager activityManager = getSystemService(ActivityManager.class);
            if (activityManager == null) {
                return;
            }
            for (ActivityManager.AppTask appTask : activityManager.getAppTasks()) {
                ActivityManager.RecentTaskInfo taskInfo = appTask.getTaskInfo();
                if (taskInfo != null && taskInfo.id == getTaskId()) {
                    appTask.setExcludeFromRecents(true);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
