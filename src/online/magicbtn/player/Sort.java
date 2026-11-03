package online.magicbtn.player;

import android.app.AlertDialog;
import android.content.Context;

import java.util.Comparator;

/** Сортировка списков (онлайн и офлайн): новые, старые, имя, размер, популярные, обсуждаемые. */
final class Sort {

    interface Keys<T> {
        String name(T t);

        long time(T t);

        long size(T t);

        MediaDb.Stat stat(T t);
    }

    static final int NEW = 0, OLD = 1, NAME = 2, SIZE = 3, LIKES = 4, COMMENTS = 5;

    private Sort() {
    }

    static int get(Context c, String where) {
        return c.getSharedPreferences("sort", 0).getInt(where, NEW);
    }

    static String title(int mode) {
        int[] ids = {R.string.sort_new, R.string.sort_old, R.string.sort_name, R.string.sort_size,
                R.string.sort_likes, R.string.sort_comments};
        return Ui.t(ids[mode]);
    }

    static void ask(final Context c, final String where, final Runnable done) {
        String[] items = new String[6];
        for (int i = 0; i < 6; i++) items[i] = title(i);
        new AlertDialog.Builder(c)
                .setTitle(Ui.t(R.string.sort))
                .setSingleChoiceItems(items, get(c, where), (d, w) -> {
                    c.getSharedPreferences("sort", 0).edit().putInt(where, w).apply();
                    d.dismiss();
                    done.run();
                })
                .show();
    }

    static <T> Comparator<T> by(int mode, final Keys<T> k) {
        switch (mode) {
            case OLD:
                return (a, b) -> Long.compare(k.time(a), k.time(b));
            case NAME:
                return (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(k.name(a), k.name(b));
            case SIZE:
                return (a, b) -> Long.compare(k.size(b), k.size(a));
            case LIKES:
                return (a, b) -> Integer.compare(k.stat(b).likes, k.stat(a).likes);
            case COMMENTS:
                return (a, b) -> Integer.compare(k.stat(b).comments, k.stat(a).comments);
            default:
                return (a, b) -> Long.compare(k.time(b), k.time(a));
        }
    }
}
