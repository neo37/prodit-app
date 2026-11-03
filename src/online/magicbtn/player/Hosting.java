package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 3.4: «Свой сайт → источник» (☰, для всех). Готовые файлы из assets/hosting: video_api.php (любой PHP-хостинг),
 * video_api.py (Flask) и README.txt — с уже вписанным ключом этого телефона. Сохраняются в «Загрузки/SmartPlayer»,
 * их можно переслать себе; «Подключить сайт» — находит video_api.php по адресу и добавляет источник с ключом.
 * Все видео и фото из всех вложенных папок сайта видны в приложении, каждая папка — разделом.
 */
final class Hosting {

    private static final String[] FILES = {"video_api.php", "video_api.py", "README.txt"};

    private Hosting() {
    }

    /** Ключ этого телефона для своих сайтов (один на все файлы). */
    static String key(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("hosting", 0);
        String k = p.getString("key", null);
        if (k == null) {
            String abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 24; i++) b.append(abc.charAt(r.nextInt(abc.length())));
            k = b.toString();
            p.edit().putString("key", k).apply();
        }
        return k;
    }

    static void open(final Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        TextView t = new TextView(a);
        t.setText(Ui.t(R.string.host_steps));
        t.setTextSize(15);
        t.setLineSpacing(0, 1.25f);
        t.setTextColor(Ui.BLACK);
        box.addView(t);
        TextView k = new TextView(a);
        k.setText(Ui.t(R.string.host_key, key(a)));
        k.setTextSize(13);
        k.setTextColor(Ui.GRAY);
        k.setTextIsSelectable(true);
        k.setPadding(0, Ui.dp(a, 12), 0, 0);
        box.addView(k);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.host_title))
                .setView(sv)
                .setPositiveButton(Ui.t(R.string.host_download), (d, w) -> download(a))
                .setNeutralButton(Ui.t(R.string.host_connect), (d, w) -> connect(a))
                .setNegativeButton(Ui.t(R.string.close), null)
                .show();
    }

    /** Файлы с ключом → «Загрузки/SmartPlayer» (Android 10+), затем меню «отправить». */
    private static void download(final Activity a) {
        new Thread(() -> {
            List<Uri> saved = new ArrayList<>();
            String err = null;
            try {
                for (String f : FILES) {
                    byte[] data = asset(a, "hosting/" + f);
                    String s = new String(data, "UTF-8").replace("__KEY__", key(a));
                    saved.add(save(a, f, s.getBytes("UTF-8")));
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String e = err;
            a.runOnUiThread(() -> {
                if (e != null || saved.isEmpty()) {
                    Toast.makeText(a, Ui.t(R.string.error_prefix, e), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(a, Ui.t(R.string.host_saved), Toast.LENGTH_LONG).show();
                Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(saved))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    a.startActivity(Intent.createChooser(send, Ui.t(R.string.host_send)));
                } catch (Exception ignored) {
                }
            });
        }).start();
    }

    private static byte[] asset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static Uri save(Context c, String name, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, name.endsWith(".txt") ? "text/plain" : "application/octet-stream");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SmartPlayer");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("Downloads");
            try (OutputStream o = c.getContentResolver().openOutputStream(u)) {
                o.write(data);
            }
            return u;
        }
        // Android 7–9: в папку приложения (без разрешений) — дальше «отправить» себе
        java.io.File dir = new java.io.File(c.getExternalFilesDir(null), "SmartPlayer");
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, name);
        try (OutputStream o = new java.io.FileOutputStream(f)) {
            o.write(data);
        }
        return Uri.fromFile(f);
    }

    /** Адрес сайта → ищем video_api.php с моим ключом → новый источник. */
    private static void connect(final Activity a) {
        final EditText url = new EditText(a);
        url.setHint("https://my-site.com");
        url.setText("https://");
        url.setSelection(url.getText().length());
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        LinearLayout box = new LinearLayout(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        box.addView(url, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.host_connect))
                .setMessage(Ui.t(R.string.host_connect_hint))
                .setView(box)
                .setPositiveButton(Ui.t(R.string.host_connect_go), (d, w) -> {
                    final String u = url.getText().toString().trim();
                    Toast.makeText(a, Ui.t(R.string.connecting), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String api = Sources.probeApi(u.startsWith("http") ? u : "https://" + u, key(a));
                        a.runOnUiThread(() -> {
                            if (api == null) {
                                Toast.makeText(a, Ui.t(R.string.host_not_found), Toast.LENGTH_LONG).show();
                                return;
                            }
                            List<Sources.Source> l = Sources.all(a);
                            String host = Uri.parse(api).getHost();
                            l.add(new Sources.Source(host == null ? api : host, api, key(a)));
                            Sources.save(a, l, l.size() - 1);
                            Nav.openSource(a, l.size() - 1);
                        });
                    }).start();
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }
}
