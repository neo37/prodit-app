package online.magicbtn.player;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Лайки, комментарии и жалобы на файлы источников с video_api.php.
 * Онлайн — сразу на сервер; офлайн — в локальный кэш и в очередь (MediaDb.ops), отправка при появлении сети.
 * Пользователь — случайный X-UID устройства (без регистрации).
 */
final class Social {

    /** Где лежит файл: адрес API, папка, имя, ключ. */
    static final class Ref {
        final String api, folder, file, key;

        Ref(String api, String folder, String file, String key) {
            this.api = api;
            this.folder = folder;
            this.file = file;
            this.key = key == null ? "" : key;
        }

        String skey() {
            return api + "|" + folder + "|" + file;
        }

        /** Видео с телефона: лайки и комментарии хранятся только на устройстве (3.4). */
        boolean local() {
            return LOCAL.equals(api);
        }
    }

    static final String LOCAL = "local";

    private static final ExecutorService bg = Executors.newSingleThreadExecutor();

    private Social() {
    }

    static String uid(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("social", 0);
        String u = p.getString("uid", null);
        if (u == null) {
            u = UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("uid", u).apply();
        }
        return u;
    }

    /** Мой анонимный номер автора — как его считает сервер: sha1("author:" + X-UID), 16 знаков. */
    static String author(Context c) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(("author:" + uid(c)).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", h[i]));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String name(Context c) {
        return c.getSharedPreferences("social", 0).getString("name", "");
    }

    static void setName(Context c, String n) {
        c.getSharedPreferences("social", 0).edit().putString("name", n).apply();
        if (n != null && !n.isEmpty()) Contacts.hello(c, true); // новое имя — в каталог «Все пользователи»
    }

    /**
     * Ссылка на файл по его URL и метке «хост/папка»: …video_api.php?action=stream&folder=F&file=X
     * или /media/X (hn). null — источник без API лайков (сайт в WebView, телефон).
     */
    static Ref ref(Context c, String url, String label) {
        if (url == null) return null;
        // видео с телефона (content://, file://) — свои лайки и комментарии, без сервера
        if (url.startsWith("content://") || url.startsWith("file://") || url.startsWith("/"))
            return new Ref(LOCAL, "phone", url, "");
        if (!url.startsWith("http")) return null;
        Uri u = Uri.parse(url);
        String host = u.getHost();
        String folder = u.getQueryParameter("folder");
        String file = u.getQueryParameter("file");
        String api;
        if (u.getPath() != null && u.getPath().endsWith("video_api.php") && folder != null && file != null) {
            api = u.getScheme() + "://" + u.getAuthority() + u.getPath();
        } else {
            if (label == null || host == null || !label.startsWith(host + "/")) return null;
            folder = label.substring(host.length() + 1);
            file = u.getLastPathSegment();
            api = "https://" + host + "/video_api.php";
        }
        String key = "";
        for (Sources.Source s : Sources.all(c)) if (s.isApi() && s.apiBase().equals(api)) key = s.key;
        return file == null ? null : new Ref(api, folder, file, key);
    }

    // ---------- HTTP ----------

