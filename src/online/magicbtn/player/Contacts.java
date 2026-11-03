package online.magicbtn.player;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Мои контакты (на этом устройстве): анонимный номер автора → имя и аватар. Имя можно переименовать —
 * тогда оно не перезаписывается именем, которое человек поставил себе сам.
 * Здесь же hello — сообщить серверу своё имя и видимость в каталоге «Все пользователи».
 */
final class Contacts {

    static final class Contact {
        final String author;
        final String name;
        final String avatar;
        final boolean renamed;

        Contact(String author, JSONObject o) {
            this.author = author;
            this.name = o.optString("n");
            this.avatar = o.optString("a");
            this.renamed = o.optBoolean("r");
        }
    }

    private Contacts() {
    }

    private static JSONObject load(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences("contacts", 0).getString("list", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject o) {
        c.getSharedPreferences("contacts", 0).edit().putString("list", o.toString()).apply();
    }

    static boolean has(Context c, String author) {
        return author != null && load(c).has(author);
    }

    static void add(Context c, String author, String name, String avatar) {
        if (author == null || !author.matches("[a-f0-9]{16}") || author.equals(Social.author(c))) return;
        JSONObject o = load(c);
        JSONObject e = o.optJSONObject(author);
        try {
            if (e == null) e = new JSONObject();
            if (!e.optBoolean("r") && name != null && !name.isEmpty()) e.put("n", name);
            if (avatar != null && !avatar.isEmpty()) e.put("a", avatar);
            o.put(author, e);
        } catch (Exception ignored) {
        }
        save(c, o);
    }

    /** Свежие имя и аватар из списков (диалоги, все пользователи) — только для тех, кто уже в контактах. */
    static void touch(Context c, String author, String name, String avatar) {
        if (has(c, author)) add(c, author, name, avatar);
    }

    static void rename(Context c, String author, String name) {
        JSONObject o = load(c);
        JSONObject e = o.optJSONObject(author);
        if (e == null) return;
        try {
            e.put("n", name);
            e.put("r", true);
        } catch (Exception ignored) {
        }
        save(c, o);
    }

    static void remove(Context c, String author) {
        JSONObject o = load(c);
        o.remove(author);
        save(c, o);
    }

    /** Имя контакта, как я его записал; null — не в контактах. */
    static String name(Context c, String author) {
        JSONObject e = load(c).optJSONObject(author == null ? "" : author);
        return e == null ? null : e.optString("n");
    }

    /** Все контакты по алфавиту. */
    static List<Contact> all(Context c) {
        List<Contact> l = new ArrayList<>();
        JSONObject o = load(c);
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject e = o.optJSONObject(k);
            if (e != null) l.add(new Contact(k, e));
        }
        Collections.sort(l, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return l;
    }

    // ---- каталог пользователей на сервере

    /** Показывать меня во «Все пользователи» (по умолчанию да). */
    static boolean visible(Context c) {
        return c.getSharedPreferences("contacts", 0).getBoolean("visible", true);
    }

    static void setVisible(Context c, boolean on) {
        c.getSharedPreferences("contacts", 0).edit().putBoolean("visible", on).apply();
        hello(c, true);
    }

    /** Сообщить серверу имя и видимость: при запуске не чаще раза в 6 ч, после смены имени — сразу. Сам уходит в фон. */
    static void hello(final Context c, boolean force) {
        final android.content.SharedPreferences p = c.getSharedPreferences("contacts", 0);
        if (!force && System.currentTimeMillis() - p.getLong("hello_at", 0) < 6 * 3600_000L) return;
        if (Social.name(c).isEmpty()) return; // безымянных в каталоге не показываем — и не записываем
        new Thread(() -> {
            try {
                JSONObject o = Social.call(c, ChatActivity.CHAT_API, "", "action=hello", "POST", new JSONObject()
                        .put("name", Social.name(c)).put("hidden", visible(c) ? "0" : "1").toString());
                if (o.optBoolean("ok")) p.edit().putLong("hello_at", System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
            }
        }).start();
    }
}
