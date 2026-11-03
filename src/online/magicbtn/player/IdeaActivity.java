package online.magicbtn.player;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Random;

/**
 * «💡 Идея»: пользователь пишет (или генерирует кнопкой 🎲) идею для приложения и отправляет заявку.
 * Заявки хранятся на сервере (video_api.php, action=idea) и разбираются в админке (action=admin);
 * если подключён Telegram, ответы приходят в личку от поддержки (reply_to в ответе сервера).
 */
public class IdeaActivity extends Activity {

    private EditText text, name, contact;
    private TextView send;
    private final Random rnd = new Random();
    private int last = -1;
    private String replyTo = "", replyName = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, Ui.dp(this, 12), p, p);
        sv.addView(col);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.button(this, "‹", Ui.WHITE);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(Ui.t(R.string.idea_title));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        col.addView(header);

        TextView sub = new TextView(this);
        sub.setText(Ui.t(R.string.idea_sub));
        sub.setTextColor(Ui.GRAY);
        sub.setTextSize(14);
        sub.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        col.addView(sub);

        TextView gen = Ui.button(this, "🎲 " + Ui.t(R.string.idea_generate), Ui.YELLOW);
        gen.setGravity(Gravity.CENTER);
        gen.setOnClickListener(v -> generate());
        col.addView(gen, new LinearLayout.LayoutParams(-1, -2));

        text = new EditText(this);
        text.setHint(Ui.t(R.string.idea_hint));
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setMinLines(4);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setTextColor(Ui.BLACK);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Ui.dp(this, 12);
        col.addView(text, tl);

        name = new EditText(this);
        name.setHint(Ui.t(R.string.your_name));
        name.setSingleLine(true);
        name.setText(Social.name(this));
        name.setTextColor(Ui.BLACK);
        col.addView(name);

        contact = new EditText(this);
        contact.setHint(Ui.t(R.string.idea_contact));
        contact.setSingleLine(true);
        contact.setText(getSharedPreferences("social", 0).getString("contact", ""));
        contact.setTextColor(Ui.BLACK);
        col.addView(contact);

        send = Ui.button(this, "➤ " + Ui.t(R.string.idea_send), Ui.YELLOW);
        send.setGravity(Gravity.CENTER);
        send.setOnClickListener(v -> Rules.ensure(this, this::submit));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Ui.dp(this, 16);
        col.addView(send, sl);

        int sent = getSharedPreferences("social", 0).getInt("ideas_sent", 0);
        if (sent > 0) {
            TextView s = new TextView(this);
            s.setText(Ui.t(R.string.idea_sent_count, sent));
            s.setTextColor(Ui.GRAY);
            s.setTextSize(13);
            s.setPadding(0, Ui.dp(this, 12), 0, 0);
            col.addView(s);
        }

        setContentView(sv);
        Ui.fitSystemBars(this);
    }

    /** Случайная идея: «что сделать» + «зачем» — без повторов подряд; дальше пользователь её правит. */
    private void generate() {
        String[] what = Ui.t(R.string.idea_what).split("\\|");
        String[] why = Ui.t(R.string.idea_why).split("\\|");
        int i;
        do i = rnd.nextInt(what.length); while (what.length > 1 && i == last);
        last = i;
        text.setText(what[i] + " — " + why[rnd.nextInt(why.length)]);
        text.setSelection(text.length());
    }

    private void submit() {
        final String t = text.getText().toString().trim();
        if (t.length() < 5) {
            Toast.makeText(this, Ui.t(R.string.idea_too_short), Toast.LENGTH_SHORT).show();
            return;
        }
        final String n = name.getText().toString().trim(), c = contact.getText().toString().trim();
        if (!n.isEmpty()) Social.setName(this, n);
        getSharedPreferences("social", 0).edit().putString("contact", c).apply();
        send.setEnabled(false);
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                JSONObject o = Social.call(this, ChatActivity.CHAT_API, "", "action=idea", "POST", new JSONObject()
                        .put("text", t).put("name", n).put("contact", c).put("lang", Look.lang(this))
                        .put("app", Updater.currentName(this)).toString());
                ok = o.optBoolean("ok");
                replyTo = o.optString("reply_to");
                replyName = o.optString("reply_name");
                msg = ok ? Ui.t(replyTo.isEmpty() ? R.string.idea_thanks : R.string.idea_thanks_dm, o.optInt("id"))
                        : "too many".equals(o.optString("error")) ? Ui.t(R.string.idea_too_many) : o.optString("error");
            } catch (Exception e) {
                msg = Ui.t(R.string.chat_offline);
            }
            final String m = msg;
            final boolean done = ok;
            runOnUiThread(() -> {
                send.setEnabled(true);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                if (done) {
                    android.content.SharedPreferences sp = getSharedPreferences("social", 0);
                    sp.edit().putInt("ideas_sent", sp.getInt("ideas_sent", 0) + 1).apply();
                    // ответы приходят в личку от поддержки — сразу открываем эту переписку
                    if (replyTo.matches("[a-f0-9]{16}")) startActivity(ChatActivity.dm(this, replyTo, replyName));
                    finish();
                }
            });
        }).start();
    }
}
