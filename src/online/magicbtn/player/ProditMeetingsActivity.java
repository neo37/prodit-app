package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Production IT: встречи из календаря spacecode.business/meet (action=prodit_meetings).
 * «Войти» — комната LiveKit внутри приложения; «+» — новая встреча с рассылкой приглашений.
 */
public class ProditMeetingsActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status, filter;
    private JSONArray items;
    private boolean onlyMine = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        onlyMine = getSharedPreferences("prodit", 0).getBoolean("only_mine", true);
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
        title.setText(Prodit.t("meetings"));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        filter = Ui.button(this, "", Ui.WHITE);
        filter.setTextSize(13);
        filter.setOnClickListener(v -> {
            onlyMine = !onlyMine;
            getSharedPreferences("prodit", 0).edit().putBoolean("only_mine", onlyMine).apply();
            show();
        });
        header.addView(filter);
        TextView add = Ui.button(this, "+", Ui.YELLOW);
        add.setOnClickListener(v -> create());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.leftMargin = Ui.dp(this, 8);
        header.addView(add, al);
        col.addView(header);

        status = new TextView(this);
        status.setTextColor(Ui.GRAY);
        status.setTextSize(13);
        status.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 4));
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
        load();
    }

    private void load() {
        new Thread(() -> {
            try {
                JSONObject o = Prodit.api(this, "action=prodit_meetings", "GET", null);
                main.post(() -> {
                    items = o.optJSONArray("items");
                    show();
                });
            } catch (Exception e) {
                main.post(() -> status.setText(Prodit.error(e)));
            }
        }).start();
    }

    private static Date parse(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
            return f.parse(iso.replaceAll("\\.\\d+", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private void show() {
        filter.setText(Prodit.t(onlyMine ? "only_mine" : "all"));
        list.removeAllViews();
        if (items == null) return;
        long now = System.currentTimeMillis();
        String lastDay = "";
        int shown = 0;
        SimpleDateFormat day = new SimpleDateFormat("EEEE, d MMMM", new Locale(Prodit.lang()));
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        Calendar today = Calendar.getInstance();
        for (int i = 0; i < items.length(); i++) {
            final JSONObject m = items.optJSONObject(i);
            if (m == null || (onlyMine && !m.optBoolean("mine"))) continue;
            Date start = parse(m.optString("start"));
            if (start == null) continue;
            long end = start.getTime() + m.optInt("minutes", 60) * 60_000L;
            if (end < now) continue;   // прошедшие не показываем
            shown++;
            Calendar c = Calendar.getInstance();
            c.setTime(start);
            String d = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                    ? Prodit.t("today")
                    : c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) + 1
                    ? Prodit.t("tomorrow") : day.format(start);
            if (!d.equals(lastDay)) {
                lastDay = d;
                TextView h = new TextView(this);
                h.setText(d.substring(0, 1).toUpperCase() + d.substring(1));
                h.setTextColor(Ui.GRAY);
                h.setTextSize(13);
                h.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 2));
                list.addView(h);
            }
            boolean live = start.getTime() - 10 * 60_000L <= now;   // за 10 минут до начала и во время встречи
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.box(this, Ui.WHITE, 0));
            int p = Ui.dp(this, 14);
            row.setPadding(p, p, p, p);
            TextView time = new TextView(this);
            time.setText(hm.format(start) + "\n" + hm.format(new Date(end)));
            time.setTextColor(live ? 0xFFB39BFF : Ui.BLACK);
            time.setTextSize(14);
            time.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(time, new LinearLayout.LayoutParams(Ui.dp(this, 56), -2));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(this);
            t.setText(m.optString("title") + (live && start.getTime() <= now ? "  · " + Prodit.t("now") : ""));
            t.setTextColor(Ui.BLACK);
            t.setTextSize(16);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            txt.addView(t);
            TextView sub = new TextView(this);
            sub.setText(Prodit.t("organizer") + ": " + m.optString("organizer", m.optString("organizer_email")));
            sub.setTextColor(Ui.GRAY);
            sub.setTextSize(13);
            txt.addView(sub);
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            TextView join = Ui.button(this, Prodit.t("join"), live ? Ui.YELLOW : Ui.WHITE);
            join.setTextSize(14);
            join.setOnClickListener(v -> Prodit.openCall(this, m.optString("room"), m.optString("title")));
            row.addView(join);
            row.setOnLongClickListener(v -> {   // ссылка для гостей без приложения
                String url = m.optString("link");
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, m.optString("title") + "\n" + url), Prodit.t("share_link")));
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 8);
            list.addView(row, lp);
        }
        status.setText(shown == 0 ? Prodit.t("no_meetings") : "");
    }

    /** Новая встреча: тема, дата, время, длительность, гости, комментарий → prodit_meet_create. */
    private void create() {
        int p = Ui.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        final EditText title = edit(box, Prodit.t("title"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final Calendar when = Calendar.getInstance();
        when.add(Calendar.HOUR_OF_DAY, 1);
        when.set(Calendar.MINUTE, 0);
        final TextView date = pick(box), time = pick(box);
        final Runnable upd = () -> {
            date.setText(Prodit.t("date") + ": " + new SimpleDateFormat("d MMMM yyyy", new Locale(Prodit.lang())).format(when.getTime()));
            time.setText(Prodit.t("time") + ": " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(when.getTime()));
        };
        upd.run();
        date.setOnClickListener(v -> new DatePickerDialog(this, (dp, y, mo, d) -> {
            when.set(y, mo, d);
            upd.run();
        }, when.get(Calendar.YEAR), when.get(Calendar.MONTH), when.get(Calendar.DAY_OF_MONTH)).show());
        time.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, mi) -> {
            when.set(Calendar.HOUR_OF_DAY, h);
            when.set(Calendar.MINUTE, mi);
            upd.run();
        }, when.get(Calendar.HOUR_OF_DAY), when.get(Calendar.MINUTE), true).show());
        final EditText minutes = edit(box, Prodit.t("minutes"), InputType.TYPE_CLASS_NUMBER);
        minutes.setText("60");
        final EditText guests = edit(box, Prodit.t("guests"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        guests.setSingleLine(false);
        final EditText note = edit(box, Prodit.t("note"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(Prodit.t("new_meeting")).setView(sv)
                .setPositiveButton(Prodit.t("create"), null).setNegativeButton(Prodit.t("cancel"), null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONArray em = new JSONArray();
            for (String s : guests.getText().toString().split("[,;\\s]+"))
                if (s.contains("@")) em.put(s.trim().toLowerCase(Locale.ROOT));
            if (title.getText().toString().trim().isEmpty() || em.length() == 0) {
                Toast.makeText(this, Prodit.t("need_title"), Toast.LENGTH_LONG).show();
                return;
            }
            v.setEnabled(false);
            new Thread(() -> {
                String msg;
                boolean ok = false;
                try {
                    SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
                    iso.setTimeZone(TimeZone.getDefault());
                    Prodit.api(this, "action=prodit_meet_create", "POST", new JSONObject()
                            .put("title", title.getText().toString().trim()).put("start", iso.format(when.getTime()))
                            .put("tz", TimeZone.getDefault().getID())
                            .put("duration", Math.max(15, parseInt(minutes.getText().toString(), 60)))
                            .put("emails", em).put("note", note.getText().toString().trim()).put("lang", Prodit.lang()));
                    msg = Prodit.t("created");
                    ok = true;
                } catch (Exception e) {
                    msg = Prodit.error(e);
                }
                final String m = msg;
                final boolean done = ok;
                main.post(() -> {
                    Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                    v.setEnabled(true);
                    if (done) {
                        dlg.dismiss();
                        load();
                    }
                });
            }).start();
        }));
        dlg.show();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private EditText edit(LinearLayout box, String hint, int type) {
        TextView l = new TextView(this);
        l.setText(hint);
        l.setTextSize(12);
        l.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private TextView pick(LinearLayout box) {
        TextView t = new TextView(this);
        t.setTextSize(16);
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        box.addView(t);
        return t;
    }
}
