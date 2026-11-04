package online.magicbtn.player;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Production IT: команда (компания по домену почты) — чат компании и каталог коллег; нажатие на коллегу — личный диалог.
 * У публичной почты (gmail и т.п.) компании нет — показываем подсказку.
 */
public class ProditTeamActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int p = Ui.dp(this, 16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 8));
        TextView back = Ui.button(this, "‹", Ui.WHITE);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(Prodit.t("team"));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(header);
        status = new TextView(this);
        status.setTextColor(Ui.GRAY);
        status.setTextSize(13);
        status.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 4));
        status.setText(Ui.t(R.string.loading));
        col.addView(status);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(p, 0, p, p);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(col);
        Ui.fitSystemBars(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            try {
                JSONObject o = Prodit.api(this, "action=prodit_company", "GET", null);
                main.post(() -> show(o));
            } catch (Exception e) {
                main.post(() -> status.setText(Prodit.error(e)));
            }
        }).start();
    }

    private void show(JSONObject o) {
        list.removeAllViews();
        final String domain = o.optString("domain");
        if (domain.isEmpty()) {
            status.setText(Prodit.t("no_company"));
            return;
        }
        Prodit.setDomain(this, domain);
        status.setText("@" + domain);
        TextView chat = Ui.button(this, "🏢 " + Prodit.t("company_chat"), Ui.YELLOW);
        chat.setOnClickListener(v -> startActivity(ChatActivity.room(this, "co:" + domain, "🏢 " + domain)));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.bottomMargin = Ui.dp(this, 12);
        list.addView(chat, cl);
        JSONArray m = o.optJSONArray("members");
        if (m == null || m.length() == 0) {
            TextView t = new TextView(this);
            t.setText(Prodit.t("no_colleagues", domain));
            t.setTextColor(Ui.GRAY);
            list.addView(t);
            return;
        }
        for (int i = 0; i < m.length(); i++) {
            final JSONObject u = m.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.box(this, Ui.WHITE, 0));
            int p = Ui.dp(this, 12);
            row.setPadding(p, p, p, p);
            TextView ava = new TextView(this);
            String n = u.optString("name");
            ava.setText(n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase());
            ava.setGravity(Gravity.CENTER);
            ava.setTextColor(Ui.ON_ACCENT);
            ava.setTypeface(Typeface.DEFAULT_BOLD);
            ava.setBackground(Ui.box(this, Ui.YELLOW, 0));
            row.addView(ava, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(Ui.dp(this, 12), 0, 0, 0);
            TextView nm = new TextView(this);
            nm.setText(n + (u.optBoolean("online") ? "  ●" : ""));
            nm.setTextColor(Ui.BLACK);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextSize(15);
            tx.addView(nm);
            TextView em = new TextView(this);
            em.setText(u.optString("email"));
            em.setTextColor(Ui.GRAY);
            em.setTextSize(13);
            tx.addView(em);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> startActivity(ChatActivity.dm(this, u.optString("author"), u.optString("name"))));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 8);
            list.addView(row, lp);
        }
    }
}
