package online.magicbtn.player;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Индекс скачанных медиа: url -> файл, плюс метки «в каком архиве видели». */
public class MediaDb extends SQLiteOpenHelper {

    public static class Item {
        public final String url;
        public final File file;
        public final String mime;
        public long size, ts;

        Item(String url, File file, String mime) {
            this.url = url;
            this.file = file;
            this.mime = mime;
        }

        public boolean isVideo() {
            return mime != null && mime.startsWith("video/");
        }
    }

    private static MediaDb instance;

    public static synchronized MediaDb get(Context ctx) {
        if (instance == null) instance = new MediaDb(ctx.getApplicationContext());
        return instance;
    }

    private MediaDb(Context ctx) {
        super(ctx, "media.db", null, 4);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE media (url TEXT PRIMARY KEY, file TEXT NOT NULL, mime TEXT, size INTEGER, ts INTEGER)");
        db.execSQL("CREATE TABLE tag (url TEXT NOT NULL, label TEXT NOT NULL, ts INTEGER, PRIMARY KEY(url, label))");
        db.execSQL("CREATE INDEX tag_label ON tag(label)");
        createPending(db);
        createSocial(db);
    }

    /** Лайки/комментарии: кэш с сервера (работает офлайн) + очередь действий до появления сети. */
    private static void createSocial(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS social (skey TEXT PRIMARY KEY, likes INTEGER, comments INTEGER, liked INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ccache (skey TEXT, name TEXT, text TEXT, ts TEXT, mine INTEGER, author TEXT, cid INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS ccache_k ON ccache(skey)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ops (id INTEGER PRIMARY KEY, api TEXT, key TEXT, action TEXT, body TEXT, ts INTEGER)");
    }

    /** v4: автор (анонимный номер) и id комментария — для блокировки и жалоб. */
    private static void addCommentAuthor(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE ccache ADD COLUMN author TEXT");
        db.execSQL("ALTER TABLE ccache ADD COLUMN cid INTEGER");
    }

    private static void createPending(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pending (url TEXT PRIMARY KEY, label TEXT, page TEXT, ts INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) createPending(db);
        if (oldV < 3) createSocial(db);
        if (oldV < 4 && oldV >= 3) addCommentAuthor(db);
    }

    // ---------- очередь на скачивание (переживает перезапуск и пропажу сети) ----------

    public static class Pending {
        public final String url, label, page;

        Pending(String url, String label, String page) {
            this.url = url;
            this.label = label;
            this.page = page;
        }
    }

    public synchronized void addPending(String url, String label, String page) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("label", label);
        v.put("page", page);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("pending", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized Pending nextPending() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url, label, page FROM pending ORDER BY ts LIMIT 1", null)) {
            return c.moveToFirst() ? new Pending(c.getString(0), c.getString(1), c.getString(2)) : null;
        }
    }

    public synchronized void removePending(String url) {
        getWritableDatabase().delete("pending", "url=?", new String[]{url});
    }

    public synchronized void bumpPending(String url) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().update("pending", v, "url=?", new String[]{url});
    }

