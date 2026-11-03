package online.magicbtn.player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Источники видео: video_api.php (нативный плеер) или любой сайт (WebView). Хранятся в
 * SharedPreferences — добавленные запоминаются. По умолчанию — только magicbtn.online.
 */
final class Sources {

    static final class Source {
        String name, url, key;

        Source(String name, String url, String key) {
            this.name = name;
            this.url = url;
            this.key = key == null ? "" : key;
        }

        /** Адрес для WebView: ключ (если есть) уходит параметром ?key=, дальше сервер держит его в cookie. */
        String loadUrl() {
            if (key.isEmpty()) return url;
            return url + (url.contains("?") ? "&" : "?") + "key=" + Uri.encode(key);
        }

        /** video_api.php — открываем нативным плеером, остальное — как сайт в WebView. */
        boolean isApi() {
            return Uri.parse(url).getPath() != null && Uri.parse(url).getPath().endsWith("video_api.php");
        }

        /** Адрес API без параметров: https://host/video_api.php */
        String apiBase() {
            int q = url.indexOf('?');
            return q < 0 ? url : url.substring(0, q);
        }

        String host() {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h;
        }
    }

    interface OnChange {
        void onSourceChanged(Source s);
    }

    private static final String PREFS = "sources";

    private Sources() {
    }

