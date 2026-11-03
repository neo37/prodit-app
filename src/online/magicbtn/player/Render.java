package online.magicbtn.player;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.Effect;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 3.4: склейка записи просмотра слайдшоу в один ролик — на телефоне, Media3 Transformer.
 * Каждый сегмент: видео — обрезка [start, start+dur], фото — показ dur мс; кадр — пропорции экрана записи
 * (вписан, как на экране), поверх — зум и сдвиг по ключевым кадрам записи (плавно между ними).
 * Файлы, помеченные 👎, пропускаются. Результат — «Фильмы/SmartPlayer» (Android 10+) или папка приложения.
 */
final class Render {

    interface Listener {
        void progress(int pct);

        void done(Uri out, String error);
    }

    /** Идёт склейка: id записи (одна за раз), иначе -1. */
    static volatile long running = -1;
    static volatile int pct;
    private static Transformer transformer;
    private static Context app;

    private Render() {
    }

    /** Вызывать из UI-потока. */
    static void start(final Context ctx, final long rec, final String title, final Listener l) {
        if (running >= 0) {
            l.done(null, "busy");
            return;
        }
        final Context c = ctx.getApplicationContext();
        List<EditedMediaItem> items = new ArrayList<>();
        float aspect = Recorder.aspect(c, rec);
        int w, h;
        if (aspect < 1) {
            w = 1080;
            h = Math.min(1920, even(Math.round(1080 / aspect)));
        } else {
            h = 1080;
            w = Math.min(1920, even(Math.round(1080 * aspect)));
        }
        MediaCache cache = MediaCache.get(c);
        for (Recorder.Seg s : Recorder.segments(c, rec)) {
            if (s.excluded) continue;
            if (s.image && Cuts.cut(c, s.uri, 0)) continue;   // 3.10: фото вырезано в редакторе
            File f = s.uri.startsWith("http") ? cache.cached(s.uri) : null;
            Uri u = f != null ? Uri.fromFile(f) : Uri.parse(s.uri);
            // 3.10: у видео — только оставленные в редакторе куски
            List<long[]> parts = new ArrayList<>();
            if (s.image) parts.add(new long[]{0, s.durMs});
            else parts = Cuts.keep(c, s.uri, s.startMs, s.startMs + s.durMs);
            for (long[] part : parts) {
                MediaItem.Builder mb = new MediaItem.Builder().setUri(u);
                if (s.image) {
                    mb.setImageDurationMs(s.durMs);
                } else {
                    mb.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(part[0]).setEndPositionMs(part[1]).build());
                }
                List<Effect> video = new ArrayList<>();
                video.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT));
                if (s.zoom.length() > 0) video.add(new Zoom(shift(s.zoom, s.image ? 0 : part[0] - s.startMs)));
                if (!Pro.on(c)) video.add(watermark(w));   // бесплатно — с подписью «Smart Player», Pro — без
                EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mb.build())
                        .setEffects(new Effects(Collections.<AudioProcessor>emptyList(), video));
                if (s.image) eb.setFrameRate(30);
                items.add(eb.build());
            }
        }
        if (items.isEmpty()) {
            l.done(null, "empty");
            return;
        }
        final File tmp = new File(c.getExternalCacheDir() != null ? c.getExternalCacheDir() : c.getCacheDir(), "render_" + rec + ".mp4");
        tmp.delete();
        Composition comp = new Composition.Builder(new EditedMediaItemSequence(items))
                .experimentalSetForceAudioTrack(true)   // у фото нет звука — подставляется тишина
                .build();
        final Handler main = new Handler(Looper.getMainLooper());
        final ProgressHolder ph = new ProgressHolder();
        running = rec;
        pct = 0;
        transformer = new Transformer.Builder(c)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        new Thread(() -> {
                            Uri out = null;
                            String err = null;
                            try {
                                out = publish(c, tmp, title);
                                Recorder.setOutput(c, rec, out.toString());
                            } catch (Exception e) {
                                err = e.getMessage();
                            }
                            tmp.delete();
                            final Uri o = out;
                            final String e = err;
                            main.post(() -> finish(l, o, e));
                        }).start();
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException e) {
                        tmp.delete();
                        finish(l, null, e.getMessage() != null ? e.getMessage() : e.getErrorCodeName());
                    }
                })
                .build();
        app = c.getApplicationContext();
        Busy.begin(app, Busy.RENDER);
        transformer.start(comp, tmp.getPath());
        main.post(new Runnable() {
            @Override
            public void run() {
                if (running != rec || transformer == null) return;
                if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    pct = ph.progress;
                    l.progress(pct);
                }
                main.postDelayed(this, 500);
            }
        });
    }

    /** Ключевые кадры зума для куска, начинающегося на off мс позже отрезка: время сдвигается, ранние — в 0. */
    private static org.json.JSONArray shift(org.json.JSONArray z, long off) {
        if (off <= 0) return z;
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < z.length(); i++) {
            org.json.JSONArray k = z.optJSONArray(i);
            if (k == null) continue;
            try {
                out.put(new org.json.JSONArray().put(Math.max(0, k.optLong(0) - off)).put(k.optDouble(1, 1))
                        .put(k.optDouble(2, 0)).put(k.optDouble(3, 0)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static void cancel() {
        if (transformer != null) transformer.cancel();
        transformer = null;
        running = -1;
        released();
    }

    private static void released() {
        if (app != null) Busy.end(app, Busy.RENDER);
        app = null;
    }

    private static void finish(Listener l, Uri out, String err) {
        running = -1;
        transformer = null;
        released();
        l.done(out, err);
    }

    /** Подпись в правом нижнем углу: реклама приложения в каждом ролике (Pro — без неё). */
    private static androidx.media3.effect.OverlayEffect watermark(int w) {
        android.text.SpannableString s = new android.text.SpannableString("▶ Smart Player");
        s.setSpan(new android.text.style.ForegroundColorSpan(0xCCFFFFFF), 0, s.length(), 0);
        s.setSpan(new android.text.style.AbsoluteSizeSpan(Math.max(28, w / 26)), 0, s.length(), 0);
        s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, s.length(), 0);
        androidx.media3.effect.OverlaySettings set = new androidx.media3.effect.OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0.92f, -0.9f)    // правый нижний угол кадра
                .setOverlayFrameAnchor(1f, -1f)
                .setAlphaScale(0.85f)
                .build();
        return new androidx.media3.effect.OverlayEffect(com.google.common.collect.ImmutableList.of(
                androidx.media3.effect.TextOverlay.createStaticTextOverlay(s, set)));
    }

    private static int even(int v) {
        return v % 2 == 0 ? v : v + 1;
    }

    /** Готовый файл → «Фильмы/SmartPlayer». */
    private static Uri publish(Context c, File f, String title) throws Exception {
        String name = (title == null || title.isEmpty() ? "slideshow" : title.replaceAll("[^\\p{L}\\p{N} ._-]+", "_"))
                + "_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT).format(new java.util.Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SmartPlayer");
            Uri u = c.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("MediaStore");
            try (InputStream in = new FileInputStream(f); OutputStream o = c.getContentResolver().openOutputStream(u)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            return u;
        }
        File dir = new File(c.getExternalFilesDir(null), "Movies");
        dir.mkdirs();
        File out = new File(dir, name);
        if (!f.renameTo(out)) throw new Exception("save");
        return Uri.fromFile(out);
    }

    /** Зум и сдвиг по ключевым кадрам [мс, зум, сдвигX, сдвигY] (доли экрана), линейно между ними. */
    private static final class Zoom implements MatrixTransformation {
        private final float[][] k;
        private long base = -1;

        Zoom(JSONArray a) {
            k = new float[a.length()][4];
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.optJSONArray(i);
                for (int j = 0; j < 4 && r != null; j++) k[i][j] = (float) r.optDouble(j, j == 1 ? 1 : 0);
            }
        }

        @Override
        public Matrix getMatrix(long presentationTimeUs) {
            if (base < 0) base = presentationTimeUs;   // время кадра — от начала этого куска
            float t = (presentationTimeUs - base) / 1000f;
            float z = 1, x = 0, y = 0;
            if (k.length > 0) {
                int i = 0;
                while (i + 1 < k.length && k[i + 1][0] <= t) i++;
                float[] a = k[i];
                if (t < a[0]) {   // до первого ключа — без зума, плавно к нему за 300 мс
                    float f = Math.max(0, 1 - (a[0] - t) / 300f);
                    z = 1 + (a[1] - 1) * f;
                    x = a[2] * f;
                    y = a[3] * f;
                } else if (i + 1 < k.length) {
                    float[] b = k[i + 1];
                    float f = Math.min(1, (t - a[0]) / Math.max(1, b[0] - a[0]));
                    z = a[1] + (b[1] - a[1]) * f;
                    x = a[2] + (b[2] - a[2]) * f;
                    y = a[3] + (b[3] - a[3]) * f;
                } else {
                    z = a[1];
                    x = a[2];
                    y = a[3];
                }
            }
            Matrix m = new Matrix();
            m.setScale(z, z);
            m.postTranslate(2 * x, -2 * y);   // доли экрана → координаты кадра (-1…1), ось Y вверх
            return m;
        }
    }
}
