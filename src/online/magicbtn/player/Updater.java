package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обновление по воздуху. На сервере лежит version.json:
 * {"versionCode":2, "versionName":"1.1", "apk":"smart-player-2.apk",
 *  "notes":"что нового", "minVersionCode":1}
 * Если versionCode больше установленного — модалка «обновите».
 * Если установленная версия меньше minVersionCode — модалку нельзя закрыть.
 */
public final class Updater {

    public static final String VERSION_URL = "https://magicbtn.online/ai-video/version.json";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean shownThisSession;
    private static JSONObject pending; // ждём, пока пользователь разрешит установку из этого источника

    private Updater() {
    }

    public static long currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Вызывать из onResume: проверяет сервер раз за запуск или продолжает начатое обновление. */
    public static void onResume(final Activity a) {
        if (Flags.PLAY) return; // в Google Play обновляет сам магазин
        if (pending != null && canInstall(a)) {
            JSONObject v = pending;
            pending = null;
            download(a, v);
            return;
        }
        if (shownThisSession) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject v = new JSONObject(fetch(VERSION_URL + "?t=" + System.currentTimeMillis()));
                    if (v.optLong("versionCode") <= currentVersion(a)) return;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!a.isFinishing() && !shownThisSession) ask(a, v);
                        }
                    });
                } catch (Exception ignored) {
                    // нет сети или файла — молча работаем дальше
                }
            }
        }).start();
    }

    private static void ask(final Activity a, final JSONObject v) {
        shownThisSession = true;
        boolean force = currentVersion(a) < v.optLong("minVersionCode", 0);
        String msg = Ui.t(R.string.upd_outdated, currentName(a),
                v.optString("versionName", String.valueOf(v.optLong("versionCode"))));
        String notes = v.optString("notes", "");
        if (!notes.isEmpty()) msg += "\n\n" + notes;
        if (force) msg += "\n\n" + Ui.t(R.string.upd_forced);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.upd_title))
                .setMessage(msg)
                .setCancelable(!force)
                .setPositiveButton(Ui.t(R.string.upd_btn), null);
        if (!force) b.setNegativeButton(Ui.t(R.string.later), null);
        final AlertDialog d = b.create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                d.dismiss();
                if (!canInstall(a)) {
                    pending = v;
                    new AlertDialog.Builder(a)
                            .setTitle(Ui.t(R.string.perm_title))
                            .setMessage(Ui.t(R.string.perm_msg))
                            .setCancelable(false)
                            .setPositiveButton(Ui.t(R.string.open_settings), (x, y) -> a.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName()))))
                            .show();
                    return;
                }
                download(a, v);
            }
        });
    }

    private static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    private static void download(final Activity a, final JSONObject v) {
        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        final TextView label = new TextView(a);
        label.setText(Ui.t(R.string.downloading));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 24);
        box.setPadding(p, p / 2, p, 0);
        box.addView(label);
        box.addView(bar);
        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.upd_progress_title))
                .setView(box)
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File apk = new File(a.getCacheDir(), "update.apk");
                try {
                    URL url = new URL(new URL(VERSION_URL), v.getString("apk"));
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                    final long total = c.getContentLengthLong();
                    long n = 0;
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            out.write(buf, 0, r);
                            n += r;
                            if (total > 0) {
                                final int pct = (int) (n * 100 / total);
                                final long done = n;
                                main.post(() -> {
                                    bar.setProgress(pct);
                                    label.setText(Ui.t(R.string.downloading_of, Ui.bytes(done), Ui.bytes(total)));
                                });
                            }
                        }
                    }
                    if (total > 0 && n != total) throw new IOException(Ui.t(R.string.upd_incomplete));
                    install(a, apk);
                    main.post(d::dismiss);
                } catch (final Exception e) {
                    main.post(() -> {
                        d.dismiss();
                        shownThisSession = false;
                        new AlertDialog.Builder(a)
                                .setTitle(Ui.t(R.string.upd_failed))
                                .setMessage(String.valueOf(e.getMessage()))
                                .setPositiveButton(Ui.t(R.string.retry), (x, y) -> download(a, v))
                                .setNegativeButton(Ui.t(R.string.close), null)
                                .show();
                    });
                }
            }
        }).start();
    }

    /** Установка через PackageInstaller: системный диалог «Обновить приложение?». */
    private static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                s.fsync(out);
            }
            Intent i = new Intent(c, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
        }
    }

    private static String fetch(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toString("UTF-8");
        }
    }
}