    public synchronized java.util.List<String[]> pendingList() {
        java.util.List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url, label FROM pending ORDER BY ts", null)) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
        }
        return out;
    }

    public synchronized int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized File fileFor(String url) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT file FROM media WHERE url=?", new String[]{url})) {
            if (c.moveToFirst()) {
                File f = new File(c.getString(0));
                if (f.exists()) return f;
            }
        }
        return null;
    }

    public synchronized String mimeFor(String url) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT mime FROM media WHERE url=?", new String[]{url})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public synchronized void put(String url, File file, String mime, long size) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("file", file.getAbsolutePath());
        v.put("mime", mime);
        v.put("size", size);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("media", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void tag(String url, String label) {
        if (label == null) return;
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("label", label);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("tag", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Все метки архивов, по которым что-то есть. */
    public synchronized List<String> labels() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT t.label FROM tag t JOIN media m ON m.url=t.url ORDER BY t.label", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** label == null — все медиа. */
    public synchronized List<Item> list(String label) {
        List<Item> out = new ArrayList<>();
        Cursor c = label == null
                ? getReadableDatabase().rawQuery("SELECT url, file, mime, size, ts FROM media ORDER BY ts", null)
                : getReadableDatabase().rawQuery(
                "SELECT m.url, m.file, m.mime, m.size, m.ts FROM media m JOIN tag t ON t.url=m.url WHERE t.label=? ORDER BY t.ts",
                new String[]{label});
        try {
            while (c.moveToNext()) {
                File f = new File(c.getString(1));
                if (!f.exists()) continue;
                Item it = new Item(c.getString(0), f, c.getString(2));
                it.size = c.getLong(3);
                it.ts = c.getLong(4);
                out.add(it);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM media", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized long totalBytes() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(size),0) FROM media", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** Метки файла (в какой подборке/источнике лежит). */
    public synchronized List<String> labelsOf(String url) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT label FROM tag WHERE url=?", new String[]{url})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** Переместить сохранённый файл в другую подборку (офлайн, только на устройстве). */
    public synchronized void move(String url, String from, String to) {
        if (from != null) getWritableDatabase().delete("tag", "url=? AND label=?", new String[]{url, from});
        else getWritableDatabase().delete("tag", "url=?", new String[]{url});
        tag(url, to);
    }

    /** Офлайн-файлы → расширение .j01 (галерея и другие приложения их не видят). */
    public synchronized int renameToJ01() {
        int n = 0;
        List<String[]> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url, file FROM media WHERE file NOT LIKE '%.j01'", null)) {
            while (c.moveToNext()) rows.add(new String[]{c.getString(0), c.getString(1)});
        }
        for (String[] r : rows) {
            File f = new File(r[1]);
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            File to = new File(f.getParentFile(), (dot > 0 ? base.substring(0, dot) : base) + ".j01");
            if (f.exists() && f.renameTo(to)) {
                ContentValues v = new ContentValues();
                v.put("file", to.getAbsolutePath());
                getWritableDatabase().update("media", v, "url=?", new String[]{r[0]});
                n++;
            }
        }
        return n;
    }

    // ---------- лайки и комментарии ----------

    public static final class Stat {
        public int likes, comments;
        public boolean liked;
    }

    public synchronized Stat stat(String skey) {
        Stat s = new Stat();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT likes, comments, liked FROM social WHERE skey=?", new String[]{skey})) {
            if (c.moveToFirst()) {
                s.likes = c.getInt(0);
                s.comments = c.getInt(1);
                s.liked = c.getInt(2) != 0;
            }
        }
        return s;
    }

    public synchronized void putStat(String skey, int likes, int comments, boolean liked) {
        ContentValues v = new ContentValues();
        v.put("skey", skey);
        v.put("likes", likes);
        v.put("comments", comments);
        v.put("liked", liked ? 1 : 0);
        getWritableDatabase().insertWithOnConflict("social", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void putComments(String skey, List<String[]> items) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("ccache", "skey=?", new String[]{skey});
        for (String[] it : items) {
            ContentValues v = new ContentValues();
            v.put("skey", skey);
            v.put("name", it[0]);
            v.put("text", it[1]);
            v.put("ts", it[2]);
            v.put("mine", "1".equals(it[3]) ? 1 : 0);
            v.put("author", it.length > 4 ? it[4] : "");
            v.put("cid", it.length > 5 ? it[5] : "0");
            db.insert("ccache", null, v);
        }
    }

    public synchronized void addComment(String skey, String name, String text, String ts, boolean mine) {
        ContentValues v = new ContentValues();
        v.put("skey", skey);
        v.put("name", name);
        v.put("text", text);
        v.put("ts", ts);
        v.put("mine", mine ? 1 : 0);
        getWritableDatabase().insert("ccache", null, v);
    }

    /** [name, text, ts, mine, author, cid] — новые сверху. */
    public synchronized List<String[]> comments(String skey) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT name, text, ts, mine, author, cid FROM ccache WHERE skey=? ORDER BY ts DESC", new String[]{skey})) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4) == null ? "" : c.getString(4), c.getString(5) == null ? "0" : c.getString(5)});
        }
        return out;
    }

    public synchronized void addOp(String api, String key, String action, String body) {
        ContentValues v = new ContentValues();
        v.put("api", api);
        v.put("key", key);
        v.put("action", action);
        v.put("body", body);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("ops", null, v);
    }

    /** [id, api, key, action, body] по порядку. */
    public synchronized List<String[]> ops() {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, api, key, action, body FROM ops ORDER BY id", null)) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)});
        }
        return out;
    }

    public synchronized void removeOp(String id) {
        getWritableDatabase().delete("ops", "id=?", new String[]{id});
    }

    public synchronized void delete(String url) {
        File f = fileFor(url);
        if (f != null) f.delete();
        getWritableDatabase().delete("media", "url=?", new String[]{url});
        getWritableDatabase().delete("tag", "url=?", new String[]{url});
    }
}
