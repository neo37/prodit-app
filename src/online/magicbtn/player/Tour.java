package online.magicbtn.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.util.Locale;

/**
 * 3.7: обучение после обновления — 3D-ведущая (маскот Алисы, assets/tour) проводит по главному экрану:
 * затемнение с «окном» на нужной кнопке, подсказка с «Далее / Пропустить», озвучка голосом телефона
 * (если на нём есть синтез речи). Один раз на каждую версию приложения; повторить — ☰ → «Обучение».
 */
final class Tour {

    /** Шаг: что подсветить (null — без подсветки) и что сказать. */
    static final class Step {
        final View target;
        final String title, text, voice;

        /** voice — имя заранее озвученного файла assets/tour/ru/<voice>.mp3 (Silero) или путь «help/ru/chat»;
         *  для других языков — голос телефона. */
        Step(View target, String text, String voice) {
            this(target, null, text, voice);
        }

        Step(View target, String title, String text, String voice) {
            this.target = target;
            this.title = title;
            this.text = text;
            this.voice = voice;
        }
    }

    /** Подробное обучение: где остановились, закончили или отложили. */
    interface Progress {
        void at(int i);

        void done();

        void postponed(int i);
    }

    private static final String SCENE = "https://appassets.androidplatform.net/assets/tour/index.html";

    private Tour() {
    }

    static boolean due(Context c) {
        return !c.getSharedPreferences("tour", 0).getBoolean("v" + BuildConfig.VERSION_CODE, false);
    }

    static void markSeen(Context c) {
        markDone(c);
    }

    private static void markDone(Context c) {
        c.getSharedPreferences("tour", 0).edit().putBoolean("v" + BuildConfig.VERSION_CODE, true).apply();
    }

    static void start(final Activity a, final Step[] steps) {
        markDone(a);   // сразу: даже если что-то пойдёт не так, обучение не будет вылезать снова
        show(a, steps);
    }

