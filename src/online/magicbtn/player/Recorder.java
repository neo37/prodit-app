package online.magicbtn.player;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 3.4: запись просмотра слайдшоу (⏺ в плеере) — только на телефоне, в своей SQLite (recordings.db).
 * Сегмент = непрерывный кусок показа одного файла: откуда (мс в ролике) и сколько показывали,
 * ключевые кадры зума/сдвига [мс от начала сегмента, зум, сдвигX, сдвигY — в долях экрана].
 * Перемотка, переход к другому файлу, пауза экрана — новый сегмент. 👎 — файл не войдёт в склейку.
 * «Мои просмотры» (RecordingsActivity) склеивают запись в один ролик (Render).
 */
final class Recorder {

    static final class Rec {
        long id, created;
        String title, output;
        int clips, excluded;
        long durationMs;
    }

    static final class Seg {
        String uri, title;
        boolean image, excluded;
        long startMs, durMs;
        JSONArray zoom;   // [[t, z, px, py], …]
    }

    private static Db db;
    /** Идёт запись: id записи, иначе -1. */
    static volatile long current = -1;
    private static long segId = -1, segWallStart;
    private static String segUri;
    private static JSONArray segZoom;
    private static float lastZ = 1, lastX, lastY;

    private Recorder() {
    }

    private static final class Db extends SQLiteOpenHelper {
        Db(Context c) {
            super(c.getApplicationContext(), "recordings.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE rec (id INTEGER PRIMARY KEY, created INTEGER, title TEXT, aspect REAL, output TEXT)");
            d.execSQL("CREATE TABLE seg (id INTEGER PRIMARY KEY, rec INTEGER, uri TEXT, title TEXT, image INTEGER,"
                    + " start_ms INTEGER, dur_ms INTEGER, zoom TEXT)");
            d.execSQL("CREATE TABLE excl (rec INTEGER, uri TEXT, PRIMARY KEY(rec, uri))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase d, int o, int n) {
        }
    }

    private static synchronized SQLiteDatabase w(Context c) {
        if (db == null) db = new Db(c);
        return db.getWritableDatabase();
    }

    static boolean on() {
        return current >= 0;
    }

    /** Начать запись. aspect — ширина/высота экрана (кадр будущего ролика). */
    static void start(Context c, String title, float aspect) {
        ContentValues v = new ContentValues();
        v.put("created", System.currentTimeMillis());
        v.put("title", title == null ? "" : title);
        v.put("aspect", aspect);
        current = w(c).insert("rec", null, v);
    }

    static void stop(Context c, long mediaMs) {
        end(c, mediaMs);
        current = -1;
    }

    /** Показ файла начался с позиции mediaMs (для фото — 0). */
    static void begin(Context c, String uri, String title, boolean image, long mediaMs) {
        if (!on()) return;
        end(c, mediaMs);
        ContentValues v = new ContentValues();
        v.put("rec", current);
        v.put("uri", uri);
        v.put("title", title);
        v.put("image", image ? 1 : 0);
        v.put("start_ms", Math.max(0, mediaMs));
        v.put("dur_ms", 0);
        segId = w(c).insert("seg", null, v);
        segUri = uri;
        segWallStart = System.currentTimeMillis();
        segZoom = new JSONArray();
        if (lastZ > 1.01f) zoom(lastZ, lastX, lastY);   // зум остался с прошлого куска
    }

    /** Показ закончился (видео — на позиции mediaMs; фото — по часам). Пустые куски (<300 мс) выбрасываем. */
    static void end(Context c, long mediaMs) {
        if (segId < 0) return;
        Cursor q = w(c).rawQuery("SELECT image, start_ms FROM seg WHERE id = ?", new String[]{String.valueOf(segId)});
        long dur = 0;
        if (q.moveToFirst()) {
            boolean image = q.getInt(0) == 1;
            dur = image || mediaMs <= 0 ? System.currentTimeMillis() - segWallStart : mediaMs - q.getLong(1);
        }
        q.close();
        if (dur < 300) {
            w(c).delete("seg", "id = ?", new String[]{String.valueOf(segId)});
        } else {
            ContentValues v = new ContentValues();
            v.put("dur_ms", dur);
            v.put("zoom", segZoom == null ? "[]" : segZoom.toString());
            w(c).update("seg", v, "id = ?", new String[]{String.valueOf(segId)});
        }
        segId = -1;
        segUri = null;
    }

