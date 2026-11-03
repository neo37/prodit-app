package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 3.4: отправить своё видео с телефона в общий чат или в личку. Файл грузится в «Мой источник» на magicbtn.online
 * отдельной «общей» загрузкой (action=share_chunk): кусками по 4 МБ с докачкой — длина любая, до 500 МБ,
 * в лимит «3 видео» источника не входит, сервер удаляет его через 30 дней. Потом — сообщение с кнопкой «▶ Смотреть».
 * Откуда: ↗ в плеере (видео с телефона) и «Поделиться → Умный плеер» из галереи (ShareInActivity).
 */
final class ShareVideo {

    static final long MAX_BYTES = 500L * 1024 * 1024;
    static final int KEEP_DAYS = 30;
    private static final int CHUNK = 4 * 1024 * 1024;

    private ShareVideo() {
    }

    /** Кому отправить: общий чат или контакт. */
    static void pick(final Activity a, final Uri video, final String title) {
        pick(a, video, title, null);
    }

    /** onDone — после отправки или отмены (в UI-потоке); null — не нужно. */
    static void pick(final Activity a, final Uri video, final String title, final Runnable onDone) {
        final List<Contacts.Contact> people = Contacts.all(a);
        final List<String> items = new ArrayList<>();
        items.add(Ui.t(R.string.share_to_chat));
        for (Contacts.Contact c : people) items.add(Ui.t(R.string.share_to_contact, c.name.isEmpty() ? "Guest" : c.name));
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.share_to_title))
                .setItems(items.toArray(new String[0]), (d, w) -> Rules.ensure(a, () -> {
                    Contacts.Contact to = w == 0 ? null : people.get(w - 1);
                    Runnable go = () -> {
                        send(a.getApplicationContext(), video, title, to == null ? null : to.author, to == null ? null : to.name, onDone);
                        Notify.askOnce(a);
                    };
                    if (to == null) ChatConfirm.ask(a, "🎬 " + (title == null ? "" : title), go);   // общий чат — подтвердить
                    else go.run();
                }))
                .setNegativeButton(Ui.t(R.string.cancel), (d, w) -> {
                    if (onDone != null) onDone.run();
                })
                .setOnCancelListener(d -> {
                    if (onDone != null) onDone.run();
                });
        if (people.isEmpty()) b.setMessage(Ui.t(R.string.share_no_contacts) + "\n" + Ui.t(R.string.share_kept, KEEP_DAYS));
        b.show();
    }

    /** Загрузить и отправить в фоне (прогресс — в уведомлении, итог — тостом). to == null — общий чат. */
    static void send(final Context c, final Uri video, final String title, final String to, final String toName,
                     final Runnable onDone) {
        final Handler main = new Handler(Looper.getMainLooper());
        final int nid = (video.toString() + System.currentTimeMillis()).hashCode();
        Busy.begin(c, Busy.UPLOAD);
        new Thread(() -> {
            String result;
            try {
                long size = size(c, video);
                if (size > MAX_BYTES) throw new Exception(Ui.t(R.string.share_too_big, (int) (MAX_BYTES >> 20)));
                JSONObject sp = Uploads.ensureSpace(c, null);
                String name = fileName(c, video, title);
                Notify.upload(c, nid, name, 0);
                String fname = upload(c, sp, video, name, size, pct -> Notify.upload(c, nid, name, pct));
                String nm = Social.name(c).isEmpty() ? "Guest" : Social.name(c);
                JSONObject b = new JSONObject().put("text", "🎬 " + (title != null && !title.isEmpty() ? title : fname))
                        .put("name", nm)
                        .put("share_name", sp.optString("name")).put("share_url", ChatActivity.CHAT_API)
                        .put("share_key", sp.optString("key"))
                        .put("share_file", Uploads.streamUrl(sp, fname));
                if (to != null) b.put("to", to).put("to_name", toName == null ? "" : toName);
                JSONObject o = Social.call(c, ChatActivity.CHAT_API, "", to == null ? "action=chat_post" : "action=dm_post",
                        "POST", b.toString());
                if (!o.optBoolean("ok")) throw new Exception(o.optString("error", "?"));
                result = Ui.t(R.string.share_done);
            } catch (Exception e) {
                result = Ui.t(R.string.share_failed, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Notify.upload(c, nid, null, -1);
            Busy.end(c, Busy.UPLOAD);
            final String m = result;
            main.post(() -> {
                Toast.makeText(c, m, Toast.LENGTH_LONG).show();
                if (onDone != null) onDone.run();
            });
        }).start();
    }

    static long size(Context c, Uri u) {
        if ("file".equals(u.getScheme()) || u.getScheme() == null) return new java.io.File(u.getPath()).length();
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String fileName(Context c, Uri u, String title) {
        String n = null;
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) n = cur.getString(0);
        } catch (Exception ignored) {
        }
        if (n == null || n.isEmpty()) n = title != null && !title.isEmpty() ? title : "video";
        String low = n.toLowerCase(Locale.ROOT);
        if (!(low.endsWith(".mp4") || low.endsWith(".mov") || low.endsWith(".m4v"))) n += ".mp4";
        return n;
    }

    /** Кусками: POST action=share_chunk&folder&id&offset&total&name, тело — байты. Сервер отвечает {ok, have[, fname]}. */
    private static String upload(Context c, JSONObject sp, Uri u, String name, long size, Uploads.Progress progress) throws Exception {
        if (size <= 0) throw new Exception("size?");
        String id = Long.toHexString(Double.doubleToLongBits(Math.random())) + Long.toHexString(System.nanoTime());
        id = id.length() > 24 ? id.substring(0, 24) : id;
        byte[] buf = new byte[CHUNK];
        long off = 0;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("no file");
            while (off < size) {
                int n = 0;
                while (n < CHUNK) {
                    int r = in.read(buf, n, CHUNK - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n == 0) break;
                JSONObject r = null;
                for (int attempt = 0; attempt < 4; attempt++) {   // плохая связь — повторяем кусок
                    try {
                        r = chunk(c, sp, id, off, size, name, buf, n);
                        break;
                    } catch (java.io.IOException e) {
                        if (attempt == 3) throw e;
                        Thread.sleep(2000L * (attempt + 1));
                    }
                }
                if (r == null || !r.optBoolean("ok")) throw new Exception(r == null ? "?" : r.optString("error", "?"));
                off += n;
                if (progress != null) progress.onProgress((int) (off * 100 / size));
                if (r.has("fname")) return r.optString("fname");
            }
        }
        throw new Exception("incomplete");
    }

    private static JSONObject chunk(Context c, JSONObject sp, String id, long off, long total, String name, byte[] buf, int n)
            throws Exception {
        String q = "action=share_chunk&folder=" + Uri.encode(sp.optString("folder")) + "&id=" + id + "&offset=" + off
                + "&total=" + total + "&name=" + Uri.encode(name);
        HttpURLConnection h = (HttpURLConnection) new URL(ChatActivity.CHAT_API + "?" + q).openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(15000);
        h.setReadTimeout(120000);
        h.setRequestProperty("X-UID", Social.uid(c));
        h.setRequestProperty("X-API-Key", sp.optString("key"));
        h.setRequestProperty("Content-Type", "application/octet-stream");
        h.setFixedLengthStreamingMode(n);
        try (OutputStream o = h.getOutputStream()) {
            o.write(buf, 0, n);
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] t = new byte[4096];
        int k;
        while (in != null && (k = in.read(t)) > 0) b.write(t, 0, k);
        h.disconnect();
        if (code >= 500) throw new java.io.IOException("HTTP " + code);
        return new JSONObject(b.size() == 0 ? "{}" : b.toString("UTF-8"));
    }
}
