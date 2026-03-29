package fuck.iosstackingscreenshots.droidvendorssuck;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.IOException;

public final class MarkupEditorActivity extends Activity {
    static final String EXTRA_SCREENSHOT_URI = "fuck.iosstackingscreenshots.droidvendorssuck.extra.SCREENSHOT_URI";

    private MarkupEditorStageView stageView;
    private TextView subtitleView;
    private volatile int loadGeneration;
    private Bitmap loadedBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_markup_editor);
        getWindow().setStatusBarColor(0xFF090B10);
        getWindow().setNavigationBarColor(0xFF090B10);

        stageView = findViewById(R.id.editor_stage);
        subtitleView = findViewById(R.id.editor_subtitle);
        Button doneButton = findViewById(R.id.done_button);
        doneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        loadScreenshot(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadScreenshot(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recycleLoadedBitmap();
    }

    private void loadScreenshot(Intent intent) {
        final Uri screenshotUri = intent != null ? intent.getParcelableExtra(EXTRA_SCREENSHOT_URI) : null;
        if (screenshotUri == null) {
            stageView.setBitmap(null);
            stageView.setEmptyMessage(getString(R.string.editor_empty_state));
            subtitleView.setText(R.string.editor_empty_subtitle);
            return;
        }

        final int generation = ++loadGeneration;
        stageView.setBitmap(null);
        stageView.setEmptyMessage(getString(R.string.editor_loading_state));
        subtitleView.setText(getString(R.string.editor_uri_label, screenshotUri.toString()));

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
                            subtitleView.setText(R.string.editor_ready_subtitle);
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
                            subtitleView.setText(R.string.editor_failed_subtitle);
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
}
