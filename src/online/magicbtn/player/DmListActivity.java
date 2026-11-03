package online.magicbtn.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.IntConsumer;

/**
 * Личные сообщения: список диалогов (последнее сообщение, непрочитанные). Новый диалог — тап по автору
 * в общем чате или ссылка «написать мне», которую можно отправить другу в любой мессенджер.
 */
public class DmListActivity extends Activity {

    private static final long POLL_MS = 6000;
    private static volatile int unread;
    private static volatile long unreadAt;

    private LinearLayout list;
    private TextView status;
    private boolean polling;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::load;

    /** Сколько непрочитанных (не чаще раза в 30 с; без сети — последнее известное). Не в UI-потоке. */
    static void refreshUnread(Context c, IntConsumer done) {
        if (System.currentTimeMillis() - unreadAt > 30000) {
            try {
                JSONObject o = Social.call(c, ChatActivity.CHAT_API, "", "action=dm_threads", "GET", null);
                if (o.optBoolean("ok")) unread = o.optInt("unread");
                unreadAt = System.currentTimeMillis();
            } catch (Exception ignored) {
            }
        }
        done.accept(unread);
    }

    static int unreadCached() {
        return unread;
    }

    /** Свежее число непрочитанных от фоновой проверки (Notify) — для ☰• без лишнего запроса. */
    static void setUnread(int n) {
        boolean ch = n != unread;
        unread = n;
        unreadAt = System.currentTimeMillis();
        if (ch) Inbox.changed();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 8));
        TextView back = Ui.button(this, "‹", Ui.WHITE);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(Ui.t(R.string.dm_title));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(header);

        TextView myLink = Ui.button(this, "🔗 " + Ui.t(R.string.dm_my_link), Ui.YELLOW);
        myLink.setTextSize(14);
        myLink.setOnClickListener(v -> ShareLink.text(this, Ui.t(R.string.dm_my_link_text) + "\n" + ShareLink.dmLink(this)));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.leftMargin = p;
        ml.rightMargin = p;
        if (!Flags.PRODIT) col.addView(myLink, ml);   // ссылка «написать мне» ведёт на сайт плеера

        status = new TextView(this);
        status.setTextColor(Ui.GRAY);
        status.setTextSize(13);
        status.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 4));
        status.setText(Ui.t(R.string.loading));
        col.addView(status);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(p, 0, p, p);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(col);
        Ui.fitSystemBars(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notify.askOnce(this);
        polling = true;
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        main.removeCallbacks(poll);
    }

    private void load() {
        main.removeCallbacks(poll);
        new Thread(() -> {
            try {
                JSONObject o = Social.call(this, ChatActivity.CHAT_API, "", "action=dm_threads", "GET", null);
                final JSONArray a = o.optJSONArray("items");
                unread = o.optInt("unread");
                unreadAt = System.currentTimeMillis();
                main.post(() -> show(a));
            } catch (Exception e) {
                main.post(() -> status.setText(Ui.t(R.string.chat_offline)));
            }
            if (polling) main.postDelayed(poll, POLL_MS);
        }).start();
    }

    private void show(JSONArray a) {
        list.removeAllViews();
        int shown = 0;
        for (int i = 0; a != null && i < a.length(); i++) {
            final JSONObject t = a.optJSONObject(i);
            if (t == null || Block.blocked(this, t.optString("peer"))) continue;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.box(this, t.optInt("unread") > 0 ? Ui.YELLOW : Ui.WHITE, 2));
            int p = Ui.dp(this, 10);
            row.setPadding(p, p, p + Ui.dp(this, 2), p + Ui.dp(this, 2));
            ImageView ava = new ImageView(this);
            ava.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ava.setBackground(Ui.box(this, Ui.YELLOW, 0));
            ava.setClipToOutline(true);
            row.addView(ava, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            Profile.loadAvatar(this, t.optString("avatar"), ava);
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(Ui.dp(this, 10), 0, 0, 0);
            TextView nm = new TextView(this);
            String ts = t.optString("ts");
            nm.setText(t.optString("name") + (t.optInt("unread") > 0 ? "  •" + t.optInt("unread") : "")
                    + (ts.length() >= 16 ? "   " + ts.substring(5, 10).replace('-', '.') + " " + ts.substring(11, 16) : ""));
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextSize(15);
            nm.setTextColor(t.optInt("unread") > 0 ? Ui.ON_ACCENT : Ui.BLACK);
            nm.setSingleLine(true);
            txt.addView(nm);
            TextView last = new TextView(this);
            last.setText((t.optBoolean("mine") ? Ui.t(R.string.you) + ": " : "") + t.optString("text"));
            last.setTextSize(13);
            last.setTextColor(t.optInt("unread") > 0 ? Ui.ON_ACCENT : Ui.GRAY);
            last.setMaxLines(2);
            txt.addView(last);
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> startActivity(ChatActivity.dm(this, t.optString("peer"), t.optString("name"))));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 8);
            list.addView(row, lp);
        }
        status.setText(shown == 0 ? Ui.t(R.string.dm_empty) : Ui.t(R.string.dm_rules));
    }

    static Intent open(Context c) {
        return new Intent(c, DmListActivity.class);
    }
}