    /** Зум/сдвиг изменились (доли экрана). */
    static void zoom(float z, float px, float py) {
        lastZ = z;
        lastX = px;
        lastY = py;
        if (segId < 0 || segZoom == null) return;
        try {
            segZoom.put(new JSONArray().put(System.currentTimeMillis() - segWallStart).put(z).put(px).put(py));
        } catch (Exception ignored) {
        }
    }

    static void resetZoom() {
        lastZ = 1;
        lastX = lastY = 0;
    }

    /** 👎: файл не войдёт в склейку этой записи (повторно — вернуть). Возвращает новое состояние. */
    static boolean toggleExclude(Context c, String uri) {
        if (!on() || uri == null) return false;
        String[] a = {String.valueOf(current), uri};
        if (w(c).delete("excl", "rec = ? AND uri = ?", a) > 0) return false;
        ContentValues v = new ContentValues();
        v.put("rec", current);
        v.put("uri", uri);
        w(c).insert("excl", null, v);
        return true;
    }

    static boolean excluded(Context c, String uri) {
        if (!on() || uri == null) return false;
        Cursor q = w(c).rawQuery("SELECT 1 FROM excl WHERE rec = ? AND uri = ?", new String[]{String.valueOf(current), uri});
        boolean r = q.moveToFirst();
        q.close();
        return r;
    }

    static List<Rec> list(Context c) {
        List<Rec> out = new ArrayList<>();
        Cursor q = w(c).rawQuery("SELECT r.id, r.created, r.title, r.output,"
                + " (SELECT COUNT(*) FROM seg s WHERE s.rec = r.id AND s.dur_ms > 0),"
                + " (SELECT COUNT(*) FROM excl e WHERE e.rec = r.id),"
                + " (SELECT COALESCE(SUM(dur_ms), 0) FROM seg s WHERE s.rec = r.id AND s.uri NOT IN (SELECT uri FROM excl e WHERE e.rec = r.id))"
                + " FROM rec r ORDER BY r.id DESC", null);
        while (q.moveToNext()) {
            Rec r = new Rec();
            r.id = q.getLong(0);
            r.created = q.getLong(1);
            r.title = q.getString(2);
            r.output = q.getString(3);
            r.clips = q.getInt(4);
            r.excluded = q.getInt(5);
            r.durationMs = q.getLong(6);
            out.add(r);
        }
        q.close();
        return out;
    }

    static float aspect(Context c, long rec) {
        Cursor q = w(c).rawQuery("SELECT aspect FROM rec WHERE id = ?", new String[]{String.valueOf(rec)});
        float a = q.moveToFirst() ? q.getFloat(0) : 9f / 16f;
        q.close();
        return a > 0 ? a : 9f / 16f;
    }

    /** Сегменты записи по порядку; excluded — помеченные 👎 (в склейку не идут). */
    static List<Seg> segments(Context c, long rec) {
        List<Seg> out = new ArrayList<>();
        Cursor q = w(c).rawQuery("SELECT uri, title, image, start_ms, dur_ms, zoom,"
                + " EXISTS(SELECT 1 FROM excl e WHERE e.rec = s.rec AND e.uri = s.uri)"
                + " FROM seg s WHERE s.rec = ? AND s.dur_ms > 0 ORDER BY s.id", new String[]{String.valueOf(rec)});
        while (q.moveToNext()) {
            Seg s = new Seg();
            s.uri = q.getString(0);
            s.title = q.getString(1);
            s.image = q.getInt(2) == 1;
            s.startMs = q.getLong(3);
            s.durMs = q.getLong(4);
            try {
                s.zoom = new JSONArray(q.getString(5) == null ? "[]" : q.getString(5));
            } catch (Exception e) {
                s.zoom = new JSONArray();
            }
            s.excluded = q.getInt(6) == 1;
            out.add(s);
        }
        q.close();
        return out;
    }

    static void setOutput(Context c, long rec, String uri) {
        ContentValues v = new ContentValues();
        v.put("output", uri);
        w(c).update("rec", v, "id = ?", new String[]{String.valueOf(rec)});
    }

    static void rename(Context c, long rec, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        w(c).update("rec", v, "id = ?", new String[]{String.valueOf(rec)});
    }

    static void delete(Context c, long rec) {
        String[] a = {String.valueOf(rec)};
        w(c).delete("seg", "rec = ?", a);
        w(c).delete("excl", "rec = ?", a);
        w(c).delete("rec", "id = ?", a);
    }
}
