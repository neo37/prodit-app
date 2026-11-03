package online.magicbtn.player;

import android.content.Context;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Блокировка авторов (на этом устройстве): их сообщения в чате и комментарии скрываются.
 * Автор — анонимный номер от сервера (не ID устройства). Разблокировать — в Настройках.
 */
final class Block {

    private Block() {
    }

    private static JSONObject load(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences("block", 0).getString("authors", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean blocked(Context c, String author) {
        return author != null && !author.isEmpty() && load(c).has(author);
    }

    static void block(Context c, String author, String name) {
        if (author == null || author.isEmpty()) return;
        JSONObject o = load(c);
        try {
            o.put(author, name == null ? "" : name);
        } catch (Exception ignored) {
        }
        c.getSharedPreferences("block", 0).edit().putString("authors", o.toString()).apply();
    }

    static void unblock(Context c, String author) {
        JSONObject o = load(c);
        o.remove(author);
        c.getSharedPreferences("block", 0).edit().putString("authors", o.toString()).apply();
    }

    /** id → имя (как его видели при блокировке). */
    static Map<String, String> all(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        JSONObject o = load(c);
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, o.optString(k));
        }
        return m;
    }
}
