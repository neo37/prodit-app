package online.magicbtn.player;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Полноэкранное слайдшоу из локальных файлов.
 * Может показывать сразу несколько источников (Архив 1, Архив 2, Все медиа) — экран
 * делится на 2–3 панели, каждая крутит свой список независимо. Деление адаптивное:
 * альбомная ориентация — колонки, портретная — строки.
 * Тап — пауза всех, свайп вниз — выход, маленькие полупрозрачные стрелки листают свою панель.
 */
public class SlideshowActivity extends Activity {

    private final List<Panel> panels = new ArrayList<>();
    private LinearLayout container;
    private TextView hud, recBtn, dislikeBtn;
    private boolean paused;
    private boolean multi;
    private int intervalMs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newFixedThreadPool(2);

    private final Runnable hideHud = new Runnable() {
        @Override
        public void run() {
            if (!paused) hud.animate().alpha(0f).setDuration(400);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String[] labels = getIntent().getStringArrayExtra("labels");
        if (labels == null) {
            labels = new String[]{getIntent().getStringExtra("label")};
        }
        boolean shuffle = getIntent().getBooleanExtra("shuffle", false);
        int start = getIntent().getIntExtra("start", 0);
        intervalMs = getIntent().getIntExtra("interval", 5) * 1000;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        container = new LinearLayout(this);
        container.setBackgroundColor(Color.BLACK);
        root.addView(container, new FrameLayout.LayoutParams(-1, -1));

        // собираем панели только из непустых источников
        List<String> used = new ArrayList<>();
        for (String raw : labels) {
            String label = (raw == null || raw.isEmpty()) ? null : raw;
            List<MediaDb.Item> items = MediaDb.get(this).list(label);
            if (items.isEmpty()) continue;
            if (shuffle) Collections.shuffle(items);
            panels.add(new Panel(label, items));
            used.add(label == null ? "all" : label);
        }
        if (panels.isEmpty()) {
            finish();
            return;
        }
        multi = panels.size() > 1;
        if (!multi && start > 0) {
            Panel p = panels.get(0);
            p.index = Math.max(0, Math.min(start, p.items.size() - 1));
        }
        for (Panel p : panels) container.addView(p.root);

        hud = new TextView(this);
        hud.setTextColor(Ui.BLACK);
        hud.setTextSize(14);
        hud.setTypeface(Typeface.DEFAULT_BOLD);
        hud.setBackground(Ui.box(this, Ui.YELLOW, 3));
        hud.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 15), Ui.dp(this, 9));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = Ui.dp(this, 24);
        root.addView(hud, hp);

        // ✕ — видимый выход (раньше был только свайп вниз)
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(22);
        close.setTextColor(Color.WHITE);
        close.setShadowLayer(6, 0, 0, Color.BLACK);
        int cp = Ui.dp(this, 12);
        close.setPadding(cp, cp, cp, cp);
        close.setOnClickListener(v -> close());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        clp.topMargin = Ui.dp(this, 16);
        root.addView(close, clp);

