package online.magicbtn.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * Тема «Аврора»: живой 3D-фон (three.js, assets/glass/index.html) под полупрозрачными карточками.
 * WebView вставляется самым нижним слоем экрана, касания не перехватывает у интерфейса;
 * на паузе экрана сцена останавливается (батарея).
 */
final class GlassBg {

    private static final String TAG = "glass3d";

    private GlassBg() {
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void attach(Activity a) {
        View c = a.findViewById(android.R.id.content);
        if (!(c instanceof FrameLayout)) return;
        FrameLayout content = (FrameLayout) c;
        WebView w = find(a);
        if (w != null) {
            w.onResume();
            w.evaluateJavascript("window.pauseScene&&pauseScene(false)", null);
            return;
        }
        w = new WebView(a);
        w.setTag(TAG);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        w.setBackgroundColor(0xFF120E33);
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setOnTouchListener((v, e) -> true); // фон: касания ему не нужны
        w.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        w.loadUrl("file:///android_asset/glass/index.html");
        content.addView(w, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    static void pause(Activity a) {
        WebView w = find(a);
        if (w != null) {
            w.evaluateJavascript("window.pauseScene&&pauseScene(true)", null);
            w.onPause();
        }
    }

    static void destroy(Activity a) {
        WebView w = find(a);
        if (w != null) {
            ((ViewGroup) w.getParent()).removeView(w);
            w.destroy();
        }
    }

    private static WebView find(Activity a) {
        View c = a.findViewById(android.R.id.content);
        if (!(c instanceof FrameLayout)) return null;
        View v = ((FrameLayout) c).getChildCount() > 0 ? ((FrameLayout) c).getChildAt(0) : null;
        return v instanceof WebView && TAG.equals(v.getTag()) ? (WebView) v : null;
    }
}