    static JSONObject call(Context c, String api, String key, String query, String method, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(api + "?" + query).openConnection();
        h.setConnectTimeout(12000);
        h.setReadTimeout(20000);
        h.setRequestMethod(method);
        h.setRequestProperty("X-UID", uid(c));
        if (key != null && !key.isEmpty()) h.setRequestProperty("X-API-Key", key);
        if (body != null) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = h.getOutputStream()) {
                o.write(body.getBytes("UTF-8"));
            }
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        h.disconnect();
        JSONObject o = new JSONObject(b.size() == 0 ? "{}" : b.toString("UTF-8"));
        if (code >= 500) throw new Exception("HTTP " + code);
        return o;
    }

    private static String body(Ref r, String... kv) throws Exception {
        JSONObject o = new JSONObject().put("folder", r.folder).put("file", r.file);
        for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        return o.toString();
    }

    // ---------- действия ----------

    /** Обновить счётчики всей папки с сервера в кэш (для сортировки). Не в UI-потоке. */
    static void refreshStats(Context c, String api, String key, String folder) {
        try {
            JSONObject o = call(c, api, key, "action=stats&folder=" + Uri.encode(folder), "GET", null);
            JSONObject st = o.optJSONObject("stats");
            if (st == null) return;
            MediaDb db = MediaDb.get(c);
            for (Iterator<String> it = st.keys(); it.hasNext(); ) {
                String f = it.next();
                JSONObject s = st.getJSONObject(f);
                db.putStat(api + "|" + folder + "|" + f, s.optInt("likes"), s.optInt("comments"), s.optBoolean("liked"));
            }
        } catch (Exception ignored) {
        }
    }

    /** Лайк/снятие: сразу в кэш (виден офлайн), затем на сервер или в очередь. */
    static MediaDb.Stat toggleLike(Context c, Ref r) {
        MediaDb db = MediaDb.get(c);
        MediaDb.Stat s = db.stat(r.skey());
        s.liked = !s.liked;
        s.likes = Math.max(0, s.likes + (s.liked ? 1 : -1));
        db.putStat(r.skey(), s.likes, s.comments, s.liked);
        if (r.local()) return s;
        try {
            db.addOp(r.api, r.key, "like", body(r));
        } catch (Exception ignored) {
        }
        flush(c);
        return s;
    }

    static void addComment(Context c, Ref r, String text) {
        MediaDb db = MediaDb.get(c);
        String nm = name(c);
        db.addComment(r.skey(), nm.isEmpty() ? "—" : nm, text, new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()), true);
        MediaDb.Stat s = db.stat(r.skey());
        db.putStat(r.skey(), s.likes, s.comments + 1, s.liked);
        if (r.local()) return;
        try {
            db.addOp(r.api, r.key, "comment", body(r, "text", text, "name", nm));
        } catch (Exception ignored) {
        }
        flush(c);
    }

    static void report(Context c, String api, String key, String kind, String ref, String reason) {
        try {
            MediaDb.get(c).addOp(api, key, "report", new JSONObject().put("kind", kind).put("ref", ref)
                    .put("reason", reason).toString());
        } catch (Exception ignored) {
        }
        flush(c);
    }

    /** Комментарии: с сервера (и в кэш) или из кэша, если сети нет. Не в UI-потоке. */
    static List<String[]> comments(Context c, Ref r) {
        MediaDb db = MediaDb.get(c);
        if (!r.local() && db.ops().isEmpty()) {
            try {
                JSONObject o = call(c, r.api, r.key, "action=comments&folder=" + Uri.encode(r.folder)
                        + "&file=" + Uri.encode(r.file), "GET", null);
                JSONArray a = o.optJSONArray("items");
                if (a != null) {
                    List<String[]> items = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject it = a.getJSONObject(i);
                        items.add(new String[]{it.optString("name"), it.optString("text"), it.optString("ts"),
                                it.optBoolean("mine") ? "1" : "0", it.optString("author"), String.valueOf(it.optInt("id"))});
                    }
                    db.putComments(r.skey(), items);
                    MediaDb.Stat s = db.stat(r.skey());
                    db.putStat(r.skey(), s.likes, items.size(), s.liked);
                }
            } catch (Exception ignored) {
            }
        }
        return db.comments(r.skey());
    }

    /** Отправить очередь (лайки/комментарии/жалобы, сделанные офлайн). */
    static void flush(final Context c) {
        bg.execute(() -> {
            MediaDb db = MediaDb.get(c);
            for (String[] op : db.ops()) {
                try {
                    JSONObject o = call(c, op[1], op[2], "action=" + op[3], "POST", op[4]);
                    if (o.has("ok")) db.removeOp(op[0]); // сервер ответил (в т.ч. отказом) — не повторяем
                    if ("like".equals(op[3]) && o.optBoolean("ok")) {
                        JSONObject b = new JSONObject(op[4]);
                        String sk = op[1] + "|" + b.optString("folder") + "|" + b.optString("file");
                        MediaDb.Stat s = db.stat(sk);
                        db.putStat(sk, o.optInt("likes"), s.comments, o.optBoolean("liked"));
                    }
                } catch (Exception e) {
                    return; // сети нет — попробуем позже
                }
            }
        });
    }
}
