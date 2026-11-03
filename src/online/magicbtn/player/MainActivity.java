package online.magicbtn.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Сайт как есть в WebView; всё, что показывается в архиве, оседает в кэше. */
public class MainActivity extends Activity implements MediaCache.Listener, Sources.OnChange {

    /**
     * Следит за слайдшоу/просмотрщиком сайта: какое фото или видео показано —
     * то и отдаём приложению на скачивание (HNApp.seen).
     */
    private static final String WATCH_JS = "(function(){"
            + "if(window.__hnWatch)return;window.__hnWatch=1;"
            + "var VIEW='#ss-wrap,#ss-overlay,#lightbox,.lightbox,[id^=lb-],[class*=slide],[id*=slide]';"
            + "function rep(el,force){try{var s=el.currentSrc||el.src;if(!s||s.indexOf('http')!==0)return;"
            + "if(force||(el.closest&&el.closest(VIEW)))HNApp.seen(s.split('#')[0],location.href);}catch(e){}}"
            + "new MutationObserver(function(ms){ms.forEach(function(m){"
            + "if(m.type==='attributes'){if(m.target.matches&&m.target.matches('video,img,source'))rep(m.target.tagName==='SOURCE'?m.target.parentNode:m.target);return;}"
            + "m.addedNodes.forEach(function(n){if(n.nodeType!==1)return;"
            + "if(n.matches('video,img'))rep(n);if(n.querySelectorAll)n.querySelectorAll('video,img').forEach(function(x){rep(x)});});"
            + "});}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});"
            + "document.addEventListener('play',function(e){rep(e.target,true)},true);"
            + "})();";

    /** Мост для JS страницы. */
    public class Bridge {
        @JavascriptInterface
        public void seen(String url, String page) {
            cache.seen(url, page);
        }
    }

    private WebView web;
    private TextView badge;
    private MediaCache cache;
    private volatile String pageUrl = "";
    private Sources.Source source;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cache = MediaCache.get(this);
        source = Sources.get(this, getIntent().getIntExtra("source", -1));
        if (source == null) source = Sources.current(this);
        if (source == null) {
            finish();
            return;
        }
        MediaCache.sourceHost = source.host();

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        badge = Ui.button(this, "", Ui.YELLOW);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, Ui.dp(this, 16), Ui.dp(this, 20));
        root.addView(badge, lp);
        badge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, GalleryActivity.class));
            }
        });

        // назад на главный экран плеера
        TextView menu = Ui.button(this, "⌂", Ui.WHITE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        mp.setMargins(Ui.dp(this, 16), 0, 0, Ui.dp(this, 20));
        root.addView(menu, mp);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        setContentView(root);
        Ui.fitSystemBars(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        cache.userAgent = s.getUserAgentString();
        web.addJavascriptInterface(new Bridge(), "HNApp");

        // сайт ставит Service Worker на /media/ — его запросы идут мимо WebViewClient,
        // поэтому отдаём скачанное и через ServiceWorkerClient
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest req) {
                return cache.intercept(req, pageUrl);
            }
        });

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return cache.intercept(req, pageUrl);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (MediaCache.labelFor(url) != null) view.evaluateJavascript(WATCH_JS, null);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                pageUrl = url;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String host = req.getUrl().getHost();
                if (host != null && host.equals(source.host())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                // главная страница не открылась (пропала сеть) — уходим в офлайн
                if (req.isForMainFrame() && !cache.isOnline()) {
                    startActivity(new Intent(MainActivity.this, GalleryActivity.class));
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                if (customCallback != null) customCallback.onCustomViewHidden();
                customCallback = null;
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl(source.loadUrl());
    }

    @Override
    public void onSourceChanged(Sources.Source s) {
        if (s == null) {
            finish();
            return;
        }
        source = s;
        MediaCache.sourceHost = s.host();
        web.clearHistory();
        web.loadUrl(s.loadUrl());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cache != null && badge != null) {
            cache.setListener(this);
            MediaDb db = MediaDb.get(this);
            onCached(db.count(), db.pendingCount());
            cache.kick(); // докачать то, что не успело
            Updater.onResume(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cache != null) cache.setListener(null);
        CookieManager.getInstance().flush();
    }

    @Override
    public void onCached(int total, int queued) {
        badge.setText(Ui.t(R.string.offline_badge, total) + (queued > 0 ? "  ⬇" + queued : ""));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            if (customCallback != null) customCallback.onCustomViewHidden();
            root.removeView(customView);
            customView = null;
            return;
        }
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
