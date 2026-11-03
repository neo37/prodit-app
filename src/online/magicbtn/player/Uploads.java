package online.magicbtn.player;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Свой источник на magicbtn.online и загрузка в него с телефона: до 3 видео по 3 минуты и до 10 фото.
 * Общая логика для «Мой источник» и для 📎 в чате (создать источник → загрузить → поделиться).
 */
final class Uploads {

    static final int MAX_FILES = 3, MAX_SECONDS = 180;
    static final long MAX_BYTES = 250L * 1024 * 1024;
    static final int MAX_PHOTOS = 10;
    static final long MAX_PHOTO_BYTES = 20L * 1024 * 1024;

    interface Progress {
        void onProgress(int pct);
    }

    /** Выбранный файл: имя, размер; error != null — загружать нельзя (текст для пользователя). */
    static final class Picked {
        String name = "video.mp4", error;
        long size = -1;
        boolean photo;
    }

    private Uploads() {
    }

    static JSONObject space(Context c) {
        try {
            String s = c.getSharedPreferences("my_source", 0).getString("space", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Создать свой источник (если ещё нет) и добавить его на главный экран. Не в UI-потоке. */
    static JSONObject ensureSpace(Context c, String name) throws Exception {
        JSONObject sp = space(c);
        if (sp != null) return sp;
        if (name == null || name.isEmpty()) {
            String n = Social.name(c);
            name = n.isEmpty() ? Ui.t(R.string.my_videos) : n + " · " + Ui.t(R.string.my_videos);
        }
        JSONObject o = Social.call(c, ChatActivity.CHAT_API, "", "action=space_create", "POST",
                new JSONObject().put("name", name).toString());
        if (!o.optBoolean("ok")) throw new Exception(o.optString("error"));
        c.getSharedPreferences("my_source", 0).edit().putString("space", o.toString()).apply();
        List<Sources.Source> l = Sources.all(c);
        l.add(new Sources.Source(o.optString("name"), ChatActivity.CHAT_API, o.optString("key")));
        Sources.save(c, l, l.size() - 1);
        return o;
    }

    /** Выбор видео или фото с телефона. */
    static android.content.Intent pickIntent() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"video/mp4", "video/quicktime",
                "image/jpeg", "image/png", "image/webp"});
        return android.content.Intent.createChooser(i, Ui.t(R.string.my_source_upload));
    }

    /** Проверки до загрузки: формат, длительность, размер (сервер проверяет ещё раз). */
    static Picked check(Context c, Uri u) {
        Picked p = new Picked();
        String type = c.getContentResolver().getType(u);
        p.photo = type != null && type.startsWith("image/");
        long ms = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, u);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            ms = d == null ? 0 : Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        try (Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = cur.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && cur.getString(ni) != null) p.name = cur.getString(ni);
                if (si >= 0) p.size = cur.getLong(si);
            }
        } catch (Exception ignored) {
        }
        String low = p.name.toLowerCase(Locale.ROOT);
        if (p.photo || low.matches(".*\\.(jpe?g|png|webp)$")) {
            p.photo = true;
            if (!low.matches(".*\\.(jpe?g|png|webp)$")) p.name += type != null && type.endsWith("png") ? ".png"
                    : type != null && type.endsWith("webp") ? ".webp" : ".jpg";
            if (p.size > MAX_PHOTO_BYTES) p.error = Ui.t(R.string.upload_photo_too_big);
            return p;
        }
        if (!(low.endsWith(".mp4") || low.endsWith(".mov") || low.endsWith(".m4v"))) p.error = Ui.t(R.string.upload_only_mp4);
        else if (ms <= 0 || ms > MAX_SECONDS * 1000L + 500) p.error = Ui.t(R.string.upload_too_long, MAX_SECONDS / 60);
        else if (p.size > MAX_BYTES) p.error = Ui.t(R.string.upload_too_big);
        return p;
    }

    /** Загрузка (multipart). Возвращает ответ сервера {ok, fname, …}. Не в UI-потоке. */
    static JSONObject upload(Context c, JSONObject sp, Uri u, Picked f, Progress progress) throws Exception {
        String boundary = "----j01" + System.currentTimeMillis();
        String safe = f.name.replace("\"", "_");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"folder\"\r\n\r\n"
                + sp.optString("folder") + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"" + (f.photo ? "photo" : "video") + "\"; filename=\"" + safe
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes("UTF-8");
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");
        HttpURLConnection h = (HttpURLConnection) new URL(ChatActivity.CHAT_API + "?action=upload").openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(15000);
        h.setReadTimeout(300000);
        h.setRequestProperty("X-UID", Social.uid(c));
        h.setRequestProperty("X-API-Key", sp.optString("key"));
        h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (f.size > 0) h.setFixedLengthStreamingMode(head.length + f.size + tail.length);
        else h.setChunkedStreamingMode(64 * 1024);
        try (OutputStream o = h.getOutputStream(); InputStream in = c.getContentResolver().openInputStream(u)) {
            o.write(head);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                if (f.size > 0 && progress != null) progress.onProgress((int) (done * 100 / f.size));
            }
            o.write(tail);
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        h.disconnect();
        JSONObject r = new JSONObject(b.size() == 0 ? "{}" : b.toString("UTF-8"));
        if (!r.optBoolean("ok")) {
            String e = r.optString("error", "HTTP " + code);
            throw new Exception(e.contains("photos") ? Ui.t(R.string.upload_limit_photos, MAX_PHOTOS)
                    : e.startsWith("limit") ? Ui.t(R.string.upload_limit, MAX_FILES) : e);
        }
        return r;
    }

    /** Адрес загруженного видео для плеера/чата. */
    static String streamUrl(JSONObject sp, String fname) {
        return ChatActivity.CHAT_API + "?action=stream&folder=" + Uri.encode(sp.optString("folder"))
                + "&file=" + Uri.encode(fname);
    }
}
