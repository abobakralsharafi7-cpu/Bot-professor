package com.professor.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

public class FileChooserActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 101;
    private static final int TARGET_IMAGE_BYTES = 33 * 1024;
    private static final int MAX_DECODE_DIMENSION = 1600;

    private boolean imageChooser = false;
    private boolean processingNativeImage = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String[] acceptTypes = getIntent().getStringArrayExtra("ACCEPT_TYPES");
        imageChooser = isImageChooser(acceptTypes);
        if (acceptTypes != null && acceptTypes.length > 0) {
            intent.setType(acceptTypes[0]);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes);
        } else {
            intent.setType("*/*");
        }
        startActivityForResult(Intent.createChooser(intent, "اختر الملف أو الصورة"), FILE_CHOOSER_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri selectedUri = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (imageChooser && selectedUri != null) {
                processImageNatively(selectedUri);
                return;
            }

            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    result = new Uri[]{Uri.parse(dataString)};
                }
            }
            if (ProfessorBubbleService.filePathCallback != null) {
                ProfessorBubbleService.filePathCallback.onReceiveValue(result);
                ProfessorBubbleService.filePathCallback = null;
            }
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!processingNativeImage && ProfessorBubbleService.filePathCallback != null) {
            ProfessorBubbleService.filePathCallback.onReceiveValue(null);
            ProfessorBubbleService.filePathCallback = null;
        }
    }

    private boolean isImageChooser(String[] acceptTypes) {
        if (acceptTypes == null) return false;
        for (String type : acceptTypes) {
            if (type == null) continue;
            String lower = type.toLowerCase(java.util.Locale.ROOT).trim();
            if (lower.startsWith("image/") || lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp") || lower.contains(".gif") || lower.contains(".bmp") || lower.contains(".heic") || lower.contains(".heif")) {
                return true;
            }
        }
        return false;
    }

    private void processImageNatively(Uri uri) {
        processingNativeImage = true;
        new Thread(() -> {
            String dataUrl = null;
            try {
                android.graphics.Bitmap bitmap = decodeBitmap(uri);
                if (bitmap != null) {
                    dataUrl = compressBitmapToDataUrl(bitmap, TARGET_IMAGE_BYTES);
                    bitmap.recycle();
                }
            } catch (Exception ignored) {
                dataUrl = null;
            }

            String finalDataUrl = dataUrl;
            runOnUiThread(() -> {
                if (ProfessorBubbleService.filePathCallback != null) {
                    ProfessorBubbleService.filePathCallback.onReceiveValue(null);
                    ProfessorBubbleService.filePathCallback = null;
                }
                if (finalDataUrl != null && !finalDataUrl.isEmpty()) {
                    ProfessorBubbleService.deliverUploadedImageDataUrl(finalDataUrl);
                } else {
                    ProfessorBubbleService.deliverUploadedImageError("حدث خطأ أثناء معالجة الصورة. جرّب صورة JPG أو PNG أو WEBP من المعرض.");
                }
                processingNativeImage = false;
                finish();
            });
        }).start();
    }

    private android.graphics.Bitmap decodeBitmap(Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(getContentResolver(), uri);
            return android.graphics.ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                android.util.Size size = info.getSize();
                int width = Math.max(1, size.getWidth());
                int height = Math.max(1, size.getHeight());
                float scale = Math.min(1f, (float) MAX_DECODE_DIMENSION / (float) Math.max(width, height));
                decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }

        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        java.io.InputStream boundsStream = getContentResolver().openInputStream(uri);
        try {
            android.graphics.BitmapFactory.decodeStream(boundsStream, null, bounds);
        } finally {
            if (boundsStream != null) boundsStream.close();
        }

        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
        opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_DIMENSION);
        java.io.InputStream stream = getContentResolver().openInputStream(uri);
        try {
            return android.graphics.BitmapFactory.decodeStream(stream, null, opts);
        } finally {
            if (stream != null) stream.close();
        }
    }

    private int calculateInSampleSize(int width, int height, int maxDimension) {
        int inSampleSize = 1;
        int largest = Math.max(width, height);
        while (largest / inSampleSize > maxDimension) {
            inSampleSize *= 2;
        }
        return Math.max(1, inSampleSize);
    }

    private String compressBitmapToDataUrl(android.graphics.Bitmap source, int targetBytes) throws Exception {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) return "";

        float initialScale = Math.min(1f, 900f / (float) Math.max(width, height));
        int currentWidth = Math.max(48, Math.round(width * initialScale));
        int currentHeight = Math.max(48, Math.round(height * initialScale));
        int[] qualities = new int[]{82, 72, 62, 52, 42, 34, 28, 22, 16, 10, 6};
        byte[] best = null;

        for (int round = 0; round < 24; round++) {
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createBitmap(currentWidth, currentHeight, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(scaled);
            canvas.drawColor(android.graphics.Color.WHITE);
            android.graphics.Rect dst = new android.graphics.Rect(0, 0, currentWidth, currentHeight);
            canvas.drawBitmap(source, null, dst, null);

            for (int quality : qualities) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out);
                byte[] bytes = out.toByteArray();
                if (best == null || bytes.length < best.length) best = bytes;
                if (bytes.length <= targetBytes) {
                    scaled.recycle();
                    return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                }
            }
            scaled.recycle();

            currentWidth = Math.max(32, Math.round(currentWidth * 0.82f));
            currentHeight = Math.max(32, Math.round(currentHeight * 0.82f));
            if (currentWidth <= 32 && currentHeight <= 32) break;
        }

        if (best == null) return "";
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(best, android.util.Base64.NO_WRAP);
    }
}