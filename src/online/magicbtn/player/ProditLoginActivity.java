package online.magicbtn.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Production IT: первый экран — вход по рабочему email. Шаг 1: email + имя → письмо с кодом. Шаг 2: код → вход.
 * После входа — ProditHomeActivity; вошедшего сразу пускаем на главный экран.
 */
public class ProditLoginActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText email, name, code;
    private TextView info, action, back, resend;
    private LinearLayout step1, step2;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> Prodit.refreshConfig(this)).start();
        if (Prodit.loggedIn(this)) {
            home();
            return;
        }
        int p = Ui.dp(this, 22);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(p, Ui.dp(this, 56), p, p);

        TextView logo = new TextView(this);
        logo.setText("Production IT");
        logo.setTextSize(30);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(Ui.BLACK);
        logo.setGravity(Gravity.CENTER);
        col.addView(logo);
        TextView tag = new TextView(this);
        tag.setText(Prodit.t("tagline"));
        tag.setTextSize(15);
        tag.setTextColor(Ui.GRAY);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 36));
        col.addView(tag);

        step1 = column();
        email = field(Prodit.t("email"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        name = field(Prodit.t("name"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(Social.name(this));
        step1.addView(label(Prodit.t("email")));
        step1.addView(email, fieldLp());
        step1.addView(label(Prodit.t("name")));
        step1.addView(name, fieldLp());
        col.addView(step1, new LinearLayout.LayoutParams(-1, -2));

        step2 = column();
        step2.setVisibility(View.GONE);
        code = field("000000", InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(28);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.3f);
        step2.addView(label(Prodit.t("code")));
        step2.addView(code, fieldLp());
        col.addView(step2, new LinearLayout.LayoutParams(-1, -2));

        info = new TextView(this);
        info.setTextSize(14);
        info.setTextColor(Ui.GRAY);
        info.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        col.addView(info, new LinearLayout.LayoutParams(-1, -2));

        action = Ui.button(this, Prodit.t("get_code"), Ui.YELLOW);
        action.setOnClickListener(v -> next());
        col.addView(action, new LinearLayout.LayoutParams(-1, -2));

        resend = link(Prodit.t("resend"));
        resend.setOnClickListener(v -> sendCode());
        back = link(Prodit.t("other_email"));
        back.setOnClickListener(v -> showStep(1));
        col.addView(resend);
        col.addView(back);
        showStep(1);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col);
        setContentView(sv);
        Ui.fitSystemBars(this);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Ui.GRAY);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 6));
        return t;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(17);
        e.setTextColor(Ui.BLACK);
        e.setHintTextColor(Ui.GRAY);
        e.setBackground(Ui.box(this, Ui.WHITE, 0));
        int p = Ui.dp(this, 14);
        e.setPadding(p, p, p, p);
        return e;
    }

    private LinearLayout.LayoutParams fieldLp() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private TextView link(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(0xFFB39BFF);   // светло-фиолетовая ссылка на тёмном фоне
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 14), 0, 0);
        return t;
    }

    private void showStep(int s) {
        step1.setVisibility(s == 1 ? View.VISIBLE : View.GONE);
        step2.setVisibility(s == 2 ? View.VISIBLE : View.GONE);
        resend.setVisibility(s == 2 ? View.VISIBLE : View.GONE);
        back.setVisibility(s == 2 ? View.VISIBLE : View.GONE);
        action.setText(Prodit.t(s == 1 ? "get_code" : "sign_in"));
        if (s == 1) info.setText("");
        else code.requestFocus();
    }

    private void next() {
        if (step1.getVisibility() == View.VISIBLE) sendCode();
        else verify();
    }

    private String mail() {
        return email.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void sendCode() {
        if (busy) return;
        final String e = mail();
        if (!e.matches("[^@\\s]+@[^@\\s]+\\.[a-z]{2,}")) {
            info.setText(Prodit.t("bad_email"));
            return;
        }
        if (name.getText().toString().trim().isEmpty()) {
            info.setText(Prodit.t("need_name"));
            return;
        }
        busy(true);
        new Thread(() -> {
            try {
                Prodit.api(this, "action=prodit_auth_start", "POST", new JSONObject().put("email", e).put("lang", Prodit.lang()));
                main.post(() -> {
                    busy(false);
                    showStep(2);
                    info.setText(Prodit.t("code_sent", e));
                });
            } catch (Exception ex) {
                main.post(() -> {
                    busy(false);
                    info.setText(Prodit.error(ex));
                });
            }
        }).start();
    }

    private void verify() {
        if (busy) return;
        final String c = code.getText().toString().replaceAll("\\D", "");
        if (c.length() != 6) {
            info.setText(Prodit.t("wrong_code"));
            return;
        }
        busy(true);
        final String n = name.getText().toString().trim();
        new Thread(() -> {
            try {
                JSONObject o = Prodit.api(this, "action=prodit_auth_verify", "POST",
                        new JSONObject().put("email", mail()).put("code", c).put("name", n));
                if (o.optString("name").isEmpty()) o.put("name", n);
                Prodit.signedIn(this, o);
                Contacts.hello(this, true);   // имя — в каталог пользователей
                main.post(this::home);
            } catch (Exception ex) {
                main.post(() -> {
                    busy(false);
                    info.setText(Prodit.error(ex));
                });
            }
        }).start();
    }

    private void busy(boolean b) {
        busy = b;
        action.setAlpha(b ? 0.5f : 1f);
    }

    private void home() {
        startActivity(new Intent(this, ProditHomeActivity.class));
        finish();
    }
}
