package online.magicbtn.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * 3.10: подробная помощь — все экраны и весь функционал по разделам (первый — чат), тексты в assets/help/<язык>.json.
 * Раздел раскрывается касанием, Алиса (маленькая, поверх всего) читает вступление: русский — записанный голос
 * assets/help/ru/<id>.mp3 (Silero), остальные языки — голос телефона. Кнопки «Показать на экране» — живые подсказки.
 */
public class HelpActivity extends Activity {

    private WebView mascot;
    private android.media.MediaPlayer mp;
    private TextToSpeech tts;
    private boolean ttsOk;
    private String lang;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lang = Look.lang(this);
        if (lang.isEmpty()) {
            Locale l = getResources().getConfiguration().getLocales().get(0);
            lang = "sr".equals(l.getLanguage()) ? ("Latn".equals(l.getScript()) ? "sr-Latn" : "sr") : l.getLanguage();
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 140));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.button(this, "‹", Ui.WHITE);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(Ui.t(R.string.help_title));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        col.addView(header);

        TextView sub = new TextView(this);
        sub.setText(Ui.t(R.string.help_sub));
        sub.setTextColor(Ui.GRAY);
        sub.setTextSize(14);
        sub.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        col.addView(sub);

        // живые подсказки прямо на экранах
        LinearLayout live = new LinearLayout(this);
        TextView liveChat = Ui.button(this, "💬 " + Ui.t(R.string.help_live_chat), Ui.YELLOW);
        liveChat.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class).putExtra("help", true)));
        TextView liveHome = Ui.button(this, "🎓 " + Ui.t(R.string.guide_again), Ui.WHITE);
        liveHome.setOnClickListener(v -> {   // 3.12: пройти подробное обучение с Алисой с начала
            Guide.restart(this);
            startActivity(new Intent(this, HomeActivity.class).putExtra("guide", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -2, 1);
        ll.rightMargin = Ui.dp(this, 8);
        live.addView(liveChat, ll);
        live.addView(liveHome, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(live);

        JSONArray chapters = load();
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject c = chapters.optJSONObject(i);
            if (c != null) col.addView(chapter(c, i == 0));
        }

        // Алиса — маленькая, в правом нижнем углу, поверх всего экрана
        mascot = new WebView(this);
        mascot.setBackgroundColor(Color.TRANSPARENT);
        mascot.getSettings().setJavaScriptEnabled(true);
        mascot.getSettings().setAllowFileAccess(false);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        mascot.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;
            }
        });
        mascot.loadUrl("https://appassets.androidplatform.net/assets/tour/index.html");
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 112), Gravity.BOTTOM | Gravity.END);
        mp.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 8));
        root.addView(mascot, mp);
        mascot.setOnClickListener(v -> mascot.evaluateJavascript("window.tour&&tour.random()", null));

        setContentView(root);
        tts = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(Locale.forLanguageTag(lang));
            ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
        });
    }

    private JSONArray load() {
        String[] tryLangs = {lang, lang.startsWith("sr") ? "sr-Latn" : lang, "en"};
        for (String l : tryLangs) {
            try (InputStream in = getAssets().open("help/" + l + ".json")) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] t = new byte[8192];
                int n;
                while ((n = in.read(t)) > 0) b.write(t, 0, n);
                return new JSONArray(b.toString("UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return new JSONArray();
    }

    private View chapter(final JSONObject c, boolean open) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.box(this, Ui.WHITE, 2));
        int p = Ui.dp(this, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = Ui.dp(this, 10);
        card.setLayoutParams(cl);

        final TextView head = new TextView(this);
        head.setTextColor(Ui.BLACK);
        head.setTextSize(18);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(head);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView lead = new TextView(this);
        lead.setText(c.optString("lead"));
        lead.setTextColor(Ui.BLACK);
        lead.setTextSize(16);
        lead.setLineSpacing(0, 1.2f);
        body.addView(lead);
        JSONArray items = c.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            TextView t = new TextView(this);
            t.setText((i + 1) + ". " + items.optString(i));
            t.setTextColor(Ui.BLACK);
            t.setTextSize(15);
            t.setLineSpacing(0, 1.25f);
            t.setPadding(0, Ui.dp(this, 8), 0, 0);
            body.addView(t);
        }
        TextView listen = Ui.button(this, "🔊 " + Ui.t(R.string.help_listen), Ui.WHITE);
        listen.setOnClickListener(v -> say(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 10);
        body.addView(listen, lp);
        card.addView(body);

        final String t = c.optString("icon") + "  " + c.optString("title");
        Runnable sync = () -> head.setText((body.getVisibility() == View.VISIBLE ? "▾ " : "▸ ") + t);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        sync.run();
        card.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            sync.run();
            if (show) say(c);
        });
        return card;
    }

    /** Алиса читает вступление раздела. */
    private void say(JSONObject c) {
        stopVoice();
        if (mascot != null) mascot.evaluateJavascript("window.tour&&tour.random()", null);
        if ("ru".equals(lang)) {
            try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("help/ru/" + c.optString("id") + ".mp3")) {
                mp = new android.media.MediaPlayer();
                mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                mp.prepare();
                mp.start();
                return;
            } catch (Exception ignored) {
            }
        }
        if (ttsOk) tts.speak(c.optString("lead"), TextToSpeech.QUEUE_FLUSH, null, c.optString("id"));
    }

    private void stopVoice() {
        if (mp != null) {
            mp.release();
            mp = null;
        }
        if (ttsOk) tts.stop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopVoice();
    }

    @Override
    protected void onDestroy() {
        stopVoice();
        if (tts != null) tts.shutdown();
        if (mascot != null) mascot.destroy();
        super.onDestroy();
    }
}
