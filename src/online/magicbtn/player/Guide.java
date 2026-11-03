package online.magicbtn.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 3.12: подробное обучение с Алисой — сразу при первом запуске, по всем разделам (первый — чат), тексты assets/help.
 * «Отложить» запоминает шаг: «Помощь» или ☰ → «Обучение» продолжают с него. Пройдено — «Помощь» открывает справочник.
 */
final class Guide {

    private Guide() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("guide", 0);
    }

    static boolean done(Context c) {
        return p(c).getBoolean("done", false);
    }

    /** Показать само при запуске: ещё не пройдено и не отложено. */
    static boolean auto(Context c) {
        return !done(c) && !p(c).getBoolean("later", false);
    }

    static void restart(Context c) {
        p(c).edit().putInt("pos", 0).putBoolean("done", false).putBoolean("later", false).apply();
    }

    /** Кнопка «Помощь» / пункт «Обучение»: продолжить обучение или (если пройдено) открыть справочник. */
    static void open(Activity a) {
        if (done(a)) {
            a.startActivity(new Intent(a, HelpActivity.class));
            return;
        }
        if (a instanceof HomeActivity) ((HomeActivity) a).guide();
        else a.startActivity(new Intent(a, HomeActivity.class).putExtra("guide", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    static String lang(Context c) {
        String l = Look.lang(c);
        if (!l.isEmpty()) return l;
        Locale loc = c.getResources().getConfiguration().getLocales().get(0);
        return "sr".equals(loc.getLanguage()) ? ("Latn".equals(loc.getScript()) ? "sr-Latn" : "sr") : loc.getLanguage();
    }

    static JSONArray chapters(Context c) {
        String l = lang(c);
        for (String t : new String[]{l, l.startsWith("sr") ? "sr-Latn" : l, "en"}) {
            try (InputStream in = c.getAssets().open("help/" + t + ".json")) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return new JSONArray(b.toString("UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return new JSONArray();
    }

    /** Шаги: приветствие, по шагу на раздел (кнопка раздела на главном подсвечивается), прощание. */
    static void start(final Activity a, Map<String, View> targets) {
        List<Tour.Step> st = new ArrayList<>();
        st.add(new Tour.Step(null, Ui.t(R.string.guide_hello), "guide_hello"));
        JSONArray ch = chapters(a);
        for (int i = 0; i < ch.length(); i++) {
            JSONObject c = ch.optJSONObject(i);
            if (c == null) continue;
            StringBuilder t = new StringBuilder(c.optString("lead"));
            JSONArray items = c.optJSONArray("items");
            for (int k = 0; items != null && k < items.length(); k++) t.append("\n\n• ").append(items.optString(k));
            st.add(new Tour.Step(targets.get(c.optString("id")), c.optString("icon") + "  " + c.optString("title"), t.toString(),
                    "help/ru/" + c.optString("id")));
        }
        st.add(new Tour.Step(targets.get("help"), Ui.t(R.string.guide_bye), "guide_bye"));
        final Context app = a.getApplicationContext();
        Tour.guide(a, st.toArray(new Tour.Step[0]), p(a).getInt("pos", 0), new Tour.Progress() {
            @Override
            public void at(int i) {
                p(app).edit().putInt("pos", i).apply();
            }

            @Override
            public void done() {
                p(app).edit().putBoolean("done", true).putBoolean("later", false).putInt("pos", 0).apply();
            }

            @Override
            public void postponed(int i) {
                p(app).edit().putBoolean("later", true).putInt("pos", i).apply();
            }
        });
    }
}