    /** 3.9: короткая подсказка экрана — один раз при первом открытии (key — имя экрана). */
    static void once(final Activity a, final String key, final Step[] steps) {
        if (Flags.PRODIT) return;   // подсказки с Алисой — только в Умном плеере
        android.content.SharedPreferences p = a.getSharedPreferences("tour", 0);
        if (p.getBoolean("once_" + key, false)) return;
        p.edit().putBoolean("once_" + key, true).apply();
        a.getWindow().getDecorView().postDelayed(() -> {
            if (!a.isFinishing()) show(a, steps);
        }, 1200);
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void show(final Activity a, final Step[] steps) {
        if (Flags.PRODIT) return;   // подсказки с Алисой — только в Умном плеере
        guide(a, steps, 0, null);
    }

    /** 3.12: подробное обучение — «‹ Назад», «Отложить», «Далее ›»; начинается с шага from; p — сохранение прогресса. */
    /** Подсказка сейчас на экране (чтобы не наложить вторую). */
    static boolean showing;

    static void guide(final Activity a, final Step[] steps, int from, final Progress prog) {
        if (Flags.PRODIT) return;   // подсказки с Алисой — только в Умном плеере
        if (showing) return;
        showing = true;
        final boolean full = prog != null;
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        final FrameLayout layer = new FrameLayout(a);
        layer.setClickable(true);   // под затемнением ничего не нажимается
        final Dim dim = new Dim(a);
        layer.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        // 3D-ведущая — прозрачная сцена в нижнем углу
        final WebView web = new WebView(a);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(a)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;
            }
        });
        web.loadUrl(SCENE);
        // маленькая ведущая: ходит от кнопки к кнопке (сдвиг вида), в сцене — шаг на месте и разворот
        final int mw = Ui.dp(a, 72), mh = Ui.dp(a, 112);   // 3.10: меньше
        layer.addView(web, new FrameLayout.LayoutParams(mw, mh, Gravity.TOP | Gravity.START));

        // подсказка
        final LinearLayout bubble = new LinearLayout(a);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(Ui.box(a, Ui.WHITE, 2));
        int p = Ui.dp(a, 16);
        bubble.setPadding(p, p, p, Ui.dp(a, 10));
        final TextView head = new TextView(a);
        head.setTextColor(Ui.BLACK);
        head.setTextSize(19);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(0, 0, 0, Ui.dp(a, 6));
        bubble.addView(head);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        final TextView text = new TextView(a);
        text.setTextColor(Ui.BLACK);
        text.setTextSize(full ? 16 : 17);
        text.setLineSpacing(0, 1.25f);
        scroll.addView(text);
        final LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, -2);
        bubble.addView(scroll, scl);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, 0);
        final TextView counter = new TextView(a);
        counter.setTextColor(Ui.GRAY);
        counter.setTextSize(14);
        row.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView back = Ui.button(a, "‹", Ui.WHITE);
        if (full) {
            row.addView(back, 0);
            ((LinearLayout.LayoutParams) counter.getLayoutParams()).leftMargin = Ui.dp(a, 8);
        }
        final TextView skip = Ui.button(a, Ui.t(full ? R.string.tour_later : R.string.tour_skip), Ui.WHITE);
        final TextView next = Ui.button(a, Ui.t(R.string.tour_next), Ui.YELLOW);
        row.addView(skip);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.leftMargin = Ui.dp(a, 8);
        row.addView(next, nl);
        bubble.addView(row);
        final FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        bl.setMargins(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        layer.addView(bubble, bl);
        bubble.setClickable(true);        web.bringToFront();   // 3.10: ведущая — поверх подсказки, а не под ней

        decor.addView(layer, new ViewGroup.LayoutParams(-1, -1));
        layer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {   // экран закрыли посреди подсказки
                showing = false;
            }
        });
        Fonts.attach(a);

        // голос: русский — заранее озвученные файлы (Silero), иначе — голос телефона (если есть)
        String lang = Look.lang(a);
        final boolean ru = lang.isEmpty() ? "ru".equals(a.getResources().getConfiguration().getLocales().get(0).getLanguage()) : "ru".equals(lang);
        final android.media.MediaPlayer[] mp = {null};
        final TextToSpeech[] tts = {null};
        final boolean[] ttsOk = {false};
        tts[0] = new TextToSpeech(a, st -> {
            if (st != TextToSpeech.SUCCESS) return;
            String l = Look.lang(a);
            Locale loc = l.isEmpty() ? a.getResources().getConfiguration().getLocales().get(0) : Locale.forLanguageTag(l);
            int res = tts[0].setLanguage(loc);
            ttsOk[0] = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED;
        });

        final int[] i = {Math.max(0, Math.min(from, steps.length - 1))};
        final Runnable[] show = new Runnable[1];
        final Runnable end = () -> {
            if (mp[0] != null) {
                mp[0].release();
                mp[0] = null;
            }
            if (tts[0] != null) {
                tts[0].stop();
                tts[0].shutdown();
            }
            web.destroy();
            decor.removeView(layer);
            showing = false;
        };
        show[0] = () -> {
            Step s = steps[i[0]];
            boolean last = i[0] == steps.length - 1;
            if (prog != null) prog.at(i[0]);
            head.setText(s.title == null ? "" : s.title);
            head.setVisibility(s.title == null ? View.GONE : View.VISIBLE);
            text.setText(s.text);
            scroll.scrollTo(0, 0);
            counter.setText((i[0] + 1) + " / " + steps.length);
            next.setText(Ui.t(last ? R.string.tour_done : R.string.tour_next) + (full && !last ? " ›" : ""));
            skip.setVisibility(last ? View.GONE : View.VISIBLE);
            back.setVisibility(i[0] > 0 ? View.VISIBLE : View.INVISIBLE);
            RectF hole = null;
            if (s.target != null && s.target.isShown()) {
                int[] t = new int[2], o = new int[2];
                s.target.getLocationInWindow(t);
                layer.getLocationInWindow(o);
                float pad = Ui.dp(a, 6);
                hole = new RectF(t[0] - o[0] - pad, t[1] - o[1] - pad,
                        t[0] - o[0] + s.target.getWidth() + pad, t[1] - o[1] + s.target.getHeight() + pad);
            }
            dim.hole = hole;
            dim.invalidate();
            // подсказка — под подсвеченным, если оно в верхней половине, иначе над ним; без подсветки — по центру
            int h = layer.getHeight();
            bubble.measure(View.MeasureSpec.makeMeasureSpec(layer.getWidth() - Ui.dp(a, 32), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            scl.height = -2;
            scroll.setLayoutParams(scl);
            bubble.measure(View.MeasureSpec.makeMeasureSpec(layer.getWidth() - Ui.dp(a, 32), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int maxH = (int) (h * 0.58f);
            if (bubble.getMeasuredHeight() > maxH) {   // длинный раздел — текст прокручивается внутри подсказки
                scl.height = Math.max(Ui.dp(a, 120), scroll.getMeasuredHeight() - (bubble.getMeasuredHeight() - maxH));
                scroll.setLayoutParams(scl);
                bubble.measure(View.MeasureSpec.makeMeasureSpec(layer.getWidth() - Ui.dp(a, 32), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            }
            int bh = bubble.getMeasuredHeight();
            int top;
            if (hole == null) top = (h - bh) / 3;
            else if (hole.centerY() < h / 2f) top = (int) hole.bottom + Ui.dp(a, 12);
            else top = (int) hole.top - bh - Ui.dp(a, 12);
            bl.topMargin = Math.max(Ui.dp(a, 40), Math.min(h - bh - Ui.dp(a, 16), top));
            bubble.setLayoutParams(bl);
            // 3.12: маленькая ведущая стоит на верхнем краю подсказки, справа — поверх неё, не закрывая подсветку
            int W = layer.getWidth();
            float tx = W - mw - Ui.dp(a, 20);
            float ty = bl.topMargin - mh + Ui.dp(a, 22);
            if (ty < 0) ty = Math.min(h - mh, bl.topMargin + bh - Ui.dp(a, 22));   // сверху нет места — у нижнего края
            float dx = tx - web.getTranslationX();
            web.evaluateJavascript("window.tour&&(tour.face(" + (Math.abs(dx) < 4 ? 0 : dx > 0 ? 1 : -1) + "),tour.play('walk'))", null);
            web.animate().translationX(tx).translationY(ty).setDuration(Math.abs(dx) < 4 && Math.abs(ty - web.getTranslationY()) < 4 ? 1 : 900)
                    .withEndAction(() -> web.evaluateJavascript("window.tour&&(" + (last ? "tour.face(0),tour.play('dance')" : "tour.random()") + ")", null))
                    .start();
            // голос
            if (mp[0] != null) {
                mp[0].release();
                mp[0] = null;
            }
            if (ttsOk[0]) tts[0].stop();
            boolean played = false;
            if (ru && s.voice != null) {
                try (android.content.res.AssetFileDescriptor fd = a.getAssets().openFd(
                        (s.voice.contains("/") ? s.voice : "tour/ru/" + s.voice) + ".mp3")) {
                    android.media.MediaPlayer m = new android.media.MediaPlayer();
                    m.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                    m.prepare();
                    m.start();
                    mp[0] = m;
                    played = true;
                } catch (Exception ignored) {
                }
            }
            if (!played && ttsOk[0]) tts[0].speak(s.text, TextToSpeech.QUEUE_FLUSH, null, "tour" + i[0]);
        };
        next.setOnClickListener(v -> {
            if (i[0] >= steps.length - 1) {
                end.run();
                if (prog != null) prog.done();
                return;
            }
            i[0]++;
            show[0].run();
        });
        back.setOnClickListener(v -> {
            if (i[0] > 0) {
                i[0]--;
                show[0].run();
            }
        });
        skip.setOnClickListener(v -> {
            end.run();
            if (prog != null) {
                prog.postponed(i[0]);
                Toast.makeText(a, Ui.t(R.string.tour_later_hint), Toast.LENGTH_LONG).show();
            }
        });
        // первый шаг — когда разметка готова и голос успел подняться
        layer.postDelayed(show[0], 900);
    }

    /** Затемнение с прозрачным «окном» и янтарной рамкой вокруг подсвеченной кнопки. */
    private static final class Dim extends View {
        RectF hole;
        private final Paint shade = new Paint(), clear = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dim(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            shade.setColor(0xB3000000);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 3));
            ring.setColor(Ui.YELLOW);
        }

        @Override
        protected void onDraw(Canvas cv) {
            cv.drawRect(0, 0, getWidth(), getHeight(), shade);
            if (hole != null) {
                float r = Ui.dp(getContext(), 12);
                cv.drawRoundRect(hole, r, r, clear);
                cv.drawRoundRect(hole, r, r, ring);
            }
        }
    }
}
