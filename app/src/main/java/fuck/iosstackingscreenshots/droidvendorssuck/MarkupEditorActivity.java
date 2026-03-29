package fuck.iosstackingscreenshots.droidvendorssuck;

import android.app.ActivityManager;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;

public final class MarkupEditorActivity extends Activity {
    static final String EXTRA_SCREENSHOT_URI = "fuck.iosstackingscreenshots.droidvendorssuck.extra.SCREENSHOT_URI";
    static final String EXTRA_SCREENSHOT_BATCH_URIS =
            "fuck.iosstackingscreenshots.droidvendorssuck.extra.SCREENSHOT_BATCH_URIS";
    static final String EXTRA_SCREENSHOT_INDEX =
            "fuck.iosstackingscreenshots.droidvendorssuck.extra.SCREENSHOT_INDEX";

    private MarkupEditorStageView stageView;
    private TextView subtitleView;
    private View topBarView;
    private View toolScrollerView;
    private volatile int loadGeneration;
    private Bitmap loadedBitmap;
    private final ArrayList<Uri> screenshotBatch = new ArrayList<>();
    private int currentScreenshotIndex;
    private float swipeDownX;
    private float swipeDownY;

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
        Button doneButton = findViewById(R.id.done_button);
        doneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        configureInsets(rootView);
        stageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleStageSwipe(event);
            }
        });
        applyIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recycleLoadedBitmap();
    }

    private void applyIntent(Intent intent) {
        populateBatch(intent);
        int requestedIndex = intent != null ? intent.getIntExtra(EXTRA_SCREENSHOT_INDEX, 0) : 0;
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
            showEmptyState();
            return;
        }
        currentScreenshotIndex = clampIndex(requestedIndex, screenshotBatch.size());
        loadScreenshot(screenshotBatch.get(currentScreenshotIndex));
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
    }

    private void updateSubtitleForLoading() {
        subtitleView.setText(getString(
                R.string.editor_loading_batch_subtitle,
                currentScreenshotIndex + 1,
                Math.max(1, screenshotBatch.size())));
    }

    private void updateSubtitleForReady() {
        subtitleView.setText(getString(
                R.string.editor_ready_batch_subtitle,
                currentScreenshotIndex + 1,
                Math.max(1, screenshotBatch.size())));
    }

    private void updateSubtitleForFailure() {
        subtitleView.setText(getString(
                R.string.editor_failed_batch_subtitle,
                currentScreenshotIndex + 1,
                Math.max(1, screenshotBatch.size())));
    }

    private boolean handleStageSwipe(MotionEvent event) {
        if (screenshotBatch.size() <= 1 || event == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeDownX = event.getX();
                swipeDownY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float deltaX = event.getX() - swipeDownX;
                float deltaY = event.getY() - swipeDownY;
                if (Math.abs(deltaX) < dp(48.0f) || Math.abs(deltaX) <= Math.abs(deltaY) * 1.25f) {
                    return true;
                }
                if (deltaX < 0.0f) {
                    showScreenshotAtIndex(currentScreenshotIndex + 1);
                } else {
                    showScreenshotAtIndex(currentScreenshotIndex - 1);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
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
                android.app.ActivityManager.RecentTaskInfo taskInfo = appTask.getTaskInfo();
                if (taskInfo != null && taskInfo.id == getTaskId()) {
                    appTask.setExcludeFromRecents(true);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
