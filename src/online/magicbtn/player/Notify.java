package online.magicbtn.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Уведомления о личных сообщениях и звонках + число на значке приложения.
 * Пушей через Google (FCM) нет: проверяем action=dm_threads — в фоне DmJob (Android — примерно раз в 15 мин),
 * пока приложение открыто — каждые несколько секунд (App). Число на значке = сумма setNumber уведомлений:
 * прочитали — уведомление снимается, значок гаснет.
 */
final class Notify {

    static final String CH_DM = "dm";
    static final String CH_CALL = "call";
    static final String CH_CHAT = "chat";
    /** Личка, пока приложение открыто: без звука и всплытия (сверху уже есть плашка), число на значке остаётся. */
    static final String CH_DM_QUIET = "dm_quiet";
    static final String CH_SOCIAL = "social";
    static final String CH_UPLOAD = "upload";
    private static final String TAG_CHAT = "chat", TAG_SOCIAL = "social";
    private static final String TAG = "dm";

    private Notify() {
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("on", true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        if (!on) nm(c).cancelAll();
    }

    /** Разрешение Android 13+ на уведомления. */
    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Один раз спросить разрешение (Android 13+) — когда человек впервые открыл личку или контакты. */
    static void askOnce(android.app.Activity a) {
        if (Build.VERSION.SDK_INT < 33 || allowed(a) || prefs(a).getBoolean("asked", false)) return;
        prefs(a).edit().putBoolean("asked", true).apply();
        a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("notify", 0);
    }

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel dm = new NotificationChannel(CH_DM, Ui.t(R.string.notif_channel_dm), NotificationManager.IMPORTANCE_HIGH);
        dm.setShowBadge(true);
        NotificationChannel call = new NotificationChannel(CH_CALL, Ui.t(R.string.notif_channel_call), NotificationManager.IMPORTANCE_HIGH);
        call.setShowBadge(true);
        nm(c).createNotificationChannel(dm);
        nm(c).createNotificationChannel(call);
        NotificationChannel chat = new NotificationChannel(CH_CHAT, Ui.t(R.string.notif_channel_chat), NotificationManager.IMPORTANCE_DEFAULT);
        chat.setShowBadge(true);
        NotificationChannel social = new NotificationChannel(CH_SOCIAL, Ui.t(R.string.notif_channel_social), NotificationManager.IMPORTANCE_DEFAULT);
        social.setShowBadge(true);
        NotificationChannel up = new NotificationChannel(CH_UPLOAD, Ui.t(R.string.notif_channel_upload), NotificationManager.IMPORTANCE_LOW);
        up.setShowBadge(false);
        nm(c).createNotificationChannel(chat);
        nm(c).createNotificationChannel(social);
        nm(c).createNotificationChannel(up);
        NotificationChannel quiet = new NotificationChannel(CH_DM_QUIET, Ui.t(R.string.notif_channel_dm), NotificationManager.IMPORTANCE_LOW);
        quiet.setShowBadge(true);
        nm(c).createNotificationChannel(quiet);
    }

    /** Снять уведомление собеседника — переписку открыли. */
    static void cancel(Context c, String peer) {
        nm(c).cancel(TAG, peer.hashCode());
        prefs(c).edit().remove("n_" + peer).apply();
    }

    private static long parse(String iso) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Проверить личку: уведомления по каждому собеседнику, входящий звонок (если приложение открыто — экран звонка).
     * Не в UI-потоке. Возвращает число непрочитанных (-1 — нет сети).
     */
    static int check(Context c, boolean foreground) {
        JSONObject o;
        try {
            o = Social.call(c, ChatActivity.CHAT_API, "", "action=dm_threads", "GET", null);
        } catch (Exception e) {
            return -1;
        }
        if (!o.optBoolean("ok")) return -1;
        JSONArray a = o.optJSONArray("items");
        long now = o.has("now") ? parse(o.optString("now")) : System.currentTimeMillis();
        boolean show = enabled(c) && allowed(c);
        if (show) channels(c);
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor ed = p.edit();
        int total = 0;
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            String peer = t.optString("peer");
            int unread = t.optInt("unread");
            if (unread == 0 || t.optBoolean("mine") || Block.blocked(c, peer)) {
                if (p.contains("n_" + peer)) {
                    nm(c).cancel(TAG, peer.hashCode());
                    ed.remove("n_" + peer);
                }
                continue;
            }
            total += unread;
            String ts = t.optString("ts");
            String name = Contacts.name(c, peer);
            if (name == null || name.isEmpty()) name = t.optString("name");
            Contacts.touch(c, peer, t.optString("name"), t.optString("avatar"));
            String text = t.optString("text");
            String call = Calls.link(text);
            long age = now - parse(ts);
            boolean ringing = call != null && age < Calls.RING_SEC * 1000L && !ts.equals(p.getString("rung_" + peer, ""));
            if (ringing && foreground) {   // приложение открыто — экран входящего звонка
                ed.putString("rung_" + peer, ts);
                c.startActivity(IncomingCallActivity.intent(c, peer, name, t.optString("avatar"), call)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                ed.putString("n_" + peer, ts); // экран звонка вместо уведомления
                continue;
            }
            if (peer.equals(ChatActivity.openPeer) || ts.equals(p.getString("n_" + peer, ""))) continue;
            if (foreground && call == null)   // приложение открыто — плашка сверху (3.4)
                Banner.show(App.current(), "✉ " + name, text, ChatActivity.dm(c, peer, name));
            if (show) post(c, peer, name, call != null ? (ringing ? "📞 " + Ui.t(R.string.call_incoming) : "📞 " + Ui.t(R.string.call_missed)) : text,
                    unread, call != null && ringing && !foreground ? call : null, foreground && call == null);
            ed.putString("n_" + peer, ts);
        }
        ed.apply();
        DmListActivity.setUnread(total);
        return total;
    }

