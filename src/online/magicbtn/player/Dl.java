package online.magicbtn.player;

import android.content.Context;

/** Что скачивать в офлайн — отдельно для каждого источника (по хосту). */
final class Dl {

    static final int ALL = 0, LIKED = 1, NONE = 2;

    private Dl() {
    }

    static int policy(Context c, String host) {
        return host == null ? ALL : c.getSharedPreferences("dl", 0).getInt(host, ALL);
    }

    static void set(Context c, String host, int p) {
        c.getSharedPreferences("dl", 0).edit().putInt(host, p).apply();
    }

    static String title(int p) {
        return Ui.t(p == LIKED ? R.string.dl_liked : p == NONE ? R.string.dl_none : R.string.dl_all);
    }

    /** Хост из метки «хост/папка» или из URL. */
    static String host(String labelOrUrl) {
        if (labelOrUrl == null) return null;
        String s = labelOrUrl.replaceFirst("^https?://", "");
        int i = s.indexOf('/');
        return i < 0 ? s : s.substring(0, i);
    }
}
