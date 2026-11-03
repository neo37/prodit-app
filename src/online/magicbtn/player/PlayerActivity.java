package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Плеер и слайдшоу — онлайн и офлайн. Видео — до конца, фото — по интервалу, затем следующее;
 * loop=true — по кругу. Свайп влево/вправо — листать, вниз — выйти, тап — пауза и перемотка.
 * ✕ — выход, ⋮ — порядок (подряд, перемешать, по лайкам, по комментариям, новые), интервал фото, поделиться.
 * Справа: ♥ 💬 ↗ (в чат) 🗑 (с устройства) 🚩. Сетевые файлы сохраняются в офлайн по правилам источника.
 */
public class PlayerActivity extends Activity {

    /** Список для плеера. Через Intent не передаём: на ~2000 файлов hn он больше лимита Binder (1 МБ) и падает. */
    static final class Playlist {
        String[] uris, titles, labels;
        boolean[] images;
        boolean loop;
        String key, page, sourceName;
        int photoMs = 5000;
        boolean shuffle;
    }

    static Playlist next;

    private static final int[] INTERVALS = {3000, 5000, 8000, 15000};

    private final List<Integer> order = new ArrayList<>(); // индексы в исходных массивах
    private String[] uris, titles, labels;
    private boolean[] images;
    private boolean loop;
    private int pos, photoMs;
    private String key, page, sourceName;
    private VideoView video;
    private ImageView photo;
    private TextView title, likeBtn, commentBtn, shareBtn, deleteBtn, reportBtn, recBtn, dislikeBtn;
    private LinearLayout socialBar, topBar;
    private Social.Ref ref;
    private MediaController controls;
    // 3.10: редактор — обрезка (рамка: зелёная — оставить, красная — вырезать)
    private boolean editing;
    private LinearLayout editBar;
    private TextView cutBtn, saveBtn;
    private View frame;
    private final Runnable editTick = new Runnable() {
        @Override
        public void run() {
            editUi();
            if (!closing && editShown()) video.postDelayed(this, 150);
        }
    };
    private MediaCache cache;
    private boolean paused, closing;
    private FrameLayout root;
    private TextView seekHint;
    private float zoom = 1f, panX, panY; // увеличение и сдвиг текущего видео/фото
    private int seekSum; // накопленная перемотка двойными тапами (сек)
    private boolean scaling;
    private int shownSeq; // защита от устаревших загрузок фото
    // 3.4: длинные видео по сети — при обрыве переподключаемся с того же места (до 3 раз), а не листаем дальше
    private int retries, lastPos;
    private boolean reopening;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (video.isPlaying()) lastPos = video.getCurrentPosition();
            } catch (Exception ignored) {
            }
            if (!closing) video.postDelayed(this, 1000);
        }
    };

    private final Runnable nextPhoto = () -> {
        if (!paused && !closing) go(1);
    };
    private final Runnable hideUi = () -> {
        if (!paused && (controls == null || !controls.isShowing())) {
            title.setVisibility(View.GONE);
            topBar.setAlpha(0.35f);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cache = MediaCache.get(this);
        Playlist pl = next;
        if (pl == null || pl.uris == null || pl.uris.length == 0) {
            finish();
            return;
        }
        uris = pl.uris;
        titles = pl.titles;
        labels = pl.labels;
        images = pl.images != null ? pl.images : new boolean[uris.length];
        loop = pl.loop;
        key = pl.key;
        page = pl.page;
        sourceName = pl.sourceName;
        photoMs = pl.photoMs;
        for (int i = 0; i < uris.length; i++) order.add(i);
        int start = state != null ? state.getInt("pos") : getIntent().getIntExtra("pos", 0);
        if (pl.shuffle) {
            Collections.shuffle(order);
            start = 0;
        }
        pos = Math.max(0, Math.min(start, uris.length - 1));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(photo, new FrameLayout.LayoutParams(-1, -1));

        // слой жестов: щипок — увеличение, двойной тап — перемотка ±10 с (по краям) или зум (в центре)
        final View touchLayer = new View(this);
        root.addView(touchLayer, new FrameLayout.LayoutParams(-1, -1));
        seekHint = new TextView(this);
        seekHint.setTextColor(Color.WHITE);
        seekHint.setTextSize(20);
        seekHint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        seekHint.setShadowLayer(8, 0, 0, Color.BLACK);
        seekHint.setVisibility(View.GONE);
        root.addView(seekHint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // верх: ✕, название, ⋮
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 8);
        topBar.setPadding(p, Ui.dp(this, 28), p, p);
        TextView close = icon("✕", 22);
        close.setOnClickListener(v -> close());
        topBar.addView(close);
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setShadowLayer(4, 0, 0, Color.BLACK);
        title.setSingleLine(true);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        recBtn = icon("⏺", 20);   // 3.4: запись просмотра слайдшоу → «Мои просмотры»
        recBtn.setOnClickListener(v -> toggleRec());
        topBar.addView(recBtn);
        TextView menu = icon("⋮", 24);
        menu.setOnClickListener(v -> menu());
        topBar.addView(menu);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // справа: лайк / комментарии / в чат / удалить / жалоба
        socialBar = new LinearLayout(this);
        socialBar.setOrientation(LinearLayout.VERTICAL);
        likeBtn = icon("", 18);
        commentBtn = icon("", 18);
        shareBtn = icon("↗", 22);
        deleteBtn = icon("🗑", 20);
        reportBtn = icon("🚩", 18);
        dislikeBtn = icon("👎", 20);   // во время записи: файл не войдёт в склейку
        dislikeBtn.setOnClickListener(v -> toggleDislike());
        for (TextView t : new TextView[]{likeBtn, commentBtn, shareBtn, deleteBtn, reportBtn, dislikeBtn}) socialBar.addView(t);
        dislikeBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        sb.rightMargin = Ui.dp(this, 8);
        root.addView(socialBar, sb);
        buildEditor();
        // 3.9: при первом открытии плеера — Алиса показывает жесты и кнопки
        Tour.once(this, "player", new Tour.Step[]{
                new Tour.Step(null, Ui.t(R.string.hint_player), "hint_player"),
                new Tour.Step(likeBtn, Ui.t(R.string.hint_like), "hint_like"),
                new Tour.Step(recBtn, Ui.t(R.string.hint_rec), "hint_rec"),
                new Tour.Step(shareBtn, Ui.t(R.string.hint_share), "hint_share")});
        likeBtn.setOnClickListener(v -> like());
        commentBtn.setOnClickListener(v -> {
            if (ref != null) SocialUi.comments(this, ref, () -> showStat(MediaDb.get(this).stat(ref.skey())));
        });
        shareBtn.setOnClickListener(v -> shareToChat());
        deleteBtn.setOnClickListener(v -> deleteCurrent());
        reportBtn.setOnClickListener(v -> {
            if (ref != null) SocialUi.report(this, ref.api, ref.key, "file", ref.folder + "/" + ref.file);
        });
        setContentView(root);

        controls = new MediaController(this);
        controls.setAnchorView(video);
        controls.setPrevNextListeners(v -> go(1), v -> go(-1));
        video.setMediaController(controls);
        video.setOnCompletionListener(mp -> {
            saveWatched(true);
            if (!closing) go(1);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            if (uris[cur()].startsWith("http") && retries < 3 && !closing) {   // обрыв связи на длинном видео
                retries++;
                showUi();
                title.setText(Ui.t(R.string.stream_retry));
                final int at = lastPos;
                video.postDelayed(() -> {
                    if (closing) return;
                    reopening = true;
                    start(at);
                }, 1500L * retries);
                return true;
            }
            showUi();
            title.setText(Ui.t(R.string.player_open_failed, titles[cur()]));
            title.postDelayed(nextPhoto, 2000);
            return true;
        });

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showUi();
                if (images[cur()]) {
                    paused = !paused;
                    title.setText((paused ? "⏸ " : "▶ ") + caption());
                    photo.removeCallbacks(nextPhoto);
                    if (!paused) photo.postDelayed(nextPhoto, photoMs);
                } else if (controls.isShowing()) {
                    controls.hide();
                } else {
                    controls.show(3000);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float w = root.getWidth();
                boolean photoNow = images[cur()];
                if (photoNow || (e.getX() > w / 3 && e.getX() < w * 2 / 3)) {
                    // центр (и любое место на фото): увеличить ×2 в точке тапа / вернуть
                    if (zoom > 1.05f) resetZoom();
                    else zoomBy(2.5f, e.getX(), e.getY());
                    return true;
                }
                seek(e.getX() < w / 2 ? -10 : 10);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (zoom <= 1.01f || scaling) return false;
                pan(-dx, -dy); // увеличено — палец двигает картинку
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || zoom > 1.01f || scaling) return false; // при увеличении свайп не листает
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (dy > Ui.dp(PlayerActivity.this, 120) && Math.abs(dy) > Math.abs(dx)) {
                    close(); // свайп вниз — выход
                    return true;
                }
                if (Math.abs(vx) < Math.abs(vy)) return false;
                go(vx < 0 ? 1 : -1);
                return true;
            }
        });
        final android.view.ScaleGestureDetector sg = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                        scaling = true;
                        return true;
                    }

                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d) {
                        zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                        return true;
                    }

                    @Override
                    public void onScaleEnd(android.view.ScaleGestureDetector d) {
                        root.postDelayed(() -> scaling = false, 150);
                        if (zoom < 1.05f) resetZoom();
                    }
                });
        touchLayer.setOnTouchListener((v, e) -> {
            sg.onTouchEvent(e);
            if (e.getPointerCount() > 1) return true;
            return gd.onTouchEvent(e);
        });

        start(state != null ? state.getInt("ms") : 0);
    }

    // ---------- увеличение и перемотка ----------

    /** Текущий слой: видео (SurfaceView — увеличиваем размером, не scale) или фото. */
    private View target() {
        return images[cur()] ? photo : video;
    }

    private void zoomBy(float f, float fx, float fy) {
        float old = zoom;
        zoom = Math.max(1f, Math.min(5f, zoom * f));
        // точка под пальцами остаётся на месте
        float cx = fx - root.getWidth() / 2f, cy = fy - root.getHeight() / 2f;
        panX = cx - (cx - panX) * zoom / old;
        panY = cy - (cy - panY) * zoom / old;
        applyZoom();
    }

    private void pan(float dx, float dy) {
        panX += dx;
        panY += dy;
        applyZoom();
    }

    private void applyZoom() {
        float mx = root.getWidth() * (zoom - 1) / 2f, my = root.getHeight() * (zoom - 1) / 2f;
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
        if (images[cur()]) {
            photo.setScaleX(zoom);
            photo.setScaleY(zoom);
        } else {
            // VideoView (SurfaceView) не масштабируется через setScaleX — увеличиваем сам размер вида
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
            int w = Math.round(root.getWidth() * zoom), h = Math.round(root.getHeight() * zoom);
            if (lp.width != w || lp.height != h) {
                lp.width = zoom <= 1.01f ? -1 : w;
                lp.height = zoom <= 1.01f ? -1 : h;
                lp.gravity = Gravity.CENTER;
                video.setLayoutParams(lp);
            }
        }
        target().setTranslationX(panX);
        target().setTranslationY(panY);
        if (Recorder.on() && root.getWidth() > 0)
            Recorder.zoom(zoom, panX / root.getWidth(), panY / root.getHeight());
    }

    private void resetZoom() {
        Recorder.resetZoom();
        zoom = 1f;
        panX = panY = 0;
        photo.setScaleX(1f);
        photo.setScaleY(1f);
        photo.setTranslationX(0);
        photo.setTranslationY(0);
        video.setTranslationX(0);
        video.setTranslationY(0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (lp.width != -1 || lp.height != -1) {
            lp.width = -1;
            lp.height = -1;
            video.setLayoutParams(lp);
        }
    }

    private final Runnable hideSeek = () -> {
        seekSum = 0;
        seekHint.setVisibility(View.GONE);
    };

    /** Двойной тап у края: ±10 с; несколько подряд — складываются (как в YouTube). */
    private void seek(int sec) {
        if (images[cur()]) return;
        if (Integer.signum(sec) != Integer.signum(seekSum)) seekSum = 0;
        seekSum += sec;
        int to = Math.max(0, Math.min(video.getDuration() > 0 ? video.getDuration() - 500 : Integer.MAX_VALUE,
                video.getCurrentPosition() + sec * 1000));
        Recorder.end(this, video.getCurrentPosition());   // перемотка в записи — новый кусок
        video.seekTo(to);
        recBegin(to);
        seekHint.setText(seekSum < 0 ? "⏪ " + (-seekSum) + " " + Ui.t(R.string.sec) : seekSum + " " + Ui.t(R.string.sec) + " ⏩");
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) seekHint.getLayoutParams();
        lp.gravity = Gravity.CENTER_VERTICAL | (sec < 0 ? Gravity.START : Gravity.END);
        lp.leftMargin = lp.rightMargin = Ui.dp(this, 48);
        seekHint.setLayoutParams(lp);
        seekHint.setVisibility(View.VISIBLE);
        seekHint.removeCallbacks(hideSeek);
        seekHint.postDelayed(hideSeek, 900);
    }

    private TextView icon(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(6, 0, 0, Color.BLACK);
        int p = Ui.dp(this, 10);
        t.setPadding(p, p, p, p);
        return t;
    }

    private int cur() {
        return order.get(pos);
    }

    private String caption() {
        return (pos + 1) + " / " + uris.length + " · " + titles[cur()];
    }

    private void showUi() {
        title.setVisibility(View.VISIBLE);
        topBar.setAlpha(1f);
        title.removeCallbacks(hideUi);
        title.postDelayed(hideUi, 3000);
    }

    private void showStat(MediaDb.Stat s) {
        likeBtn.setText((s.liked ? "♥" : "♡") + "\n" + s.likes);
        likeBtn.setTextColor(s.liked ? Ui.YELLOW : Color.WHITE);
        commentBtn.setText("💬\n" + s.comments);
    }

    /** Адрес для скачивания: закрытые папки video_api.php требуют ключ — он идёт параметром. */
    private String downloadUrl(String u) {
        if (key == null || key.isEmpty() || !u.contains("action=stream")) return u;
        return u + "&key=" + Uri.encode(key);
    }

    private void start(int fromMs) {
        if (closing) return;
        resetZoom(); // новое видео/фото — без увеличения
        photo.removeCallbacks(nextPhoto);
        title.removeCallbacks(nextPhoto);
        shownSeq++;
        int i = cur();
        title.setText(caption());
        showUi();
        if (!reopening) {
            retries = 0;
            lastPos = 0;
            Stats.add(this, Stats.PLAYER, 1);   // 3.8: статистика кнопки «Видеоплеер» — открыто видео/фото
        }
        reopening = false;

        String u = uris[i];
        boolean remote = u.startsWith("http");
        String label = labels == null ? null : labels[i];
        ref = Social.ref(this, u, label);
        likeBtn.setVisibility(ref == null ? View.GONE : View.VISIBLE);
        commentBtn.setVisibility(ref == null ? View.GONE : View.VISIBLE);
        reportBtn.setVisibility(ref == null || ref.local() ? View.GONE : View.VISIBLE);
        shareBtn.setVisibility(View.VISIBLE); // своё видео с телефона тоже можно отправить (3.4)
        if (ref != null) showStat(MediaDb.get(this).stat(ref.skey()));
        File local = remote ? cache.cached(downloadUrl(u)) : null;
        deleteBtn.setVisibility(local != null || Trash.isPhone(u) ? View.VISIBLE : View.GONE);
        // 3.4: видео сохраняем в офлайн после просмотра (saveWatched), а не сразу — иначе длинное видео
        // качается целиком параллельно с просмотром и тормозит; фото — сразу, они маленькие
        if (remote && local == null && label != null && images[i]) {
            boolean liked = ref != null && MediaDb.get(this).stat(ref.skey()).liked;
            cache.save(downloadUrl(u), label, page, liked);
        }

        if (images[i]) {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            photo.setVisibility(View.VISIBLE);
            photo.setImageBitmap(null);
            loadPhoto(local, u, shownSeq);
            recBegin(0);
            if (!paused) photo.postDelayed(nextPhoto, photoMs);
            return;
        }
        photo.setVisibility(View.GONE);
        video.setVisibility(View.VISIBLE);
        if (local != null) {
            video.setVideoURI(Uri.fromFile(local));
        } else {
            Map<String, String> h = new HashMap<>();
            if (key != null && !key.isEmpty()) h.put("X-API-Key", key);
            video.setVideoURI(Uri.parse(u), h);
        }
        // продолжить с места, где остановились в прошлый раз
        if (fromMs <= 0) {
            fromMs = Resume.get(this, u);
            if (fromMs > 0) {
                int sec = fromMs / 1000;
                seekHint.setText("▶ " + Ui.t(R.string.resume_from, String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60)));
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) seekHint.getLayoutParams();
                lp.gravity = Gravity.CENTER;
                seekHint.setLayoutParams(lp);
                seekHint.setVisibility(View.VISIBLE);
                seekHint.removeCallbacks(hideSeek);
                seekHint.postDelayed(hideSeek, 1800);
            }
        }
        if (fromMs > 0) video.seekTo(fromMs);
        video.start();
        recBegin(fromMs);
    }

    /** Запомнить позицию текущего видео (для «продолжить»). */
    private void rememberPosition() {
        if (uris == null || images == null || images[cur()] || video == null) return;
        try {
            Resume.put(this, uris[cur()], video.getCurrentPosition(), video.getDuration());
        } catch (Exception ignored) {
        }
    }

    private void loadPhoto(final File local, final String u, final int seq) {
        new Thread(() -> {
            Bitmap b = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                if (local != null) {
                    b = BitmapFactory.decodeFile(local.getPath(), o);
                } else if (u.startsWith("http")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    if (key != null && !key.isEmpty()) c.setRequestProperty("X-API-Key", key);
                    try (InputStream in = c.getInputStream()) {
                        b = BitmapFactory.decodeStream(in, null, o);
                    } finally {
                        c.disconnect();
                    }
                } else {
                    try (InputStream in = getContentResolver().openInputStream(Uri.parse(u))) {
                        b = BitmapFactory.decodeStream(in, null, o);
                    }
                }
            } catch (Exception ignored) {
            }
            final Bitmap bm = b;
            photo.post(() -> {
                if (seq == shownSeq && bm != null && !closing) photo.setImageBitmap(bm);
            });
        }).start();
    }

    // ---------- 3.4: запись просмотра ----------

    private String recUri(int i) {
        return uris[i].startsWith("http") ? downloadUrl(uris[i]) : uris[i];
    }

    private long recPos() {
        try {
            return uris == null || images[cur()] ? 0 : video.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    private void recBegin(long ms) {
        if (!Recorder.on()) return;
        int i = cur();
        Recorder.begin(this, recUri(i), titles[i], images[i], ms);
        recUi();
    }

    // ---------- редактор: обрезка (3.10) ----------

    private void buildEditor() {
        frame = new View(this);
        frame.setVisibility(View.GONE);
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        editBar = new LinearLayout(this);
        editBar.setGravity(Gravity.CENTER);
        editBar.setVisibility(View.GONE);
        TextView back = Ui.button(this, "−1 " + Ui.t(R.string.sec), Ui.WHITE);
        back.setOnClickListener(v -> seekBy(-1000));
        cutBtn = Ui.button(this, "", Ui.WHITE);
        cutBtn.setOnClickListener(v -> toggleCut());
        TextView fwd = Ui.button(this, "+1 " + Ui.t(R.string.sec), Ui.WHITE);
        fwd.setOnClickListener(v -> seekBy(1000));
        saveBtn = Ui.button(this, "💾 " + Ui.t(R.string.edit_save), Ui.YELLOW);
        saveBtn.setOnClickListener(v -> saveEdit());
        for (TextView t : new TextView[]{back, cutBtn, fwd, saveBtn}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            editBar.addView(t, lp);
        }
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = Ui.dp(this, 96);
        root.addView(editBar, bp);
    }

    /** Редактор виден: включён из меню ⋮ или идёт запись просмотра. */
    private boolean editShown() {
        return editing || Recorder.on();
    }

    private long editPos() {
        if (images[cur()]) return 0;
        try {
            return video.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    private void editUi() {
        if (editBar == null || uris == null) return;
        boolean show = editShown();
        editBar.setVisibility(show ? View.VISIBLE : View.GONE);
        frame.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        boolean cut = Cuts.cut(this, recUri(cur()), editPos());
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(cut ? 0x22FF3B30 : Color.TRANSPARENT);
        g.setStroke(Ui.dp(this, 5), cut ? 0xFFFF3B30 : 0xFF34C759);
        frame.setBackground(g);
        cutBtn.setText(cut ? "↩ " + Ui.t(R.string.edit_uncut) : "✂ " + Ui.t(R.string.edit_cut));
        saveBtn.setVisibility(editing && !Recorder.on() && !images[cur()] ? View.VISIBLE : View.GONE);
    }

    private void startEditTick() {
        video.removeCallbacks(editTick);
        video.post(editTick);
    }

    /** С этого кадра и дальше — вырезать / оставить (разметка после него переписывается). */
    private void toggleCut() {
        String u = recUri(cur());
        long t = editPos();
        Cuts.set(this, u, t, !Cuts.cut(this, u, t));
        editUi();
    }

    private void seekBy(int ms) {
        if (images[cur()]) return;
        int now = video.getCurrentPosition();
        int to = Math.max(0, Math.min(video.getDuration() > 0 ? video.getDuration() - 200 : Integer.MAX_VALUE, now + ms));
        if (Recorder.on()) Recorder.end(this, now);   // перемотка в записи — новый кусок
        video.seekTo(to);
        if (Recorder.on()) recBegin(to);
        editUi();
    }

    private void toggleEditor() {
        editing = !editing;
        if (editing) {
            Toast.makeText(this, Ui.t(R.string.edit_help), Toast.LENGTH_LONG).show();
            startEditTick();
        }
        editUi();
    }

    /** Сохранить обрезанное видео в галерею (склейка одного файла по разметке). */
    private void saveEdit() {
        final int i = cur();
        if (images[i] || Recorder.on()) return;
        final String u = recUri(i);
        if (!Cuts.any(this, u)) {
            Toast.makeText(this, Ui.t(R.string.edit_nothing), Toast.LENGTH_SHORT).show();
            return;
        }
        int dur = video.getDuration();
        if (dur <= 0) return;
        String t = "✂ " + titles[i];
        Recorder.start(this, t, root.getHeight() > 0 ? root.getWidth() / (float) root.getHeight() : 9f / 16f);
        final long rec = Recorder.current;
        Recorder.begin(this, u, titles[i], false, 0);
        Recorder.end(this, dur);
        Recorder.stop(this, 0);
        Toast.makeText(this, Ui.t(R.string.edit_saving), Toast.LENGTH_LONG).show();
        final android.content.Context app = getApplicationContext();
        Render.start(this, rec, t, new Render.Listener() {
            @Override
            public void progress(int pct) {
            }

            @Override
            public void done(android.net.Uri out, String error) {
                Toast.makeText(app, out != null ? Ui.t(R.string.edit_saved) : Ui.t(R.string.rec_failed, String.valueOf(error)),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void recUi() {
        boolean on = Recorder.on();
        recBtn.setText(on ? Ui.t(R.string.rec_on) : "⏺");
        recBtn.setTextColor(on ? 0xFFFF5A5F : Color.WHITE);
        dislikeBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        dislikeBtn.setAlpha(on && Recorder.excluded(this, recUri(cur())) ? 1f : 0.55f);
        if (on) startEditTick();
        editUi();
    }

    private void toggleRec() {
        if (Recorder.on()) {
            Recorder.stop(this, recPos());
            Toast.makeText(this, Ui.t(R.string.rec_stopped), Toast.LENGTH_LONG).show();
        } else {
            Recorder.start(this, sourceName != null ? sourceName : titles[cur()],
                    root.getHeight() > 0 ? root.getWidth() / (float) root.getHeight() : 9f / 16f);
            Toast.makeText(this, Ui.t(R.string.rec_started), Toast.LENGTH_LONG).show();
            recBegin(recPos());
            if (zoom > 1.01f) Recorder.zoom(zoom, panX / root.getWidth(), panY / root.getHeight());
        }
        recUi();
    }

    private void toggleDislike() {
        boolean ex = Recorder.toggleExclude(this, recUri(cur()));
        Toast.makeText(this, Ui.t(ex ? R.string.rec_excluded : R.string.rec_included), Toast.LENGTH_SHORT).show();
        recUi();
    }

    /** Просмотренное видео из сети — в офлайн (по правилам источника): досмотрели или посмотрели больше половины. */
    private void saveWatched(boolean done) {
        int i = cur();
        if (images[i] || labels == null || labels[i] == null || !uris[i].startsWith("http")) return;
        try {
            if (!done) {
                int d = video.getDuration(), p = video.getCurrentPosition();
                if (d <= 0 || p < d / 2) return;
            }
        } catch (Exception e) {
            return;
        }
        String u = downloadUrl(uris[i]);
        if (cache.cached(u) != null) return;
        Social.Ref r = Social.ref(this, uris[i], labels[i]);
        boolean liked = r != null && MediaDb.get(this).stat(r.skey()).liked;
        cache.saveWatched(u, labels[i], page, liked);
    }

    private void go(int d) {
        if (closing) return;
        Recorder.end(this, recPos());
        rememberPosition();
        saveWatched(false);
        int n = pos + d;
        if (n >= order.size()) {
            if (!loop) {
                close();
                return;
            }
            n = 0;
        }
        if (n < 0) n = loop ? order.size() - 1 : 0;
        pos = n;
        start(0);
    }

    // ---------- действия ----------

    private void like() {
        if (ref == null) return;
        MediaDb.Stat s = Social.toggleLike(this, ref);
        showStat(s);
        // «только лайкнутые»: лайкнули — скачиваем
        int i = cur();
        if (s.liked && labels != null && labels[i] != null) cache.save(downloadUrl(uris[i]), labels[i], page, true);
    }

    /** Поделиться видео в общем чате: сообщение с кнопкой «▶ Смотреть» и подключением источника. */
    private void shareToChat() {
        final int i = cur();
        if (!uris[i].startsWith("http")) {   // своё видео с телефона — загрузить и отправить в чат или в личку
            if (images[i]) {
                Toast.makeText(this, Ui.t(R.string.share_not_video), Toast.LENGTH_SHORT).show();
                return;
            }
            ShareVideo.pick(this, Uri.parse(uris[i]), titles[i]);
            return;
        }
        Rules.ensure(this, () -> ChatConfirm.ask(this, "🎬 " + titles[i], () -> new Thread(() -> {
            String msg;
            try {
                String api = ref != null ? ref.api : page;
                JSONObject b = new JSONObject().put("text", "🎬 " + titles[i]).put("name",
                        Social.name(this).isEmpty() ? "Guest" : Social.name(this));
                if (api != null) b.put("share_name", sourceName != null ? sourceName : Uri.parse(api).getHost())
                        .put("share_url", api).put("share_key", key == null ? "" : key);
                // папка нужна для лайков у получателя; у /media/… её нет в адресе — передаём во фрагменте
                String lab = labels == null ? null : labels[i];
                String file = uris[i];
                if (!file.contains("folder=") && lab != null && lab.contains("/"))
                    file += "#f=" + Uri.encode(lab.substring(lab.indexOf('/') + 1));
                b.put("share_file", file);
                JSONObject o = Social.call(this, ChatActivity.CHAT_API, "", "action=chat_post", "POST", b.toString());
                msg = o.optBoolean("ok") ? Ui.t(R.string.shared_to_chat)
                        : "too fast".equals(o.optString("error")) ? Ui.t(R.string.chat_too_fast) : o.optString("error");
            } catch (Exception e) {
                msg = Ui.t(R.string.chat_offline);
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
        }).start()));
    }

    /** Ссылка на текущее видео для любого мессенджера: у кого стоит приложение — откроется сразу в плеере. */
    private void shareLink() {
        final int i = cur();
        String api = ref != null ? ref.api : page;
        if (api == null || !uris[i].startsWith("https://")) {
            Toast.makeText(this, Ui.t(R.string.link_bad), Toast.LENGTH_SHORT).show();
            return;
        }
        String lab = labels == null ? null : labels[i];
        String file = uris[i];
        if (!file.contains("folder=") && lab != null && lab.contains("/"))
            file += "#f=" + Uri.encode(lab.substring(lab.indexOf('/') + 1));
        String name = sourceName != null ? sourceName : Uri.parse(api).getHost();
        ShareLink.text(this, Ui.t(R.string.video_link_text, titles[i]) + "\n"
                + ShareLink.videoLink(name, api, key, file, titles[i]));
    }

    /** Удалить сохранённую копию с устройства (без модератора — это только ваш телефон). */
    private void deleteCurrent() {
        final int i = cur();
        if (Trash.isPhone(uris[i])) {   // 3.4: файл из памяти телефона
            new AlertDialog.Builder(this)
                    .setMessage(Ui.t(R.string.delete_phone_confirm))
                    .setPositiveButton(Ui.t(R.string.remove), (d, w) -> {
                        video.pause();
                        Trash.deletePhone(this, Uri.parse(uris[i]), () -> {
                            video.stopPlayback();
                            if (order.size() <= 1) {
                                close();
                                return;
                            }
                            order.remove(pos);
                            if (pos >= order.size()) pos = 0;
                            start(0);
                        });
                    })
                    .setNegativeButton(Ui.t(R.string.cancel), null)
                    .show();
            return;
        }
        final String dl = downloadUrl(uris[i]);
        new AlertDialog.Builder(this)
                .setMessage(Ui.t(R.string.delete_confirm))
                .setPositiveButton(Ui.t(R.string.remove), (d, w) -> {
                    video.stopPlayback();
                    MediaDb.get(this).delete(dl);
                    Toast.makeText(this, Ui.t(R.string.deleted), Toast.LENGTH_SHORT).show();
                    if (!uris[i].startsWith("http") || order.size() <= 1) {
                        close();
                        return;
                    }
                    order.remove(pos);
                    if (pos >= order.size()) pos = 0;
                    start(0);
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }

    private void menu() {
        showUi();
        final String[] items = {
                "▶ " + Ui.t(R.string.order_sequential), "🔀 " + Ui.t(R.string.order_shuffle),
                "♥ " + Ui.t(R.string.sort_likes), "💬 " + Ui.t(R.string.sort_comments),
                "🕒 " + Ui.t(R.string.sort_new),
                "⏱ " + Ui.t(R.string.photo_interval, photoMs / 1000),
                "↗ " + Ui.t(R.string.share_to_chat), "🔗 " + Ui.t(R.string.video_link),
                "✂ " + Ui.t(editing ? R.string.edit_off : R.string.edit_on)};
        new AlertDialog.Builder(this)
                .setTitle(Ui.t(R.string.slideshow_mode))
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: reorder(null); break;
                        case 1: reorder("shuffle"); break;
                        case 2: reorder("likes"); break;
                        case 3: reorder("comments"); break;
                        case 4: reorder("new"); break;
                        case 5:
                            int k = 0;
                            while (k < INTERVALS.length && INTERVALS[k] != photoMs) k++;
                            photoMs = INTERVALS[(k + 1) % INTERVALS.length];
                            Toast.makeText(this, Ui.t(R.string.photo_interval, photoMs / 1000), Toast.LENGTH_SHORT).show();
                            break;
                        case 6: shareToChat(); break;
                        case 7: shareLink(); break;
                        default: toggleEditor();
                    }
                })
                .show();
    }

    /** Новый порядок; текущий файл остаётся на экране и становится первым. */
    private void reorder(String mode) {
        final int current = cur();
        final MediaDb db = MediaDb.get(this);
        order.clear();
        for (int i = 0; i < uris.length; i++) order.add(i);
        if ("shuffle".equals(mode)) {
            Collections.shuffle(order);
        } else if (mode != null && !"new".equals(mode)) {
            final boolean likes = "likes".equals(mode);
            final int[] score = new int[uris.length];
            for (int i = 0; i < uris.length; i++) {
                Social.Ref r = Social.ref(this, uris[i], labels == null ? null : labels[i]);
                if (r == null) continue;
                MediaDb.Stat s = db.stat(r.skey());
                score[i] = likes ? s.likes : s.comments;
            }
            Collections.sort(order, (a, b) -> Integer.compare(score[b], score[a]));
        }
        order.remove(Integer.valueOf(current));
        order.add(0, current);
        pos = 0;
        title.setText(caption());
        Toast.makeText(this, Ui.t(R.string.order_applied), Toast.LENGTH_SHORT).show();
    }

    /** Быстрый выход: сначала гасим таймеры и плеер, потом закрываем экран. */
    private void close() {
        if (closing) return;
        if (Recorder.on()) {
            Recorder.stop(this, recPos());
            Toast.makeText(this, Ui.t(R.string.rec_stopped), Toast.LENGTH_LONG).show();
        }
        rememberPosition();
        saveWatched(false);
        closing = true;
        photo.removeCallbacks(nextPhoto);
        title.removeCallbacks(nextPhoto);
        title.removeCallbacks(hideUi);
        if (controls != null) controls.hide();
        video.setVisibility(View.GONE);
        finish();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("pos", pos);
        if (video != null) out.putInt("ms", video.getCurrentPosition());
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        if (!Trash.onResult(this, req, res)) super.onActivityResult(req, res, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MediaCache.playing = false;
        video.removeCallbacks(tick);
        if (!closing) Recorder.end(this, recPos());   // экран ушёл — кусок закрываем
        if (!closing) rememberPosition();
        if (video != null) video.pause();
        if (photo != null) photo.removeCallbacks(nextPhoto);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MediaCache.playing = true;   // фоновое скачивание ждёт, пока смотрят — канал целиком плееру
        video.removeCallbacks(tick);
        video.post(tick);
        if (Recorder.on() && !closing && uris != null) recBegin(recPos());
        if (!closing && photo != null && images != null && images[cur()] && !paused) {
            photo.removeCallbacks(nextPhoto);
            photo.postDelayed(nextPhoto, photoMs);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // плеер освобождаем, когда экран уже не виден — выход не подвисает
        if (isFinishing() && video != null) video.stopPlayback();
    }
}