        // 3.9: запись просмотра и здесь (одна панель): ⏺ — запись, 👎 — не брать файл в склейку
        if (!multi) {
            LinearLayout rc = new LinearLayout(this);
            rc.setOrientation(LinearLayout.VERTICAL);
            rc.setGravity(Gravity.CENTER_HORIZONTAL);
            recBtn = corner("⏺");
            recBtn.setOnClickListener(v -> toggleRec());
            dislikeBtn = corner("👎");
            dislikeBtn.setOnClickListener(v -> {
                MediaDb.Item it = panels.get(0).items.get(panels.get(0).index);
                boolean ex = Recorder.toggleExclude(this, recUri(it));
                Toast.makeText(this, Ui.t(ex ? R.string.rec_excluded : R.string.rec_included), Toast.LENGTH_SHORT).show();
                recUi();
            });
            rc.addView(recBtn);
            rc.addView(dislikeBtn);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            rp.topMargin = Ui.dp(this, 16);
            root.addView(rc, rp);
            recUi();
        }

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                togglePause();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 != null && e2.getY() - e1.getY() > Ui.dp(SlideshowActivity.this, 120)
                        && Math.abs(e2.getY() - e1.getY()) > Math.abs(e2.getX() - e1.getX())) {
                    close();
                    return true;
                }
                return false;
            }
        });
        // стрелки (clickable-дети панелей) перехватывают свои касания сами,
        // всё остальное обрабатывает жестовый детектор на контейнере
        container.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return gd.onTouchEvent(e);
            }
        });

        setContentView(root);
        applyOrientation();
        immersive();
        for (Panel p : panels) p.show();
        updateHud();
    }

    // ---------- запись просмотра (3.9) ----------

    private TextView corner(String icon) {
        TextView t = new TextView(this);
        t.setText(icon);
        t.setTextSize(22);
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(6, 0, 0, Color.BLACK);
        int cp = Ui.dp(this, 12);
        t.setPadding(cp, cp, cp, cp);
        t.setClickable(true);
        return t;
    }

    private static String recUri(MediaDb.Item it) {
        return android.net.Uri.fromFile(it.file).toString();
    }

    private long recPos() {
        if (multi || panels.isEmpty()) return 0;
        Panel p = panels.get(0);
        try {
            return p.items.get(p.index).isVideo() ? p.video.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void recBegin() {
        if (multi || !Recorder.on() || panels.isEmpty()) return;
        Panel p = panels.get(0);
        MediaDb.Item it = p.items.get(p.index);
        Recorder.end(this, recPos());
        Recorder.begin(this, recUri(it), it.file.getName(), !it.isVideo(), 0);
        recUi();
    }

    private void recUi() {
        if (recBtn == null) return;
        boolean on = Recorder.on();
        recBtn.setText(on ? Ui.t(R.string.rec_on) : "⏺");
        recBtn.setTextColor(on ? 0xFFFF5A5F : Color.WHITE);
        dislikeBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && !panels.isEmpty()) {
            Panel p = panels.get(0);
            dislikeBtn.setAlpha(Recorder.excluded(this, recUri(p.items.get(p.index))) ? 1f : 0.55f);
        }
    }

    private void toggleRec() {
        if (Recorder.on()) {
            Recorder.stop(this, recPos());
            Toast.makeText(this, Ui.t(R.string.rec_stopped), Toast.LENGTH_LONG).show();
        } else {
            View r = container;
            Recorder.start(this, panels.get(0).label != null ? panels.get(0).label : Ui.t(R.string.home_offline),
                    r.getHeight() > 0 ? r.getWidth() / (float) r.getHeight() : 9f / 16f);
            Toast.makeText(this, Ui.t(R.string.rec_started), Toast.LENGTH_LONG).show();
            recBegin();
        }
        recUi();
    }

    // ---------- раскладка ----------

    private void applyOrientation() {
        boolean wide = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        container.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int gap = Ui.dp(this, 1);
        for (Panel p : panels) {
            LinearLayout.LayoutParams lp = wide
                    ? new LinearLayout.LayoutParams(0, -1, 1f)
                    : new LinearLayout.LayoutParams(-1, 0, 1f);
            lp.setMargins(gap, gap, gap, gap);
            p.root.setLayoutParams(lp);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation();
        immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    // ---------- управление ----------

    private void togglePause() {
        paused = !paused;
        for (Panel p : panels) p.onPauseChanged();
        updateHud();
    }

    private void updateHud() {
        String txt;
        if (multi) {
            StringBuilder sb = new StringBuilder(paused ? "⏸" : "▶");
            for (Panel p : panels) sb.append("   ").append(p.title()).append(" ").append(p.index + 1);
            txt = sb.toString();
        } else {
            Panel p = panels.get(0);
            txt = (paused ? "⏸  " : "▶  ") + (p.index + 1) + " / " + p.items.size();
        }
        hud.setText(txt);
        hud.animate().cancel();
        hud.setAlpha(1f);
        h.removeCallbacks(hideHud);
        h.postDelayed(hideHud, 1800);
    }

    // ---------- панель ----------

    private class Panel {
        final String label;
        final List<MediaDb.Item> items;
        final FrameLayout root;
        final ImageView image;
        final VideoView video;
        int index;
        int token;

        final Runnable advance = new Runnable() {
            @Override
            public void run() {
                next(1);
            }
        };

        Panel(String label, List<MediaDb.Item> items) {
            this.label = label;
            this.items = items;
            root = new FrameLayout(SlideshowActivity.this);
            root.setBackgroundColor(Color.BLACK);

            image = new ImageView(SlideshowActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(image, new FrameLayout.LayoutParams(-1, -1));

            video = new VideoView(SlideshowActivity.this);
            root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (!paused) next(1);
                }
            });
            video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    h.postDelayed(advance, 800);
                    return true;
                }
            });
            if (multiSource()) {
                video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        mp.setVolume(0f, 0f); // при нескольких панелях звук глушим
                    }
                });
            }

            // подпись-источник, маленькая и полупрозрачная
            if (multiSource()) {
                TextView chip = new TextView(SlideshowActivity.this);
                chip.setText(title());
                chip.setTextColor(Color.WHITE);
                chip.setTextSize(11);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
                chip.setBackgroundColor(0x66000000);
                chip.setPadding(Ui.dp(SlideshowActivity.this, 8), Ui.dp(SlideshowActivity.this, 2),
                        Ui.dp(SlideshowActivity.this, 8), Ui.dp(SlideshowActivity.this, 3));
                FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                cp.setMargins(Ui.dp(SlideshowActivity.this, 6), Ui.dp(SlideshowActivity.this, 6), 0, 0);
                root.addView(chip, cp);
            }

            root.addView(arrow("‹", Gravity.START, -1));
            root.addView(arrow("›", Gravity.END, 1));

            // 3.4: 🗑 — удалить текущий файл из офлайна прямо во время слайдшоу
            TextView trash = new TextView(SlideshowActivity.this);
            trash.setText("🗑");
            trash.setTextSize(20);
            trash.setShadowLayer(6, 0, 0, Color.BLACK);
            int tp = Ui.dp(SlideshowActivity.this, 14);
            trash.setPadding(tp, tp, tp, tp);
            trash.setOnClickListener(v -> deleteCurrent());
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            tl.topMargin = Ui.dp(SlideshowActivity.this, 16);
            root.addView(trash, tl);
        }

        void deleteCurrent() {
            if (items.isEmpty()) return;
            final MediaDb.Item it = items.get(index);
            h.removeCallbacks(advance);
            new android.app.AlertDialog.Builder(SlideshowActivity.this)
                    .setMessage(Ui.t(R.string.delete_confirm))
                    .setPositiveButton(Ui.t(R.string.remove), (d, w) -> {
                        video.stopPlayback();
                        MediaDb.get(SlideshowActivity.this).delete(it.url);
                        items.remove(index);
                        android.widget.Toast.makeText(SlideshowActivity.this, Ui.t(R.string.deleted), android.widget.Toast.LENGTH_SHORT).show();
                        if (items.isEmpty()) {
                            panels.remove(this);
                            container.removeView(root);
                            if (panels.isEmpty()) close();
                            return;
                        }
                        index = index % items.size();
                        show();
                        updateHud();
                    })
                    .setNegativeButton(Ui.t(R.string.cancel), (d, w) -> {
                        if (!paused && !items.get(index).isVideo()) h.postDelayed(advance, intervalMs);
                    })
                    .show();
        }

        private boolean multiSource() {
            return getIntent().getStringArrayExtra("labels") != null
                    && getIntent().getStringArrayExtra("labels").length > 1;
        }

        private TextView arrow(String glyph, int side, final int dir) {
            TextView a = new TextView(SlideshowActivity.this);
            a.setText(glyph);
            a.setTextColor(Color.WHITE);
            a.setTextSize(34);
            a.setTypeface(Typeface.DEFAULT_BOLD);
            a.setAlpha(0.35f);
            a.setGravity(Gravity.CENTER);
            a.setPadding(Ui.dp(SlideshowActivity.this, 10), Ui.dp(SlideshowActivity.this, 16),
                    Ui.dp(SlideshowActivity.this, 10), Ui.dp(SlideshowActivity.this, 16));
            a.setClickable(true);
            a.setBackgroundColor(0x22000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | side);
            a.setLayoutParams(lp);
            a.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    next(dir);
                    updateHud();
                }
            });
            return a;
        }

        String title() {
            return MediaCache.titleFor(label);
        }

        void next(int dir) {
            if (!multi && Recorder.on()) Recorder.end(SlideshowActivity.this, recPos());
            index = (index + dir + items.size()) % items.size();
            show();
        }

        void onPauseChanged() {
            MediaDb.Item it = items.get(index);
            if (paused) {
                h.removeCallbacks(advance);
                if (it.isVideo()) video.pause();
                if (Build.VERSION.SDK_INT >= 28 && image.getDrawable() instanceof AnimatedImageDrawable)
                    ((AnimatedImageDrawable) image.getDrawable()).stop();
            } else {
                if (it.isVideo()) video.start();
                else h.postDelayed(advance, intervalMs);
                if (Build.VERSION.SDK_INT >= 28 && image.getDrawable() instanceof AnimatedImageDrawable)
                    ((AnimatedImageDrawable) image.getDrawable()).start();
            }
        }

        void show() {
            final int my = ++token;
            h.removeCallbacks(advance);
            final MediaDb.Item it = items.get(index);
            if (!multi && Recorder.on()) {   // запись: новый кусок с начала файла
                Recorder.begin(SlideshowActivity.this, recUri(it), it.file.getName(), !it.isVideo(), 0);
                recUi();
            }
            if (it.isVideo()) {
                image.setImageDrawable(null);
                image.setVisibility(View.GONE);
                video.setVisibility(View.VISIBLE);
                video.setVideoPath(it.file.getPath());
                video.start();
                if (paused) video.pause();
                return;
            }
            video.stopPlayback();
            video.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            decoder.execute(new Runnable() {
                @Override
                public void run() {
                    final Drawable d = decode(it);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my != token || isFinishing()) return;
                            image.setImageDrawable(d);
                            if (Build.VERSION.SDK_INT >= 28 && d instanceof AnimatedImageDrawable && !paused)
                                ((AnimatedImageDrawable) d).start();
                            if (d == null) h.postDelayed(advance, 300);
                            else if (!paused) h.postDelayed(advance, intervalMs);
                        }
                    });
                }
            });
        }

        void stop() {
            h.removeCallbacks(advance);
            video.stopPlayback();
        }
    }

    private Drawable decode(MediaDb.Item it) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            final int max = Math.max(dm.widthPixels, dm.heightPixels);
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(it.file);
                return ImageDecoder.decodeDrawable(src, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder dec, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                        int w = info.getSize().getWidth(), hh = info.getSize().getHeight();
                        int big = Math.max(w, hh);
                        if (big > max * 1.5) {
                            float k = (float) max / big;
                            dec.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(hh * k)));
                        }
                    }
                });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.file.getPath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            android.graphics.Bitmap b = BitmapFactory.decodeFile(it.file.getPath(), o);
            return b == null ? null : new android.graphics.drawable.BitmapDrawable(getResources(), b);
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean closing;

    /** Быстрый выход: гасим таймеры и прячем панели сразу, плееры освобождаем в onStop (экран уже закрыт). */
    private void close() {
        if (closing) return;
        if (Recorder.on() && !multi) {
            Recorder.stop(this, recPos());
            Toast.makeText(this, Ui.t(R.string.rec_stopped), Toast.LENGTH_LONG).show();
        }
        closing = true;
        h.removeCallbacksAndMessages(null);
        container.setVisibility(View.GONE);
        finish();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing()) for (Panel p : panels) p.stop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!closing && !multi && Recorder.on()) Recorder.end(this, recPos());   // экран ушёл — кусок закрываем
        h.removeCallbacksAndMessages(null);
        for (Panel p : panels) {
            if (p.video.isPlaying()) p.video.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!paused && !closing) for (Panel p : panels) p.show();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        decoder.shutdownNow();
        for (Panel p : panels) p.stop();
        super.onDestroy();
    }
}
