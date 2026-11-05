package online.magicbtn.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Production IT: календарь встреч — нативный экран на данных prodit_meetings.
 * Сверху месяц и неделя (вкладки дней, точка — в этот день есть встречи), ниже день по часам.
 * Нажатие на свободное время — новая встреча на это время, на встречу — её карточка.
 */
final class ProditCalendarView extends LinearLayout {

    interface Host {
        void openMeeting(JSONObject m);

        void newMeeting(Calendar when);
    }

    private static final int HOUR_DP = 56, GUTTER_DP = 52;
    private static final int EVENT = 0xFF3D2A86, EVENT_PAST = 0xFF2A2745, EVENT_EDGE = 0xFFB39BFF, NOW_LINE = 0xFFE58FB0;

    private final Host host;
    private final Calendar day = midnight(Calendar.getInstance());
    private final List<JSONObject> items = new ArrayList<>();
    private final TextView range;
    private final LinearLayout strip;
    private final ScrollView scroll;
    private final Timeline timeline;
    private boolean scrolled;

    ProditCalendarView(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        int p = Ui.dp(c, 16);

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(p, 0, p, Ui.dp(c, 6));
        range = new TextView(c);
        range.setTextColor(Ui.BLACK);
        range.setTextSize(16);
        bar.addView(range, new LayoutParams(0, -2, 1));
        TextView prev = Ui.button(c, "‹", Ui.WHITE), today = Ui.button(c, Prodit.t("today"), Ui.WHITE), next = Ui.button(c, "›", Ui.WHITE);
        today.setTextSize(13);
        prev.setOnClickListener(v -> shift(-7));
        next.setOnClickListener(v -> shift(7));
        today.setOnClickListener(v -> {
            day.setTimeInMillis(midnight(Calendar.getInstance()).getTimeInMillis());
            scrolled = false;
            redraw();
        });
        bar.addView(prev);
        LayoutParams tl = new LayoutParams(-2, -2);
        tl.leftMargin = tl.rightMargin = Ui.dp(c, 6);
        bar.addView(today, tl);
        bar.addView(next);
        addView(bar);

        strip = new LinearLayout(c);
        strip.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 6));
        addView(strip);
        View line = new View(c);
        line.setBackgroundColor(Ui.LINE);
        addView(line, new LayoutParams(-1, Math.max(1, Ui.dp(c, 1) / 2)));

        scroll = new ScrollView(c);
        timeline = new Timeline(c);
        scroll.addView(timeline, new FrameLayout.LayoutParams(-1, Ui.dp(c, HOUR_DP) * 24));
        addView(scroll, new LayoutParams(-1, 0, 1));
        redraw();
    }

    /** Новый список встреч. Прошедшие тоже показываем: календарь листается и назад. */
    void setItems(JSONArray all, boolean onlyMine) {
        items.clear();
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m != null && (!onlyMine || m.optBoolean("mine")) && ProditMeetingsActivity.parse(m.optString("start")) != null) items.add(m);
        }
        redraw();
    }

    private void shift(int days) {
        day.add(Calendar.DAY_OF_YEAR, days);
        redraw();
    }

    private void redraw() {
        Locale loc = new Locale(Prodit.lang());
        String month = new SimpleDateFormat("LLLL yyyy", loc).format(day.getTime());
        range.setText(month.substring(0, 1).toUpperCase(loc) + month.substring(1));
        drawStrip(loc);
        timeline.drawEvents();
        if (!scrolled) {
            scrolled = true;
            final int hour = Math.max(7, Math.min(Calendar.getInstance().get(Calendar.HOUR_OF_DAY) - 1, 17));
            scroll.post(() -> scroll.scrollTo(0, hour * Ui.dp(getContext(), HOUR_DP)));
        }
    }

    private void drawStrip(Locale loc) {
        Context c = getContext();
        strip.removeAllViews();
        Calendar d = midnight((Calendar) day.clone());
        d.add(Calendar.DAY_OF_YEAR, -((d.get(Calendar.DAY_OF_WEEK) + 5) % 7));   // понедельник
        Calendar now = Calendar.getInstance();
        SimpleDateFormat wd = new SimpleDateFormat("EEE", loc);
        for (int i = 0; i < 7; i++) {
            final long at = d.getTimeInMillis();
            boolean selected = sameDay(d, day), today = sameDay(d, now);
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 2));
            TextView name = new TextView(c);
            name.setText(wd.format(d.getTime()));
            name.setTextSize(11);
            name.setTextColor(selected ? Ui.BLACK : Ui.GRAY);
            cell.addView(name);
            TextView num = new TextView(c);
            num.setText(String.valueOf(d.get(Calendar.DAY_OF_MONTH)));
            num.setTextSize(16);
            num.setGravity(Gravity.CENTER);
            num.setTextColor(today ? Ui.ON_ACCENT : Ui.BLACK);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            if (today) circle.setColor(Ui.YELLOW);
            if (selected) circle.setStroke(Ui.dp(c, 2), EVENT_EDGE);
            num.setBackground(circle);
            cell.addView(num, new LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)));
            View dot = new View(c);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(hasMeetings(d) ? EVENT_EDGE : 0);
            dot.setBackground(dd);
            LayoutParams dl = new LayoutParams(Ui.dp(c, 5), Ui.dp(c, 5));
            dl.topMargin = Ui.dp(c, 3);
            cell.addView(dot, dl);
            cell.setOnClickListener(v -> {
                day.setTimeInMillis(at);
                redraw();
            });
            strip.addView(cell, new LayoutParams(0, -2, 1));
            d.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    private boolean hasMeetings(Calendar d) {
        for (JSONObject m : items) if (sameDay(calOf(m), d)) return true;
        return false;
    }

    private static Calendar calOf(JSONObject m) {
        Calendar c = Calendar.getInstance();
        c.setTime(ProditMeetingsActivity.parse(m.optString("start")));
        return c;
    }

    private static Calendar midnight(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** Встреча в сетке дня: минуты от полуночи и дорожка (пересекающиеся встречи идут рядом). */
    private static final class Slot {
        JSONObject m;
        int start, end, lane, lanes;
    }

    private static List<Slot> lay(List<Slot> all) {
        Collections.sort(all, (a, b) -> a.start - b.start);
        List<Slot> group = new ArrayList<>();
        int groupEnd = 0;
        for (Slot s : all) {
            if (!group.isEmpty() && s.start >= groupEnd) {
                closeGroup(group);
                groupEnd = 0;
            }
            group.add(s);
            groupEnd = Math.max(groupEnd, s.end);
        }
        closeGroup(group);
        return all;
    }

    private static void closeGroup(List<Slot> group) {
        List<Integer> lanes = new ArrayList<>();
        for (Slot s : group) {
            int i = 0;
            while (i < lanes.size() && lanes.get(i) > s.start) i++;
            if (i == lanes.size()) lanes.add(0);
            lanes.set(i, s.end);
            s.lane = i;
        }
        for (Slot s : group) s.lanes = lanes.size();
        group.clear();
    }

    /** Часы дня: линии рисуются сами, встречи — дочерние кнопки. */
    private final class Timeline extends FrameLayout {
        private final Paint linePaint = new Paint(), hourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int hourPx, gutterPx;
        private float touchY;

        Timeline(Context c) {
            super(c);
            setWillNotDraw(false);
            hourPx = Ui.dp(c, HOUR_DP);
            gutterPx = Ui.dp(c, GUTTER_DP);
            linePaint.setColor(Ui.LINE);
            hourPaint.setColor(Ui.GRAY);
            hourPaint.setTextSize(Ui.dp(c, 11));
            hourPaint.setTextAlign(Paint.Align.RIGHT);
            setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) touchY = e.getY();
                return false;
            });
            setOnClickListener(v -> {
                Calendar when = (Calendar) day.clone();
                when.add(Calendar.MINUTE, (int) (touchY / hourPx * 2) * 30);   // шаг 30 минут
                if (when.after(Calendar.getInstance())) host.newMeeting(when);
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw) post(this::drawEvents);   // ширина встреч зависит от ширины экрана
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            for (int h = 0; h < 24; h++) {
                float y = h * hourPx;
                canvas.drawLine(gutterPx, y, w, y, linePaint);
                if (h > 0) canvas.drawText(String.format(Locale.ROOT, "%02d:00", h), gutterPx - Ui.dp(getContext(), 8), y + Ui.dp(getContext(), 4), hourPaint);
            }
        }

        void drawEvents() {
            removeAllViews();
            int width = getWidth() - gutterPx - Ui.dp(getContext(), 8);
            if (width <= 0) return;   // ещё не измерен — вернёмся из onSizeChanged
            List<Slot> slots = new ArrayList<>();
            for (JSONObject m : items) {
                Calendar c = calOf(m);
                if (!sameDay(c, day)) continue;
                Slot s = new Slot();
                s.m = m;
                s.start = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
                s.end = Math.min(1440, s.start + m.optInt("minutes", 60));
                slots.add(s);
            }
            long now = System.currentTimeMillis();
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
            Context c = getContext();
            for (Slot s : lay(slots)) {
                final JSONObject m = s.m;
                Date st = ProditMeetingsActivity.parse(m.optString("start"));
                boolean past = st.getTime() + m.optInt("minutes", 60) * 60_000L < now;
                boolean recur = !m.optString("recur").isEmpty();
                TextView ev = new TextView(c);
                ev.setText((recur ? "↻ " : "") + m.optString("title") + (s.end - s.start >= 45 ? "\n" + hm.format(st) : "  " + hm.format(st)));
                ev.setTextColor(0xFFFFFFFF);
                ev.setTextSize(13);
                ev.setEllipsize(TextUtils.TruncateAt.END);
                ev.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 4), 0);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(past ? EVENT_PAST : EVENT);
                bg.setCornerRadius(Ui.dp(c, 8));
                ev.setBackground(bg);
                ev.setOnClickListener(v -> host.openMeeting(m));
                int laneW = width / s.lanes;
                LayoutParams lp = new LayoutParams(laneW - Ui.dp(c, 2), Math.max(Ui.dp(c, 24), (s.end - s.start) * hourPx / 60 - Ui.dp(c, 2)));
                lp.leftMargin = gutterPx + s.lane * laneW;
                lp.topMargin = s.start * hourPx / 60;
                addView(ev, lp);
            }
            Calendar nowC = Calendar.getInstance();
            if (sameDay(nowC, day)) {
                View line = new View(c);
                line.setBackgroundColor(NOW_LINE);
                LayoutParams lp = new LayoutParams(-1, Ui.dp(c, 2));
                lp.leftMargin = gutterPx;
                lp.topMargin = (nowC.get(Calendar.HOUR_OF_DAY) * 60 + nowC.get(Calendar.MINUTE)) * hourPx / 60;
                addView(line, lp);
            }
        }
    }
}
