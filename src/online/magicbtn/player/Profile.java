package online.magicbtn.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Аватар: локальная копия (256 px JPEG) + загрузка на сервер чата; аватарки других — с кэшем. */
final class Profile {

    private static final ExecutorService bg = Executors.newFixedThreadPool(2);

    private Profile() {
    }

    static File localAvatar(Context c) {
        return new File(c.getFilesDir(), "avatar.jpg");
    }

    /** Сжать выбранную картинку до 256×256 (по центру), сохранить и отправить на сервер. Не в UI-потоке. */
    static void setAvatar(Context c, Uri u) throws Exception {
        Bitmap src;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            src = BitmapFactory.decodeStream(in, null, o);
        }
        if (src == null) throw new Exception("not an image");
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap small = Bitmap.createScaledBitmap(sq, 256, 256, true);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 85, jpg);
        try (FileOutputStream f = new FileOutputStream(localAvatar(c))) {
            f.write(jpg.toByteArray());
        }
        // на сервер — чтобы аватар видели в чате
        String boundary = "----ava" + System.currentTimeMillis();
        HttpURLConnection h = (HttpURLConnection) new URL(ChatActivity.CHAT_API + "?action=avatar_set").openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("X-UID", Social.uid(c));
        h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream o = h.getOutputStream()) {
            o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"a.jpg\"\r\n"
                    + "Content-Type: image/jpeg\r\n\r\n").getBytes("UTF-8"));
            o.write(jpg.toByteArray());
            o.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        }
        int code = h.getResponseCode();
        h.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        new File(c.getCacheDir(), "avatars").delete();
    }

    static Bitmap myAvatar(Context c) {
        File f = localAvatar(c);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /** Аватар автора сообщения (id от сервера); пусто — остаётся жёлтый кружок. */
    static void loadAvatar(final Activity a, final String id, final ImageView into) {
        if (id == null || id.isEmpty() || !id.matches("[a-f0-9]{8,40}")) return;
        into.setTag(id);
        bg.execute(() -> {
            File dir = new File(a.getCacheDir(), "avatars");
            dir.mkdirs();
            File f = new File(dir, id + ".jpg");
            try {
                if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 3600_000) {
                    HttpURLConnection h = (HttpURLConnection) new URL(ChatActivity.CHAT_API + "?action=avatar&id=" + id).openConnection();
                    if (h.getResponseCode() == 200) {
                        try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                    h.disconnect();
                }
                final Bitmap b = BitmapFactory.decodeFile(f.getPath());
                a.runOnUiThread(() -> {
                    if (b != null && id.equals(into.getTag())) into.setImageBitmap(b);
                });
            } catch (Exception ignored) {
            }
        });
    }
}
