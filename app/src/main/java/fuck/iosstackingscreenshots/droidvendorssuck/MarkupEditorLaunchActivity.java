package fuck.iosstackingscreenshots.droidvendorssuck;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;

public final class MarkupEditorLaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchMarkupEditor(getIntent());
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launchMarkupEditor(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    private void launchMarkupEditor(Intent intent) {
        Intent activityIntent = new Intent(this, MarkupEditorActivity.class);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (intent == null) {
            startActivity(activityIntent);
            return;
        }

        Uri screenshotUri = intent.getParcelableExtra(MarkupEditorActivity.EXTRA_SCREENSHOT_URI);
        if (screenshotUri != null) {
            activityIntent.setData(screenshotUri);
        }

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            activityIntent.setClipData(clipData);
        }

        ArrayList<Uri> batchUris = intent.getParcelableArrayListExtra(
                MarkupEditorActivity.EXTRA_SCREENSHOT_BATCH_URIS);
        if (batchUris != null) {
            activityIntent.putParcelableArrayListExtra(
                    MarkupEditorActivity.EXTRA_SCREENSHOT_BATCH_URIS,
                    batchUris);
        }
        activityIntent.putExtra(
                MarkupEditorActivity.EXTRA_SCREENSHOT_URI,
                screenshotUri);
        activityIntent.putExtra(
                MarkupEditorActivity.EXTRA_SCREENSHOT_INDEX,
                intent.getIntExtra(MarkupEditorActivity.EXTRA_SCREENSHOT_INDEX, 0));

        startActivity(activityIntent);
        overridePendingTransition(0, 0);
    }
}
