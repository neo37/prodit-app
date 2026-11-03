package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Отчёты о падениях: при сбое пишем причину в файл, при следующем запуске спрашиваем,
 * отправить ли разработчику. Без согласия ничего не уходит; в отчёте нет ни имени, ни ID, ни источников.
 */
final class CrashLog {

    private CrashLog() {
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "crash.txt");
    }

    static void install(final Context c) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String head = "app " + Updater.currentName(c) + " (" + Updater.currentVersion(c) + ")\n"
                        + "android " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")\n"
                        + "device " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                        + "theme " + Look.theme(c) + ", thread " + t.getName() + "\n\n";
                String text = head + sw;
                try (FileOutputStream f = new FileOutputStream(file(c))) {
                    f.write(text.substring(0, Math.min(text.length(), 19000)).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Было падение — предложить отправить отчёт (один раз). */
    static void offer(final Activity a) {
        final File f = file(a);
        if (!f.exists()) return;
        final String text;
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            }
            text = b.toString("UTF-8"); // без java.nio.file — он только с Android 8, а minSdk 24
        } catch (Exception e) {
            f.delete();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.crash_title))
                .setMessage(Ui.t(R.string.crash_msg))
                .setPositiveButton(Ui.t(R.string.send), (d, w) -> new Thread(() -> {
                    try {
                        JSONObject o = Social.call(a, ChatActivity.CHAT_API, "", "action=crash", "POST",
                                new JSONObject().put("text", text).toString());
                        if (o.optBoolean("ok")) f.delete();
                    } catch (Exception ignored) {
                    }
                }).start())
                .setNegativeButton(Ui.t(R.string.cancel), (d, w) -> f.delete())
                .show();
    }
}
