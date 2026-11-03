package online.magicbtn.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 3.6: главный экран при входе — Чат (самая большая кнопка, с непрочитанными), Видеоплеер, Игра (сразу в уровень)
 * и «Получить Pro». Значок приложения по-прежнему ведёт на VideoListActivity — она перенаправляет сюда.
 */
public class HomeActivity extends Activity {

    private TextView chatCount, dmBtn, proBtn, menuBtn, helpBtn;
    private LinearLayout chatCard, playerTile, gameTile, chatStats, playerStats, gameStats;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);
        col.setPadding(p, Ui.dp(this, 12), p, p);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(Ui.t(R.string.app_name));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        helpBtn = Ui.button(this, Ui.t(R.string.help_btn), Ui.WHITE);   // «Помощь» — обучение с ведущей снова
        helpBtn.setOnClickListener(v -> Guide.open(this));   // 3.12: продолжить обучение или справочник
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.rightMargin = Ui.dp(this, 8);
        header.addView(helpBtn, hl);
        TextView menu = menuBtn = Ui.button(this, "☰", Ui.WHITE);
        menu.setOnClickListener(v -> Nav.open(this));
        header.addView(menu);
        col.addView(header);

        // Чат — самая большая
        LinearLayout chat = chatCard = card();
        chat.setGravity(Gravity.CENTER);
        TextView ci = big("💬", 56);
        chat.addView(ci);
        TextView ct = big(Ui.t(R.string.home2_chat), 28);
        chat.addView(ct);
        chatCount = new TextView(this);
        chatCount.setTextSize(16);
        chatCount.setTextColor(Ui.GRAY);
        chatCount.setGravity(Gravity.CENTER);
        chatCount.setPadding(0, Ui.dp(this, 6), 0, 0);
        chat.addView(chatCount);
        dmBtn = Ui.button(this, "", Ui.WHITE);
        dmBtn.setOnClickListener(v -> startActivity(new Intent(this, DmListActivity.class)));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = Ui.dp(this, 16);
        chat.addView(dmBtn, dl);
        chat.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)));
        chatStats = statsBlock(chat, Stats.CHAT, 90);
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(-1, 0, 5);
        chl.topMargin = Ui.dp(this, 16);
        col.addView(chat, chl);

        // Видеоплеер и Игра
        LinearLayout row = new LinearLayout(this);
        LinearLayout player = tile("▶", Ui.t(R.string.home2_player), v -> startActivity(new Intent(this, VideoListActivity.class)));
        LinearLayout game = tile("🎲", Ui.t(R.string.home2_game),
                v -> startActivity(new Intent(this, GameActivity.class).putExtra("continue", true)));
        playerTile = player;
        gameTile = game;
        playerStats = statsBlock(player, Stats.PLAYER, 70);
        gameStats = statsBlock(game, Stats.GAME, 70);
        row.addView(player, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, -1, 1);
        gl.leftMargin = Ui.dp(this, 12);
        row.addView(game, gl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, 0, 3);
        rl.topMargin = Ui.dp(this, 12);
        col.addView(row, rl);

        // Получить Pro
        proBtn = Ui.button(this, "", Ui.YELLOW);
        proBtn.setTextSize(17);
        if (!Flags.MONETIZE) proBtn.setVisibility(View.GONE);   // монетизация выключена — Pro у всех
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
        pl.topMargin = Ui.dp(this, 16);
        col.addView(proBtn, pl);

        setContentView(col);
        Ui.fitSystemBars(this);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.button(this, Ui.WHITE));
        c.setClickable(true);
        int p = Ui.dp(this, 16);
        c.setPadding(p, p, p, p);
        return c;
    }

    private TextView big(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Ui.BLACK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout tile(String icon, String name, View.OnClickListener click) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.addView(big(icon, 40));
        TextView n = big(name, 19);
        n.setPadding(0, Ui.dp(this, 8), 0, 0);
        c.addView(n);
        c.setOnClickListener(click);
        return c;
    }

    // ---------- 3.8: статистика на кнопках («Собирать статистику» → «Обновить» → мини-дашборд Chart.js) ----------

    private LinearLayout statsBlock(LinearLayout tile, final String what, final int heightDp) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = Ui.dp(this, 10);
        tile.addView(box, bl);
        box.setTag(new int[]{heightDp});
        fillStats(box, what);
        return box;
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void fillStats(final LinearLayout box, final String what) {
        box.removeAllViews();
        if (!Stats.on(this, what)) {
            TextView start = Ui.button(this, Ui.t(R.string.stats_start), Ui.WHITE);
            start.setTextSize(13);
            start.setOnClickListener(v -> {
                Stats.setOn(this, what, true);
                fillStats(box, what);
            });
            box.addView(start);
            return;
        }
        final String[] labels = new String[7];
        final int[] vals = Stats.week(this, what, labels);
        int h = ((int[]) box.getTag())[0];
        if (Stats.total(vals) > 0) {
            final android.webkit.WebView w = new android.webkit.WebView(this);
            w.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            w.getSettings().setJavaScriptEnabled(true);
            w.setVerticalScrollBarEnabled(false);
            final androidx.webkit.WebViewAssetLoader loader = new androidx.webkit.WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", new androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this)).build();
            w.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView v, android.webkit.WebResourceRequest r) {
                    return loader.shouldInterceptRequest(r.getUrl());
                }

                @Override
                public void onPageFinished(android.webkit.WebView v, String url) {
                    StringBuilder l = new StringBuilder("["), d = new StringBuilder("[");
                    for (int i = 0; i < 7; i++) {
                        l.append(i > 0 ? "," : "").append(org.json.JSONObject.quote(labels[i]));
                        d.append(i > 0 ? "," : "").append(vals[i]);
                    }
                    v.evaluateJavascript("draw(" + l + "]," + d + "],'" + hex(Ui.YELLOW) + "','" + hex(Ui.GRAY) + "','" + hex(Ui.LINE) + "')", null);
                }
            });
            w.loadUrl("https://appassets.androidplatform.net/assets/stats/chart.html");
            box.addView(w, new LinearLayout.LayoutParams(-1, Ui.dp(this, h)));
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView sum = new TextView(this);
        sum.setTextSize(12);
        sum.setTextColor(Ui.GRAY);
        int total = Stats.total(vals);
        sum.setText(total > 0 ? Ui.t(what.equals(Stats.GAME) ? R.string.stats_total_min : what.equals(Stats.CHAT)
                ? R.string.stats_total_msg : R.string.stats_total_views, total) : Ui.t(R.string.stats_collecting));
        row.addView(sum, new LinearLayout.LayoutParams(0, -2, 1));
        TextView upd = Ui.button(this, Ui.t(R.string.stats_refresh), Ui.WHITE);
        upd.setTextSize(13);
        upd.setOnClickListener(v -> fillStats(box, what));
        upd.setOnLongClickListener(v -> {   // долгое нажатие — выключить сбор
            Stats.setOn(this, what, false);
            fillStats(box, what);
            return true;
        });
        row.addView(upd);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = Ui.dp(this, 4);
        box.addView(row, rl);
    }

    private static String hex(int c) {
        return String.format(java.util.Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    private void refresh() {
        int chat = Inbox.chatUnread, dm = Math.max(0, DmListActivity.unreadCached());
        chatCount.setText(chat > 0 ? Ui.t(R.string.home2_chat_new, chat) : Ui.t(R.string.home2_chat_sub));
        chatCount.setTextColor(chat > 0 ? 0xFFFF5A5F : Ui.GRAY);
        dmBtn.setText("✉ " + Ui.t(R.string.home2_dm) + (dm > 0 ? "  • " + dm : ""));
        proBtn.setText(Pro.forever(this) ? "⭐ Pro ✓" : Pro.on(this)
                ? Ui.t(R.string.home2_pro_until, android.text.format.DateFormat.format("dd.MM", Pro.until(this)))
                : Ui.t(R.string.home2_pro));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        PlayUpdate.check(this);   // версия из Play — предложить обновиться
        // 3.12: подробное обучение с Алисой — сразу при первом запуске; «Отложить» → продолжится по «Помощи»
        boolean asked = getIntent().getBooleanExtra("guide", false);
        if (getIntent().getBooleanExtra("tour", false)) {   // «Обзор главного экрана» из справочника
            getIntent().removeExtra("tour");
            chatCard.post(this::tour);
        } else if (asked || Guide.auto(this)) {
            getIntent().removeExtra("guide");
            chatCard.postDelayed(this::guide, 400);
        }
        Inbox.listener = this::refresh;
        new Thread(() -> DmListActivity.refreshUnread(this, n -> runOnUiThread(this::refresh))).start();
    }

    private boolean guiding;

    /** Подробное обучение с того шага, где остановились. */
    void guide() {
        if (guiding || Tour.showing || isFinishing()) return;
        guiding = true;
        java.util.Map<String, android.view.View> t = new java.util.HashMap<>();
        t.put("chat", chatCard);
        t.put("dm", dmBtn);
        t.put("player", playerTile);
        t.put("view", playerTile);
        t.put("game", gameTile);
        t.put("stats", chatStats);
        t.put("menu", menuBtn);
        t.put("contacts", menuBtn);
        t.put("idea", menuBtn);
        t.put("help", helpBtn);
        Tour.markSeen(this);   // короткий тур этой версии больше не нужен
        Guide.start(this, t);
        chatCard.postDelayed(() -> guiding = false, 1500);
    }

    private void tour() {
        Tour.start(this, new Tour.Step[]{
                new Tour.Step(null, Ui.t(R.string.tour_hello), "tour_hello"),
                new Tour.Step(chatCard, Ui.t(R.string.tour_chat), "tour_chat"),
                new Tour.Step(dmBtn, Ui.t(R.string.tour_dm), "tour_dm"),
                new Tour.Step(playerTile, Ui.t(R.string.tour_player), "tour_player"),
                new Tour.Step(gameTile, Ui.t(R.string.tour_game), "tour_game"),
                new Tour.Step(menuBtn, Ui.t(R.string.tour_menu), "tour_menu"),
                new Tour.Step(helpBtn, Ui.t(R.string.tour_bye), "tour_bye"),
        });
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Inbox.listener != null) Inbox.listener = null;
    }
}
