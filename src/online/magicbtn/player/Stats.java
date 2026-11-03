package online.magicbtn.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 3.8: статистика кнопок главного экрана — только на телефоне, по дням, когда пользователь её включил:
 *   chat   — отправленные сообщения (общий чат и личка);
 *   player — открытые видео и фото;
 *   game   — минуты в игре.
 */
final class Stats {

    static final String CHAT = "chat", PLAYER = "player", GAME = "game";

    private Stats() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("stats", 0);
    }

    private static String day(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d);
    }

    static boolean on(Context c, String what) {
        return p(c).getBoolean("on_" + what, false);
    }

    static void setOn(Context c, String what, boolean on) {
        p(c).edit().putBoolean("on_" + what, on).apply();
    }

    /** +n к сегодняшнему счётчику (только если сбор включён). */
    static void add(Context c, String what, int n) {
        if (c == null || !on(c, what) || n <= 0) return;
        String k = what + ":" + day(new Date());
        p(c).edit().putInt(k, p(c).getInt(k, 0) + n).apply();
    }

    /** Последние 7 дней: подписи (дни недели) и значения. */
    static int[] week(Context c, String what, String[] labelsOut) {
        int[] v = new int[7];
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -6);
        SimpleDateFormat dow = new SimpleDateFormat("EE", Look.res().getResources().getConfiguration().getLocales().get(0));
        for (int i = 0; i < 7; i++) {
            Date d = cal.getTime();
            v[i] = p(c).getInt(what + ":" + day(d), 0);
            labelsOut[i] = dow.format(d);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return v;
    }

    static int total(int[] v) {
        int t = 0;
        for (int x : v) t += x;
        return t;
    }
}
