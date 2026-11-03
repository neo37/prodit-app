package online.magicbtn.player;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 3.4: непрочитанное в общем чате и подсветка новых сообщений без пушей.
 * Проверка action=chat&since=… — пока приложение открыто (App, раз в ~15 с) и в фоне (DmJob, раз в ~15 мин).
 * Открыто приложение — плашка Banner; закрыто — уведомление Notify.chat (если пуши FCM ещё не работают).
 * Число на ☰ = личка + общий чат; экран со ☰ подписывается через listener.
 */
final class Inbox {

    static volatile int chatUnread;
    /** Кто показывает ☰ — обновить число (вызывается в UI-потоке). */
    static volatile Runnable listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Inbox() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("inbox", 0);
    }

    static int total() {
        return Math.max(0, DmListActivity.unreadCached()) + chatUnread;
    }

    static void changed() {
        Runnable l = listener;
        if (l != null) main.post(l);
    }

    /** Новые сообщения общего чата от других (не в UI-потоке). */
    static void checkChat(Context c, boolean foreground) {
        SharedPreferences p = prefs(c);
        long seen = p.getLong("seen", -1), told = p.getLong("told", -1);
        JSONObject o;
        try {
            o = Social.call(c, ChatActivity.CHAT_API, "", "action=chat&since=" + Math.max(0, Math.max(seen, told)), "GET", null);
        } catch (Exception e) {
            return;
        }
        JSONArray a = o.optJSONArray("items");
        if (a == null) return;
        long max = Math.max(seen, told);
        if (seen < 0) {   // первый запуск: всё старое считаем прочитанным, без шквала уведомлений
            for (int i = 0; i < a.length(); i++) max = Math.max(max, a.optJSONObject(i).optLong("id"));
            p.edit().putLong("seen", max).putLong("told", max).apply();
            return;
        }
        String me = Social.author(c);
        JSONObject last = null;
        int fresh = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null) continue;
            long id = m.optLong("id");
            max = Math.max(max, id);
            if (m.optBoolean("mine") || me.equals(m.optString("author")) || Block.blocked(c, m.optString("author"))) continue;
            fresh++;
            last = m;
        }
        p.edit().putLong("told", max).apply();
        if (fresh == 0) return;
        chatUnread += fresh;
        changed();
        if (ChatActivity.generalOpen || !Push.chatEnabled(c)) return;
        String name = last.optString("name");
        String text = last.optString("text");
        if (foreground) {
            Banner.show(App.current(), "💬 " + Ui.t(R.string.notif_chat) + " · " + name, text,
                    new Intent(c, ChatActivity.class));
        } else if (!Push.active(c)) {
            Notify.chat(c, name, text, last.optString("author"));
        }
    }

    /** Общий чат открыт и прочитан до lastId. */
    static void seenChat(Context c, long lastId) {
        SharedPreferences p = prefs(c);
        if (lastId > p.getLong("seen", -1)) p.edit().putLong("seen", lastId).putLong("told", Math.max(lastId, p.getLong("told", -1))).apply();
        if (chatUnread != 0) {
            chatUnread = 0;
            changed();
        }
        Notify.clearChat(c);
    }
}
