package com.professor.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

public class ProfessorTradeStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "professor_trades.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "trades";

    private final Context appContext;
    private final File imagesDir;

    public ProfessorTradeStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        appContext = context.getApplicationContext();
        imagesDir = new File(appContext.getFilesDir(), "trade_images");
        if (!imagesDir.exists()) {
            imagesDir.mkdirs();
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "id TEXT PRIMARY KEY," +
                "timestamp INTEGER NOT NULL DEFAULT 0," +
                "type TEXT," +
                "result TEXT," +
                "points REAL NOT NULL DEFAULT 0," +
                "json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trades_timestamp ON " + TABLE + "(timestamp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trades_type ON " + TABLE + "(type)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trades_result ON " + TABLE + "(result)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    @JavascriptInterface
    public synchronized String getAllTrades() {
        SQLiteDatabase db = getReadableDatabase();
        JSONArray arr = new JSONArray();
        Cursor c = db.query(TABLE, new String[]{"json"}, null, null, null, null, "timestamp DESC");
        try {
            while (c.moveToNext()) {
                arr.put(new JSONObject(c.getString(0)));
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return arr.toString();
    }

    @JavascriptInterface
    public synchronized String getAllTradesForBackup() {
        SQLiteDatabase db = getReadableDatabase();
        JSONArray arr = new JSONArray();
        Cursor c = db.query(TABLE, new String[]{"json"}, null, null, null, null, "timestamp DESC");
        try {
            while (c.moveToNext()) {
                JSONObject obj = new JSONObject(c.getString(0));
                inlineImageForBackup(obj);
                arr.put(obj);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return arr.toString();
    }

    @JavascriptInterface
    public synchronized boolean saveBackupFile(String filename) {
        try {
            String safeName = filename == null || filename.isEmpty()
                    ? "Professor_Trades_Backup.json"
                    : filename;
            byte[] bytes = getAllTradesForBackup().getBytes(StandardCharsets.UTF_8);
            OutputStream os = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = appContext.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) os = appContext.getContentResolver().openOutputStream(uri);
            }
            if (os == null) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                os = new FileOutputStream(new File(dir, safeName), false);
            }
            try {
                os.write(bytes);
                os.flush();
            } finally {
                os.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public synchronized String getTrade(String id) {
        if (id == null) return "";
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, new String[]{"json"}, "id=?", new String[]{id}, null, null, null, "1");
        try {
            if (c.moveToFirst()) return c.getString(0);
        } finally {
            c.close();
        }
        return "";
    }

    @JavascriptInterface
    public synchronized int getCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    @JavascriptInterface
    public synchronized boolean upsertTrade(String tradeJson) {
        try {
            JSONObject obj = new JSONObject(tradeJson);
            upsertObject(getWritableDatabase(), obj);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public synchronized boolean replaceTrades(String tradesJson) {
        try {
            JSONArray arr = new JSONArray(tradesJson == null || tradesJson.isEmpty() ? "[]" : tradesJson);
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                HashSet<String> keepIds = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String id = obj.optString("id", "trade_" + System.currentTimeMillis() + "_" + i);
                    obj.put("id", id);
                    keepIds.add(id);
                    upsertObject(db, obj);
                }
                Cursor c = db.query(TABLE, new String[]{"id", "json"}, null, null, null, null, null);
                try {
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        if (!keepIds.contains(id)) {
                            deleteImageFromJson(c.getString(1));
                            db.delete(TABLE, "id=?", new String[]{id});
                        }
                    }
                } finally {
                    c.close();
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public synchronized boolean importTrades(String tradesJson) {
        try {
            JSONArray arr = new JSONArray(tradesJson == null || tradesJson.isEmpty() ? "[]" : tradesJson);
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                clearAllLocked(db);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    if (!obj.has("id") || obj.optString("id").isEmpty()) {
                        obj.put("id", "import_" + System.currentTimeMillis() + "_" + i);
                    }
                    upsertObject(db, obj);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public synchronized boolean deleteTrade(String id) {
        if (id == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        String json = getTrade(id);
        deleteImageFromJson(json);
        return db.delete(TABLE, "id=?", new String[]{id}) > 0;
    }

    @JavascriptInterface
    public synchronized String queryTrades(String type, String result, long fromTs, long toTs, boolean sortAsc, int offset, int limit) {
        SQLiteDatabase db = getReadableDatabase();
        StringBuilder where = new StringBuilder("1=1");
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (type != null && !"all".equals(type)) {
            where.append(" AND type=?");
            args.add(type);
        }
        if (result != null && !"all".equals(result)) {
            where.append(" AND result=?");
            args.add(result);
        }
        if (fromTs > 0) {
            where.append(" AND timestamp>=?");
            args.add(String.valueOf(fromTs));
        }
        if (toTs > 0) {
            where.append(" AND timestamp<=?");
            args.add(String.valueOf(toTs));
        }

        JSONObject out = new JSONObject();
        JSONArray rows = new JSONArray();
        String[] argArray = args.toArray(new String[0]);
        try {
            fillStats(db, out, where.toString(), argArray);
            String order = "timestamp " + (sortAsc ? "ASC" : "DESC");
            Cursor c = db.query(TABLE, new String[]{"json"}, where.toString(), argArray, null, null, order, offset + "," + limit);
            try {
                while (c.moveToNext()) {
                    rows.put(new JSONObject(c.getString(0)));
                }
            } finally {
                c.close();
            }
            out.put("rows", rows);
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    @JavascriptInterface
    public synchronized String saveTradeImage(String id, String dataUrl) {
        if (id == null || dataUrl == null || dataUrl.isEmpty()) return "";
        try {
            String saved = saveImageData(id, dataUrl);
            String json = getTrade(id);
            if (!json.isEmpty()) {
                JSONObject obj = new JSONObject(json);
                deleteImageFromJson(json);
                obj.put("image", saved);
                upsertObject(getWritableDatabase(), obj);
            }
            return saved;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public synchronized boolean deleteTradeImage(String id) {
        if (id == null) return false;
        try {
            String json = getTrade(id);
            if (json.isEmpty()) return false;
            JSONObject obj = new JSONObject(json);
            deleteImageFromJson(json);
            obj.put("image", "");
            upsertObject(getWritableDatabase(), obj);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void upsertObject(SQLiteDatabase db, JSONObject obj) throws Exception {
        String id = obj.optString("id");
        if (id == null || id.isEmpty()) return;
        normalizeImage(obj);
        long timestamp = obj.optLong("timestamp", System.currentTimeMillis());
        String type = obj.optString("type", "");
        String result = obj.optString("result", "");
        double points = obj.optDouble("points", 0);

        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("timestamp", timestamp);
        values.put("type", type);
        values.put("result", result);
        values.put("points", points);
        values.put("json", obj.toString());
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void fillStats(SQLiteDatabase db, JSONObject out, String where, String[] args) throws Exception {
        Cursor count = db.rawQuery("SELECT COUNT(*), " +
                "SUM(CASE WHEN result='ربح' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN result='خسارة' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN result='تعادل' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN result='خسارة' THEN -ABS(points) WHEN result='تعادل' THEN 0 ELSE ABS(points) END) " +
                "FROM " + TABLE + " WHERE " + where, args);
        try {
            if (count.moveToFirst()) {
                out.put("total", count.getInt(0));
                out.put("win", count.isNull(1) ? 0 : count.getInt(1));
                out.put("loss", count.isNull(2) ? 0 : count.getInt(2));
                out.put("draw", count.isNull(3) ? 0 : count.getInt(3));
                out.put("net", count.isNull(4) ? 0 : count.getDouble(4));
            }
        } finally {
            count.close();
        }
    }

    private void clearAllLocked(SQLiteDatabase db) {
        Cursor c = db.query(TABLE, new String[]{"json"}, null, null, null, null, null);
        try {
            while (c.moveToNext()) deleteImageFromJson(c.getString(0));
        } finally {
            c.close();
        }
        db.delete(TABLE, null, null);
    }

    private void normalizeImage(JSONObject obj) throws Exception {
        String image = obj.optString("image", "");
        if (image.startsWith("data:")) {
            obj.put("image", saveImageData(obj.optString("id"), image));
        }
    }

    private String saveImageData(String id, String dataUrl) throws Exception {
        String base64 = dataUrl;
        if (dataUrl.startsWith("data:")) {
            int comma = dataUrl.indexOf(',');
            base64 = comma >= 0 ? dataUrl.substring(comma + 1) : "";
        }
        byte[] bytes = Base64.decode(base64.replaceAll("\\s+", ""), Base64.DEFAULT);
        if (!imagesDir.exists()) imagesDir.mkdirs();
        File file = new File(imagesDir, sanitize(id) + "_" + System.currentTimeMillis() + ".jpg");
        FileOutputStream out = new FileOutputStream(file, false);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return Uri.fromFile(file).toString();
    }

    private void inlineImageForBackup(JSONObject obj) {
        String image = obj.optString("image", "");
        if (!image.startsWith("file:")) return;
        try {
            File file = new File(Uri.parse(image).getPath());
            if (!file.exists()) return;
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            in.close();
            String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            obj.put("image", "data:image/jpeg;base64," + b64);
        } catch (Exception ignored) {
        }
    }

    private void deleteImageFromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String image = obj.optString("image", "");
            if (!image.startsWith("file:")) return;
            File file = new File(Uri.parse(image).getPath());
            if (file.exists() && file.getAbsolutePath().startsWith(imagesDir.getAbsolutePath())) {
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isEmpty()) return "trade";
        return value.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
