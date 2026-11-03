package online.magicbtn.player;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 3.6: Pro — купленный навсегда (Billing, pro_forever) или на время: за просмотр рекламы (+AD_DAYS, копится до
 * MAX_DAYS) или по коду (CODES: код → дней, один раз на телефоне). Срок хранится на телефоне.
 */
final class Pro {

    static final int AD_DAYS = 1, MAX_DAYS = 30;
    private static final String[][] CODES = {{"3637", "30"}};
    private static final long DAY = 24 * 3600_000L;

    private Pro() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("pro", 0);
    }

    static boolean forever(Context c) {
        return c.getSharedPreferences("billing", 0).getBoolean("pro", false);
    }

    static long until(Context c) {
        return prefs(c).getLong("until", 0);
    }

    static boolean on(Context c) {
        return !Flags.MONETIZE || forever(c) || until(c) > System.currentTimeMillis();
    }

    /** Добавить дней (не дальше MAX_DAYS от сегодня). Возвращает новый срок. */
    static long grant(Context c, int days) {
        long now = System.currentTimeMillis();
        long u = Math.max(now, until(c)) + days * DAY;
        u = Math.min(u, now + MAX_DAYS * DAY);
        prefs(c).edit().putLong("until", u).apply();
        Inbox.changed();
        return u;
    }

    /** Код: дней Pro, 0 — неверный, -1 — уже использован на этом телефоне. */
    static int redeem(Context c, String code) {
        String k = code == null ? "" : code.trim();
        for (String[] e : CODES) {
            if (!e[0].equals(k)) continue;
            if (prefs(c).getBoolean("used_" + k, false)) return -1;
            int days = Integer.parseInt(e[1]);
            prefs(c).edit().putBoolean("used_" + k, true).apply();
            long now = System.currentTimeMillis();   // код — до полного срока, без потолка MAX_DAYS от рекламы
            prefs(c).edit().putLong("until", Math.max(now, until(c)) + days * DAY).apply();
            return days;
        }
        return 0;
    }
}
