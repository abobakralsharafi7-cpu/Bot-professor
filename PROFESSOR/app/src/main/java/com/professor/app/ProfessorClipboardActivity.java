package com.professor.app;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;

public class ProfessorClipboardActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String text = "";
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
                text = clip == null ? "" : clip.toString();
            }
        } catch (Exception ignored) {}
        ProfessorBubbleService.deliverClipboardText(text);
        finish();
        overridePendingTransition(0, 0);
    }
}
