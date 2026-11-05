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
    /** Вид «Календарь» — нативный экран на тех же данных (ProditCalendarView). */
    private boolean gridView;
    private ProditCalendarView calendar;
    private ScrollView listScroll;
    private TextView viewBtn;
    /** 1.3: роль на сервере — "super" (главный админ), "admin" или "" */
    private String role = "";
    private TextView adminsBtn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        onlyMine = getSharedPreferences("prodit", 0).getBoolean("only_mine", true);
        gridView = getSharedPreferences("prodit", 0).getBoolean("cal_grid", false);
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
        viewBtn = Ui.button(this, "", Ui.WHITE);
        viewBtn.setTextSize(13);
        viewBtn.setOnClickListener(v -> {
            gridView = !gridView;
            getSharedPreferences("prodit", 0).edit().putBoolean("cal_grid", gridView).apply();
            applyView();
        });
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
        vl.leftMargin = Ui.dp(this, 8);
        header.addView(viewBtn, vl);
        adminsBtn = Ui.button(this, "👥", Ui.WHITE);
        adminsBtn.setTextSize(13);
        adminsBtn.setVisibility(android.view.View.GONE);
        adminsBtn.setOnClickListener(v -> admins());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.leftMargin = Ui.dp(this, 8);
        header.addView(adminsBtn, ml);
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

        listScroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(p, 0, p, p);
        listScroll.addView(list);
        calendar = new ProditCalendarView(this, new ProditCalendarView.Host() {
            @Override
            public void openMeeting(JSONObject m) {
                card(m);
            }

            @Override
            public void newMeeting(Calendar when) {
                createDialog(ProditMeetingsActivity.this, null, null, null, when, ProditMeetingsActivity.this::load);
            }
        });
        android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        body.addView(listScroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        body.addView(calendar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(col);
        Ui.fitSystemBars(this);
        applyView();
    }

    /** Переключить вид: список встреч ⇄ календарь. */
    private void applyView() {
        viewBtn.setText(gridView ? "☰ " + Prodit.t("cal_list") : "▦ " + Prodit.t("cal_grid"));
        status.setVisibility(gridView ? android.view.View.GONE : android.view.View.VISIBLE);
        listScroll.setVisibility(gridView ? android.view.View.GONE : android.view.View.VISIBLE);
        calendar.setVisibility(gridView ? android.view.View.VISIBLE : android.view.View.GONE);
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
                    role = o.optString("role", "");
                    adminsBtn.setVisibility(role.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                    if (role.isEmpty()) onlyMine = false;   // обычному пользователю сервер и так отдаёт только его встречи
                    filter.setVisibility(role.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                    show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    status.setText(Prodit.error(e));
                    if (gridView) Toast.makeText(this, Prodit.error(e), Toast.LENGTH_LONG).show();   // в календаре строки статуса нет
                });
            }
        }).start();
    }

    static Date parse(String iso) {
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
        calendar.setItems(items, onlyMine);
        java.util.Set<String> seenSeries = new java.util.HashSet<>();   // от серии в списке — только ближайшая дата
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
            if (!m.optString("recur").isEmpty() && !seenSeries.add(m.optString("uid"))) continue;
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
            if (!m.optString("recur").isEmpty()) {
                TextView rep = new TextView(this);
                rep.setText(recurLabel(m));
                rep.setTextColor(0xFFB39BFF);
                rep.setTextSize(13);
                txt.addView(rep);
            }
            if (m.optBoolean("admin_view")) {   // 1.3: чужая встреча, видна только потому, что вы админ
                TextView note = new TextView(this);
                note.setText(Prodit.t("admin_note"));
                note.setTextColor(0xFFB07A00);
                note.setTextSize(12);
                note.setPadding(0, Ui.dp(this, 4), 0, 0);
                txt.addView(note);
            }
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            TextView join = Ui.button(this, Prodit.t("join"), live ? Ui.YELLOW : Ui.WHITE);
            join.setTextSize(14);
            join.setOnClickListener(v -> Prodit.openCall(this, m.optString("room"), m.optString("title")));
            row.addView(join);
            row.setOnClickListener(v -> card(m));
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

    private static final String[] RECURS = {"", "daily", "weekdays", "weekly", "biweekly", "monthly"};

    private static String recurName(String recur) {
        return Prodit.t(recur.isEmpty() ? "r_none" : "r_" + recur);
    }

    /** «↻ Каждую неделю до 01.02.2027» */
    private static String recurLabel(JSONObject m) {
        String until = m.optString("until");
        return "↻ " + recurName(m.optString("recur")) + (until.isEmpty() ? "" : " " + Prodit.t("r_until_short", until));
    }

    /** Карточка встречи: когда, кто, повтор; «Войти», ссылка для гостей, «Изменить» — организатору и админам. */
    private void card(final JSONObject m) {
        Date st = parse(m.optString("start"));
        if (st == null) return;
        int p = Ui.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        SimpleDateFormat day = new SimpleDateFormat("EEEE, d MMMM", new Locale(Prodit.lang())), hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        StringBuilder t = new StringBuilder(day.format(st)).append(", ").append(hm.format(st)).append(" – ")
                .append(hm.format(new Date(st.getTime() + m.optInt("minutes", 60) * 60_000L)));
        if (!m.optString("recur").isEmpty()) t.append("\n").append(recurLabel(m));
        if (m.optBoolean("admin_view")) t.append("\n").append(Prodit.t("admin_note"));
        t.append("\n\n").append(Prodit.t("organizer")).append(": ").append(m.optString("organizer", m.optString("organizer_email")));
        JSONArray es = m.optJSONArray("emails");
        StringBuilder g = new StringBuilder();
        for (int i = 0; es != null && i < es.length(); i++)
            if (!es.optString(i).equalsIgnoreCase(m.optString("organizer_email"))) g.append(g.length() > 0 ? ", " : "").append(es.optString(i));
        if (g.length() > 0) t.append("\n").append(Prodit.t("participants")).append(": ").append(g);
        if (!m.optString("note").isEmpty()) t.append("\n\n").append(m.optString("note"));
        TextView info = new TextView(this);
        info.setText(t);
        info.setTextSize(14);
        box.addView(info);
        TextView share = pick(this, box);
        share.setText("🔗 " + Prodit.t("share_link"));
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, m.optString("title") + "\n" + m.optString("link")), Prodit.t("share_link"))));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(m.optString("title")).setView(sv)
                .setPositiveButton(Prodit.t("join"), (d, w) -> Prodit.openCall(this, m.optString("room"), m.optString("title")))
                .setNegativeButton(Prodit.t("close"), null);
        if (m.optBoolean("can_edit")) b.setNeutralButton(Prodit.t("edit"), (d, w) -> createDialog(this, null, null, m, this::load));
        b.show();
    }

    /** Новая встреча: тема, дата, время, длительность, гости, комментарий → prodit_meet_create. */
    private void create() {
        createDialog(this, null, null, this::load);
    }

    /** Новая встреча: тема, дата, время, длительность, гости, комментарий → prodit_meet_create.
     *  withAuthor — собеседник из личного диалога (его почту сервер подставит сам). */
    static void createDialog(final Activity a, final String withAuthor, final String withName, final Runnable onDone) {
        createDialog(a, withAuthor, withName, null, onDone);
    }

    /** edit != null — правка существующей встречи (организатор или админ): prodit_meet_edit, кнопка «Отменить встречу». */
    static void createDialog(final Activity a, final String withAuthor, final String withName, final JSONObject edit, final Runnable onDone) {
        createDialog(a, withAuthor, withName, edit, null, onDone);
    }

    /** start — время новой встречи (нажали на свободное место в календаре). У серии edit — выбранная дата: start в списке. */
    static void createDialog(final Activity a, final String withAuthor, final String withName, final JSONObject edit, final Calendar start, final Runnable onDone) {
        int p = Ui.dp(a, 20);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        final EditText title = edit(a, box, Prodit.t("title"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (withName != null) title.setText(Prodit.t("meet_with", withName));
        final Calendar when = Calendar.getInstance();
        when.add(Calendar.HOUR_OF_DAY, 1);
        when.set(Calendar.MINUTE, 0);
        when.set(Calendar.SECOND, 0);
        when.set(Calendar.MILLISECOND, 0);
        if (start != null) when.setTimeInMillis(start.getTimeInMillis());
        if (edit != null) {
            if (edit.optBoolean("admin_view")) {
                TextView an = new TextView(a);
                an.setText(Prodit.t("admin_note"));
                an.setTextColor(0xFFB07A00);
                an.setTextSize(13);
                box.addView(an, 0);
            }
            title.setText(edit.optString("title"));
            Date st = parse(edit.optString("start"));
            if (st != null) when.setTime(st);
        }
        final TextView date = pick(a, box), time = pick(a, box);
        final Runnable upd = () -> {
            date.setText(Prodit.t("date") + ": " + new SimpleDateFormat("d MMMM yyyy", new Locale(Prodit.lang())).format(when.getTime()));
            time.setText(Prodit.t("time") + ": " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(when.getTime()));
        };
        upd.run();
        date.setOnClickListener(v -> new DatePickerDialog(a, (dp, y, mo, d) -> {
            when.set(y, mo, d);
            upd.run();
        }, when.get(Calendar.YEAR), when.get(Calendar.MONTH), when.get(Calendar.DAY_OF_MONTH)).show());
        time.setOnClickListener(v -> new TimePickerDialog(a, (tp, h, mi) -> {
            when.set(Calendar.HOUR_OF_DAY, h);
            when.set(Calendar.MINUTE, mi);
            upd.run();
        }, when.get(Calendar.HOUR_OF_DAY), when.get(Calendar.MINUTE), true).show());
        final EditText minutes = edit(a, box, Prodit.t("minutes"), InputType.TYPE_CLASS_NUMBER);
        minutes.setText(edit != null ? String.valueOf(edit.optInt("minutes", 60)) : "60");
        // повтор: у серии сначала выбираем, что менять — эту дату или всё
        final boolean series = edit != null && !edit.optString("recur").isEmpty();
        final String[] scope = {"one"};
        final String[] recur = {edit != null ? edit.optString("recur") : ""};
        final String[] until = {edit != null ? edit.optString("until") : ""};
        final TextView repeatRow = pick(a, box), untilRow = pick(a, box);
        final Runnable updRepeat = () -> {
            boolean oneOnly = series && "one".equals(scope[0]);
            repeatRow.setVisibility(oneOnly ? android.view.View.GONE : android.view.View.VISIBLE);
            untilRow.setVisibility(oneOnly || recur[0].isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            repeatRow.setText(Prodit.t("r_repeat") + ": " + recurName(recur[0]));
            untilRow.setText(Prodit.t("r_until") + ": " + (until[0].isEmpty() ? "—" : until[0]));
        };
        repeatRow.setOnClickListener(v -> {
            String[] names = new String[RECURS.length];
            for (int i = 0; i < names.length; i++) names[i] = recurName(RECURS[i]);
            new AlertDialog.Builder(a).setTitle(Prodit.t("r_repeat")).setItems(names, (x, i) -> {
                recur[0] = RECURS[i];
                updRepeat.run();
            }).show();
        });
        untilRow.setOnClickListener(v -> {
            Calendar u = (Calendar) when.clone();
            DatePickerDialog dp = new DatePickerDialog(a, (x, y, mo, d) -> {
                until[0] = String.format(Locale.ROOT, "%04d-%02d-%02d", y, mo + 1, d);
                updRepeat.run();
            }, u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH));
            dp.setButton(AlertDialog.BUTTON_NEUTRAL, Prodit.t("r_no_end"), (x, w) -> {
                until[0] = "";
                updRepeat.run();
            });
            dp.show();
        });
        if (series) {
            final TextView scopeRow = pick(a, box);
            box.removeView(scopeRow);
            box.addView(scopeRow, edit.optBoolean("admin_view") ? 1 : 0);
            final String[] codes = {"one", "all"}, names = {Prodit.t("r_one"), Prodit.t("r_all")};
            scopeRow.setText(Prodit.t("r_scope") + ": " + names[0]);
            scopeRow.setOnClickListener(v -> new AlertDialog.Builder(a).setTitle(Prodit.t("r_scope")).setItems(names, (x, i) -> {
                scope[0] = codes[i];
                scopeRow.setText(Prodit.t("r_scope") + ": " + names[i]);
                updRepeat.run();
            }).show());
        }
        updRepeat.run();
        final EditText guests = edit(a, box, Prodit.t("guests"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        guests.setSingleLine(false);
        final EditText note = edit(a, box, Prodit.t("note"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (edit != null) {   // гости без организатора — его почту сервер добавит сам
            StringBuilder g = new StringBuilder();
            JSONArray es = edit.optJSONArray("emails");
            String org = edit.optString("organizer_email").toLowerCase(Locale.ROOT);
            for (int i = 0; es != null && i < es.length(); i++)
                if (!es.optString(i).equalsIgnoreCase(org)) g.append(g.length() > 0 ? ", " : "").append(es.optString(i));
            guests.setText(g);
            note.setText(edit.optString("note"));
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder bld = new AlertDialog.Builder(a).setTitle(Prodit.t(edit != null ? "edit_meeting" : "new_meeting")).setView(sv)
                .setPositiveButton(Prodit.t(edit != null ? "save" : "create"), null).setNegativeButton(Prodit.t("cancel"), null);
        if (edit != null) bld.setNeutralButton(Prodit.t("cancel_meeting"), null);
        AlertDialog dlg = bld.create();
        dlg.setOnShowListener(d -> {
          if (edit != null) dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(a)
                .setMessage(Prodit.t(!series ? "confirm_cancel" : "one".equals(scope[0]) ? "r_confirm_one" : "r_confirm_all"))
                .setPositiveButton(Prodit.t("cancel_meeting"), (x, y) -> {
                    JSONObjectBuilder req = new JSONObjectBuilder().put("uid", edit.optString("uid")).put("cancel", true);
                    if (series) req.put("occurrence", edit.optString("start")).put("scope", scope[0]);
                    send(a, req.o, true, dlg, onDone, Prodit.t("cancelled"));
                })
                .setNegativeButton(Prodit.t("cancel"), null).show());
          dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONArray em = new JSONArray();
            for (String s : guests.getText().toString().split("[,;\\s]+"))
                if (s.contains("@")) em.put(s.trim().toLowerCase(Locale.ROOT));
            if (title.getText().toString().trim().isEmpty() || (em.length() == 0 && withAuthor == null && edit == null)) {
                Toast.makeText(a, Prodit.t("need_title"), Toast.LENGTH_LONG).show();
                return;
            }
            final boolean oneOnly = series && "one".equals(scope[0]);
            if (!oneOnly && !recur[0].isEmpty() && !until[0].isEmpty()
                    && until[0].compareTo(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(when.getTime())) < 0) {
                Toast.makeText(a, Prodit.t("r_bad_until"), Toast.LENGTH_LONG).show();
                return;
            }
            v.setEnabled(false);
            new Thread(() -> {
                String msg;
                boolean ok = false;
                try {
                    SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
                    iso.setTimeZone(TimeZone.getDefault());
                    JSONObject req = new JSONObject()
                            .put("title", title.getText().toString().trim()).put("start", iso.format(when.getTime()))
                            .put("tz", TimeZone.getDefault().getID())
                            .put("duration", Math.max(15, parseInt(minutes.getText().toString(), 60)))
                            .put("emails", em).put("note", note.getText().toString().trim()).put("lang", Prodit.lang());
                    if (series) req.put("occurrence", edit.optString("start")).put("scope", scope[0]);
                    if (!oneOnly) req.put("recur", recur[0]).put("until", recur[0].isEmpty() ? "" : until[0]);
                    if (edit != null) {
                        Prodit.api(a, "action=prodit_meet_edit", "POST", req.put("uid", edit.optString("uid")));
                        msg = Prodit.t("saved");
                    } else {
                        Prodit.api(a, "action=prodit_meet_create", "POST", req.put("with", withAuthor == null ? "" : withAuthor));
                        msg = Prodit.t("created");
                    }
                    ok = true;
                } catch (Exception e) {
                    msg = Prodit.error(e);
                }
                final String m = msg;
                final boolean done = ok;
                a.runOnUiThread(() -> {
                    Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                    v.setEnabled(true);
                    if (done) {
                        dlg.dismiss();
                        if (onDone != null) onDone.run();
                    }
                });
            }).start();
          });
        });
        dlg.show();
    }

    /** Отмена встречи (prodit_meet_edit cancel) в фоне; результат — тостом. */
    private static void send(Activity a, JSONObject req, boolean close, AlertDialog dlg, Runnable onDone, String okMsg) {
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                Prodit.api(a, "action=prodit_meet_edit", "POST", req);
                msg = okMsg;
                ok = true;
            } catch (Exception e) {
                msg = Prodit.error(e);
            }
            final String m = msg;
            final boolean done = ok;
            a.runOnUiThread(() -> {
                Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                if (done && close) {
                    dlg.dismiss();
                    if (onDone != null) onDone.run();
                }
            });
        }).start();
    }

    /** JSONObject.put бросает checked JSONException — обёртка для лямбд. */
    private static final class JSONObjectBuilder {
        final JSONObject o = new JSONObject();

        JSONObjectBuilder put(String k, Object v) {
            try {
                o.put(k, v);
            } catch (Exception ignored) {
            }
            return this;
        }
    }

    // ---------- 1.3: админы ----------

    /** Список админов; главные админы добавляют и снимают (prodit_admins / prodit_admin_set). */
    private void admins() {
        new Thread(() -> {
            try {
                JSONObject o = Prodit.api(this, "action=prodit_admins", "GET", null);
                main.post(() -> adminsDialog(o));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, Prodit.error(e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void adminsDialog(JSONObject o) {
        final boolean isSuper = "super".equals(o.optString("role"));
        int p = Ui.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        TextView hint = new TextView(this);
        hint.setText(Prodit.t("admins_hint"));
        hint.setTextColor(Ui.GRAY);
        hint.setTextSize(13);
        box.addView(hint);
        TextView mh = new TextView(this);
        mh.setText(Prodit.t("main_admins"));
        mh.setTypeface(Typeface.DEFAULT_BOLD);
        mh.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        box.addView(mh);
        JSONArray sup = o.optJSONArray("super");
        for (int i = 0; sup != null && i < sup.length(); i++) {
            TextView t = new TextView(this);
            t.setText("★ " + sup.optString(i));
            t.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            box.addView(t);
        }
        TextView ah = new TextView(this);
        ah.setText(Prodit.t("admins"));
        ah.setTypeface(Typeface.DEFAULT_BOLD);
        ah.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        box.addView(ah);
        JSONArray adm = o.optJSONArray("admins");
        final AlertDialog[] self = new AlertDialog[1];
        if (adm == null || adm.length() == 0) {
            TextView t = new TextView(this);
            t.setText(Prodit.t("no_admins"));
            t.setTextColor(Ui.GRAY);
            box.addView(t);
        }
        for (int i = 0; adm != null && i < adm.length(); i++) {
            final String email = adm.optJSONObject(i).optString("email");
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(email);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            if (isSuper) {
                TextView rm = Ui.button(this, Prodit.t("remove"), Ui.WHITE);
                rm.setTextSize(13);
                rm.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(Prodit.t("remove_admin", email))
                        .setPositiveButton(Prodit.t("remove"), (x, y) -> setAdmin(email, false, self[0]))
                        .setNegativeButton(Prodit.t("cancel"), null).show());
                r.addView(rm);
            }
            box.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
        final EditText add = isSuper ? edit(this, box, Prodit.t("admin_email"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) : null;
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("👥 " + Prodit.t("admins")).setView(sv)
                .setNegativeButton(Prodit.t("cancel"), null);
        if (isSuper) b.setPositiveButton(Prodit.t("add"), null);
        self[0] = b.create();
        if (isSuper) self[0].setOnShowListener(d -> self[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String e = add.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (!e.contains("@")) {
                add.setError(Prodit.t("admin_email"));
                return;
            }
            setAdmin(e, true, self[0]);
        }));
        self[0].show();
    }

    private void setAdmin(String email, boolean on, AlertDialog dlg) {
        new Thread(() -> {
            try {
                JSONObject o = Prodit.api(this, "action=prodit_admin_set", "POST", new JSONObject().put("email", email).put("on", on));
                main.post(() -> {
                    if (dlg != null) dlg.dismiss();
                    adminsDialog(o);
                    load();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, Prodit.error(e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static EditText edit(Activity a, LinearLayout box, String hint, int type) {
        TextView l = new TextView(a);
        l.setText(hint);
        l.setTextSize(12);
        l.setPadding(0, Ui.dp(a, 10), 0, 0);
        box.addView(l);
        EditText e = new EditText(a);
        e.setInputType(type);
        e.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private static TextView pick(Activity a, LinearLayout box) {
        TextView t = new TextView(a);
        t.setTextSize(16);
        t.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 6));
        box.addView(t);
        return t;
    }
}
