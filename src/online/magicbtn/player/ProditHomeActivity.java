package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** Production IT: главный экран — Чаты, Встречи, «Написать по email», Профиль, Выйти. */
public class ProditHomeActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView chatsSub;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Prodit.loggedIn(this)) {
            startActivity(new Intent(this, ProditLoginActivity.class));
            finish();
            return;
        }
        int p = Ui.dp(this, 18);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(p, Ui.dp(this, 20), p, p);

        TextView logo = new TextView(this);
        logo.setText("Production IT");
        logo.setTextSize(24);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(Ui.BLACK);
        col.addView(logo);
        TextView me = new TextView(this);
        me.setText(Social.name(this) + " · " + Prodit.email(this));
        me.setTextSize(13);
        me.setTextColor(Ui.GRAY);
        me.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 18));
        col.addView(me);

        chatsSub = card(col, "💬", Prodit.t("chats"), Prodit.t("chats_sub"), true,
                () -> startActivity(DmListActivity.open(this)));
        card(col, "📅", Prodit.t("meetings"), Prodit.t("meetings_sub"), true,
                () -> startActivity(new Intent(this, ProditMeetingsActivity.class)));
        card(col, "✉", Prodit.t("new_chat"), Prodit.t("new_chat_sub"), false, this::newChat);
        card(col, "👤", Prodit.t("profile"), Prodit.email(this), false,
                () -> startActivity(new Intent(this, ProfileActivity.class)));

        TextView out = new TextView(this);
        out.setText(Prodit.t("sign_out"));
        out.setTextColor(Ui.GRAY);
        out.setTextSize(14);
        out.setGravity(Gravity.CENTER);
        out.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 8));
        out.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(Prodit.t("sign_out_q", Prodit.email(this)))
                .setPositiveButton(Prodit.t("sign_out"), (d, w) -> {
                    Prodit.signOut(this);
                    startActivity(new Intent(this, ProditLoginActivity.class));
                    finishAffinity();
                })
                .setNegativeButton(Prodit.t("cancel"), null).show());
        col.addView(out);
        TextView ver = new TextView(this);
        ver.setText("v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Ui.GRAY);
        ver.setTextSize(11);
        ver.setGravity(Gravity.CENTER);
        col.addView(ver);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col);
        setContentView(sv);
        Ui.fitSystemBars(this);
        new Thread(() -> Prodit.refreshConfig(this)).start();
        Prodit.startRealtime(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notify.askOnce(this);
        if (chatsSub == null) return;
        new Thread(() -> DmListActivity.refreshUnread(this, n -> main.post(() ->
                chatsSub.setText(n > 0 ? "• " + n + " · " + Prodit.t("chats_sub") : Prodit.t("chats_sub"))))).start();
    }

    /** Карточка-кнопка; big — крупная (Чаты, Встречи). Возвращает подпись (для счётчика непрочитанных). */
    private TextView card(LinearLayout col, String icon, String title, String sub, boolean big, Runnable go) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.box(this, Ui.WHITE, 0));
        int p = Ui.dp(this, big ? 20 : 14);
        row.setPadding(p, p, p, p);
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(big ? 28 : 20);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.box(this, big ? Ui.YELLOW : Ui.BG, 0));
        int s = Ui.dp(this, big ? 56 : 42);
        row.addView(ic, new LinearLayout.LayoutParams(s, s));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(big ? 20 : 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.BLACK);
        txt.addView(t);
        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(13);
        st.setTextColor(Ui.GRAY);
        txt.addView(st);
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(24);
        arrow.setTextColor(Ui.GRAY);
        row.addView(arrow);
        row.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 12);
        col.addView(row, lp);
        return st;
    }

    /** Новый диалог: email → найден — открыть чат; нет — предложить приглашение. */
    private void newChat() {
        final EditText e = new EditText(this);
        e.setHint(Prodit.t("email"));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        e.setSingleLine(true);
        int p = Ui.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(Prodit.t("new_chat")).setView(box)
                .setPositiveButton(Prodit.t("find"), (d, w) -> find(e.getText().toString().trim().toLowerCase(java.util.Locale.ROOT)))
                .setNegativeButton(Prodit.t("cancel"), null).show();
    }

    private void find(final String email) {
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[a-z]{2,}")) {
            Toast.makeText(this, Prodit.t("bad_email"), Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                JSONObject o = Prodit.api(this, "action=prodit_find&email=" + android.net.Uri.encode(email), "GET", null);
                main.post(() -> startActivity(ChatActivity.dm(this, o.optString("author"), o.optString("name"))));
            } catch (Exception ex) {
                main.post(() -> {
                    if ("not registered".equals(ex.getMessage())) invite(email);
                    else Toast.makeText(this, Prodit.error(ex), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void invite(final String email) {
        new AlertDialog.Builder(this).setMessage(Prodit.t("not_registered", email))
                .setPositiveButton(Prodit.t("invite"), (d, w) -> new Thread(() -> {
                    String msg;
                    try {
                        Prodit.api(this, "action=prodit_invite", "POST", new JSONObject().put("email", email).put("lang", Prodit.lang()));
                        msg = Prodit.t("invited");
                    } catch (Exception ex) {
                        msg = Prodit.error(ex);
                    }
                    final String m = msg;
                    main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
                }).start())
                .setNegativeButton(Prodit.t("cancel"), null).show();
    }
}