    /**
     * Есть ли у сайта API плеера: пробуем сам адрес (если это .php) и https://host/video_api.php.
     * Возвращает адрес API или null. Вызывать не в UI-потоке.
     */
    static String probeApi(String url, String key) {
        Uri u = Uri.parse(url);
        if (u.getHost() == null) return null;
        List<String> cand = new ArrayList<>();
        if (u.getPath() != null && u.getPath().endsWith(".php")) cand.add(u.getScheme() + "://" + u.getAuthority() + u.getPath());
        cand.add("https://" + u.getAuthority() + "/video_api.php");
        for (String c : cand) {
            try {
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(c + "?action=folders").openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                if (key != null && !key.isEmpty()) h.setRequestProperty("X-API-Key", key);
                if (h.getResponseCode() == 200) {
                    java.io.InputStream in = h.getInputStream();
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && b.size() < 65536) b.write(buf, 0, n);
                    in.close();
                    JSONObject o = new JSONObject(b.toString("UTF-8"));
                    if (o.has("folders")) return c;
                }
                h.disconnect();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static List<Source> defaults() {
        List<Source> l = new ArrayList<>();
        // по умолчанию только magicbtn.online; архивы и другие сайты пользователь добавляет сам
        l.add(new Source("magicbtn.online", "https://magicbtn.online/video_api.php", ""));
        return l;
    }

    static List<Source> all(Context c) {
        String raw = c.getSharedPreferences(PREFS, 0).getString("list", null);
        if (raw == null) return defaults();
        if (raw.equals("[]")) return new ArrayList<>();
        List<Source> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new Source(o.optString("name"), o.optString("url"), o.optString("key")));
            }
        } catch (Exception e) {
            return defaults();
        }
        return l;
    }

    static void save(Context c, List<Source> l, int current) {
        JSONArray a = new JSONArray();
        try {
            for (Source s : l) a.put(new JSONObject().put("name", s.name).put("url", s.url).put("key", s.key));
        } catch (Exception ignored) {
        }
        c.getSharedPreferences(PREFS, 0).edit().putString("list", a.toString()).putInt("current", current).apply();
    }

    static int currentIndex(Context c, int size) {
        int i = c.getSharedPreferences(PREFS, 0).getInt("current", 0);
        return i >= 0 && i < size ? i : 0;
    }

    static Source current(Context c) {
        List<Source> l = all(c);
        return l.isEmpty() ? null : l.get(currentIndex(c, l.size()));
    }

    static Source get(Context c, int i) {
        List<Source> l = all(c);
        return i >= 0 && i < l.size() ? l.get(i) : null;
    }

    // ---------- меню ----------

    static void showMenu(final Context c, final OnChange cb) {
        final List<Source> l = all(c);
        final int cur = currentIndex(c, l.size());
        String[] names = new String[l.size() + 3];
        for (int i = 0; i < l.size(); i++) names[i] = (i == cur ? "● " : "○ ") + l.get(i).name;
        names[l.size()] = Ui.t(R.string.src_menu_add);
        names[l.size() + 1] = Ui.t(R.string.src_menu_edit);
        names[l.size() + 2] = Ui.t(R.string.src_menu_delete);
        new AlertDialog.Builder(c)
                .setTitle(Ui.t(R.string.source))
                .setItems(names, (d, which) -> {
                    if (which < l.size()) {
                        save(c, l, which);
                        cb.onSourceChanged(l.get(which));
                    } else if (which == l.size()) {
                        edit(c, l, -1, cb);
                    } else if (which == l.size() + 1) {
                        edit(c, l, cur, cb);
                    } else if (!l.isEmpty()) {
                        l.remove(cur);
                        save(c, l, 0);
                        cb.onSourceChanged(l.isEmpty() ? null : l.get(0));
                    }
                })
                .setNegativeButton(Ui.t(R.string.close), null)
                .show();
    }

    /** Свой источник (создан в «Мой источник»)? */
    static boolean isMine(Context c, Source s) {
        org.json.JSONObject sp = Uploads.space(c);
        return s != null && sp != null && s.isApi() && !s.key.isEmpty() && s.key.equals(sp.optString("key"));
    }

    /**
     * Удалить источник. Свой — с сервера целиком (все фото и видео, ссылки в чате и мессенджерах перестают работать).
     * Чужой — только из приложения; по желанию стереть и его офлайн-копии на телефоне.
     */
    static void remove(final android.app.Activity a, final int idx, final Runnable done) {
        final Source s = get(a, idx);
        if (s == null) return;
        if (isMine(a, s)) {
            MySourceActivity.deleteSource(a, () -> a.runOnUiThread(done));
            return;
        }
        final Runnable drop = () -> {
            List<Source> l = all(a);
            for (int i = l.size() - 1; i >= 0; i--)
                if (l.get(i).url.equals(s.url) && l.get(i).key.equals(s.key)) l.remove(i);
            save(a, l, 0);
            done.run();
        };
        new AlertDialog.Builder(a)
                .setTitle(s.name)
                .setMessage(Ui.t(R.string.source_remove_msg))
                .setPositiveButton(Ui.t(R.string.source_remove), (d, w) -> drop.run())
                .setNeutralButton(Ui.t(R.string.source_remove_offline), (d, w) -> {
                    MediaDb db = MediaDb.get(a);
                    String prefix = s.host() + "/";
                    for (String label : db.labels())
                        if (label.startsWith(prefix)) for (MediaDb.Item it : db.list(label)) db.delete(it.url);
                    drop.run();
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }

    static void edit(final Context c, final int idx, final OnChange cb) {
        edit(c, all(c), idx, cb);
    }

    private static void edit(final Context c, final List<Source> l, final int idx, final OnChange cb) {
        Source s = idx >= 0 ? l.get(idx) : new Source("", "https://", "");
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 20);
        box.setPadding(p, Ui.dp(c, 8), p, 0);
        final EditText name = field(c, box, Ui.t(R.string.src_name), s.name, InputType.TYPE_CLASS_TEXT);
        final EditText url = field(c, box, Ui.t(R.string.src_url), s.url,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText key = field(c, box, Ui.t(R.string.src_key), s.key,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        AlertDialog.Builder b = new AlertDialog.Builder(c);
        if (idx >= 0 && c instanceof android.app.Activity)
            b.setNeutralButton(Ui.t(R.string.remove), (d, w) -> remove((android.app.Activity) c, idx, () -> cb.onSourceChanged(null)));
        b
                .setTitle(Ui.t(idx >= 0 ? R.string.src_edit_title : R.string.src_new_title))
                .setView(box)
                .setPositiveButton(Ui.t(R.string.connect), (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("http")) u = "https://" + u;
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = Uri.parse(u).getHost();
                    Source ns = new Source(n, u, key.getText().toString().trim());
                    int at = idx;
                    if (at >= 0) l.set(at, ns);
                    else {
                        l.add(ns);
                        at = l.size() - 1;
                    }
                    save(c, l, at);
                    cb.onSourceChanged(ns);
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }

    private static EditText field(Context c, LinearLayout box, String hint, String value, int type) {
        TextView t = new TextView(c);
        t.setText(hint);
        t.setTextColor(Ui.GRAY);
        box.addView(t);
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setText(value);
        box.addView(e);
        return e;
    }
}
