package online.magicbtn.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 3.10: обрезка видео (редактор в плеере). Для каждого файла — переключатели по времени [мс, вырезать?]:
 * статус кадра = последний переключатель до него (по умолчанию — оставить). Отметка в момент t
 * переписывает всё, что было размечено дальше t (отмотал назад — и размечаешь заново с этого кадра).
 * Фото: статус в точке 0 — всё фото целиком.
 */
final class Cuts {

    private Cuts() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("cuts", 0);
    }

    private static List<long[]> marks(Context c, String uri) {
        List<long[]> out = new ArrayList<>();
        if (uri == null) return out;
        try {
            JSONArray a = new JSONArray(p(c).getString(uri, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray m = a.getJSONArray(i);
                out.add(new long[]{m.getLong(0), m.getLong(1)});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, String uri, List<long[]> ms) {
        JSONArray a = new JSONArray();
        for (long[] m : ms) a.put(new JSONArray().put(m[0]).put(m[1]));
        if (ms.isEmpty()) p(c).edit().remove(uri).apply();
        else p(c).edit().putString(uri, a.toString()).apply();
    }

    /** Вырезан ли кадр в момент t. */
    static boolean cut(Context c, String uri, long t) {
        boolean s = false;
        for (long[] m : marks(c, uri)) {
            if (m[0] > t) break;
            s = m[1] == 1;
        }
        return s;
    }

    /** С момента t и дальше — вырезать (true) или оставить (false); прежняя разметка после t стирается. */
    static void set(Context c, String uri, long t, boolean cut) {
        List<long[]> ms = new ArrayList<>();
        boolean before = false;
        for (long[] m : marks(c, uri)) {
            if (m[0] >= t) break;
            ms.add(m);
            before = m[1] == 1;
        }
        if (before != cut) ms.add(new long[]{Math.max(0, t), cut ? 1 : 0});
        save(c, uri, ms);
    }

    static boolean any(Context c, String uri) {
        for (long[] m : marks(c, uri)) if (m[1] == 1) return true;
        return false;
    }

    static void clear(Context c, String uri) {
        p(c).edit().remove(uri).apply();
    }

    /** Оставленные куски отрезка [from, to): список {начало, конец} в мс. */
    static List<long[]> keep(Context c, String uri, long from, long to) {
        List<long[]> out = new ArrayList<>();
        List<long[]> ms = marks(c, uri);
        boolean s = false;
        long at = from;
        for (long[] m : ms) {
            if (m[0] <= from) {
                s = m[1] == 1;
                continue;
            }
            if (m[0] >= to) break;
            if (!s && m[0] > at) out.add(new long[]{at, m[0]});
            s = m[1] == 1;
            at = m[0];
        }
        if (!s && to > at) out.add(new long[]{at, to});
        List<long[]> big = new ArrayList<>();
        for (long[] k : out) if (k[1] - k[0] >= 200) big.add(k);   // обрывки короче 0,2 с не берём
        return big;
    }
}