    private static void post(Context c, String peer, String name, String text, int unread, String callUrl, boolean silent) {
        int id = peer.hashCode();
        Intent open = ChatActivity.dm(c, peer, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, callUrl != null ? CH_CALL : silent ? CH_DM_QUIET : CH_DM) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(name)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setNumber(unread)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setCategory(callUrl != null ? Notification.CATEGORY_CALL : Notification.CATEGORY_MESSAGE)
                .setContentIntent(PendingIntent.getActivity(c, id, open, fl));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        if (callUrl != null) {   // звонок застал приложение закрытым — принять прямо из уведомления
            Intent accept = IncomingCallActivity.intent(c, peer, name, "", callUrl).putExtra("auto", "accept")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent decline = IncomingCallActivity.intent(c, peer, name, "", callUrl).putExtra("auto", "decline")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.addAction(new Notification.Action.Builder(null, Ui.t(R.string.call_accept), PendingIntent.getActivity(c, id + 1, accept, fl)).build());
            b.addAction(new Notification.Action.Builder(null, Ui.t(R.string.call_decline), PendingIntent.getActivity(c, id + 2, decline, fl)).build());
        }
        try {
            nm(c).notify(TAG, id, b.build());
        } catch (SecurityException ignored) {   // разрешение отозвали между проверкой и показом
        }
    }

    /** Пуш «новое в общем чате»: одно уведомление, число растёт, пока чат не открыли. */
    /** Production IT: сообщение в комнате (чат компании). Нажатие — открыть эту комнату. */
    static void room(Context c, String room, String name, String text, String author) {
        if (room == null || !enabled(c) || !allowed(c)) return;
        if (author != null && (author.equals(Social.author(c)) || Block.blocked(c, author))) return;
        channels(c);
        String title = room.startsWith("co:") ? "🏢 " + room.substring(3) : "📋 Бэклог";
        Intent open = ChatActivity.room(c, room, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        String body = (name == null || name.isEmpty() ? "Guest" : name) + ": " + (text == null ? "" : text);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_CHAT) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body)).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(PendingIntent.getActivity(c, room.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            nm(c).notify("room:" + room, 1, b.build());
        } catch (SecurityException ignored) {
        }
    }

    /** Production IT: «Встреча через N минут». Нажатие — сразу в комнату встречи. */
    static void meeting(Context c, String title, String room, String minutes) {
        if (room == null || !allowed(c)) return;
        channels(c);
        Intent open = new Intent().setClassName(c, "online.magicbtn.player.LkCallActivity").putExtra("room", room)
                .putExtra("title", title == null ? "" : title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String head = "⏰ " + Prodit.t("soon_meeting", minutes == null ? "10" : minutes);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_CHAT) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify).setContentTitle(head).setContentText(title == null ? "" : title)
                .setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT)
                .setContentIntent(PendingIntent.getActivity(c, room.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            nm(c).notify("meet:" + room, 1, b.build());
        } catch (SecurityException ignored) {
        }
    }

    static void chat(Context c, String name, String text, String author) {
        if (!enabled(c) || !allowed(c) || ChatActivity.generalOpen) return;
        if (author != null && (author.equals(Social.author(c)) || Block.blocked(c, author))) return;
        channels(c);
        SharedPreferences p = prefs(c);
        int n = p.getInt("chat_unread", 0) + 1;
        p.edit().putInt("chat_unread", n).apply();
        Intent open = new Intent(c, ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        String who = name == null || name.isEmpty() ? "Guest" : name;
        String body = who + ": " + (text == null ? "" : text);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_CHAT) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(n > 1 ? Ui.t(R.string.notif_chat_new, n) : Ui.t(R.string.notif_chat))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setNumber(n)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(PendingIntent.getActivity(c, 7001, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            nm(c).notify(TAG_CHAT, 1, b.build());
        } catch (SecurityException ignored) {
        }
    }

    /** Общий чат открыли — снять уведомление и обнулить счётчик. */
    static void clearChat(Context c) {
        prefs(c).edit().putInt("chat_unread", 0).apply();
        nm(c).cancel(TAG_CHAT, 1);
    }

    /** Пуш «комментарий к моему видео». */
    static void comment(Context c, String name, String text, String file) {
        if (!enabled(c) || !allowed(c)) return;
        channels(c);
        String body = (name == null || name.isEmpty() ? "Guest" : name) + ": " + (text == null ? "" : text)
                + (file == null || file.isEmpty() ? "" : "\n🎬 " + file);
        Intent open = new Intent(c, MySourceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_SOCIAL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(Ui.t(R.string.notif_comment_title))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setNumber(1)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL)
                .setContentIntent(PendingIntent.getActivity(c, 7002, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            nm(c).notify(TAG_SOCIAL, (file == null ? "" : file).hashCode(), b.build());
        } catch (SecurityException ignored) {
        }
    }

    /** Прогресс отправки видео (уведомление без звука); pct < 0 — убрать. */
    static void upload(Context c, int id, String title, int pct) {
        if (pct < 0) {
            nm(c).cancel("upload", id);
            return;
        }
        if (!allowed(c)) return;
        channels(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_UPLOAD) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(Ui.t(R.string.share_uploading, pct))
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        try {
            nm(c).notify("upload", id, b.build());
        } catch (SecurityException ignored) {
        }
    }
}
