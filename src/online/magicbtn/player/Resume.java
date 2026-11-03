package online.magicbtn.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** «Продолжить просмотр»: позиция в каждом видео (до 300 последних). */
final class Resume {

    private static final int MAX = 300;

    private Resume() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("resume", 0);
    }

    static int get(Context c, String url) {
        String v = p(c).getString(url, null);
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.substring(0, v.indexOf(':')));
        } catch (Exception e) {
            return 0;
        }
    }

    /** pos — где остановились; досмотрели почти до конца или едва начали — забываем. */
    static void put(Context c, String url, int pos, int duration) {
        if (url == null || duration <= 0) return;
        SharedPreferences.Editor e = p(c).edit();
        if (pos < 5000 || pos > duration - 8000) {
            e.remove(url).apply();
            return;
        }
        Map<String, ?> all = p(c).getAll();
        if (all.size() > MAX) { // убираем самые старые
            String oldest = null;
            long ts = Long.MAX_VALUE;
            for (Map.Entry<String, ?> en : all.entrySet()) {
                String s = String.valueOf(en.getValue());
                try {
                    long t = Long.parseLong(s.substring(s.indexOf(':') + 1));
                    if (t < ts) {
                        ts = t;
                        oldest = en.getKey();
                    }
                } catch (Exception ignored) {
                }
            }
            if (oldest != null) e.remove(oldest);
        }
        e.putString(url, pos + ":" + System.currentTimeMillis()).apply();
    }
}
