package online.magicbtn.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/**
 * 3.4.1: игра «Хвосты» (башенная защита на three.js, исходник ~/infra/alisa, tools/game_bundle.py → assets/game)
 * прямо в приложении и без интернета. Файлы отдаёт WebViewAssetLoader по https://appassets.androidplatform.net/
 * (ES-модули и importmap работают как на сайте), прогресс — localStorage этого WebView.
 */
public class GameActivity extends Activity {

    private static final String HOME = "https://appassets.androidplatform.net/assets/game/index.html";
    /** С главного экрана — сразу в последний открытый уровень (continue.html читает прогресс игры). */
    private static final String CONTINUE = "https://appassets.androidplatform.net/assets/game/continue.html";
    private WebView web;
    private long playStart;   // 3.8: минуты в игре — для статистики кнопки «Игра»

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#8fd4e8"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // прогресс уровней
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"appassets.androidplatform.net".equals(u.getHost())) return true;   // наружу из игры не уходим
                if (u.getPath() != null && u.getPath().endsWith("/game/index.html")) {
                    finish();   // экран «Хвосты» (меню уровней игры) неудобен в приложении — 🏠 в игре возвращает в приложение
                    return true;
                }
                return false;
            }
        });
        setContentView(web);
        hideBars();
        if (state != null) web.restoreState(state);
        else web.loadUrl(CONTINUE);   // всегда сразу в уровень (последний открытый)
    }

    private void hideBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) hideBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (playStart > 0) Stats.add(this, Stats.GAME, (int) Math.round((System.currentTimeMillis() - playStart) / 60000.0));   // минуты
        playStart = 0;
        web.onPause();   // игра и звук на паузе, пока экран не виден
    }

    @Override
    protected void onResume() {
        super.onResume();
        playStart = System.currentTimeMillis();
        web.onResume();
    }

    @Override
    public void onBackPressed() {
        finish();   // «Назад» — сразу в приложение (внутри игры одна страница — уровень)
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
