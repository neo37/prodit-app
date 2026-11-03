package online.magicbtn.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Звонки один на один через efir.umno.cloud (видеовстречи без регистрации, в браузере):
 * звонящий создаёт комнату со случайным именем и шлёт собеседнику в личку приглашение «📞 … ссылка».
 * У собеседника приложение показывает входящий звонок (IncomingCallActivity), старые версии — ссылку в чате.
 * Отказ — ответное сообщение «📵 …».
 */
final class Calls {

    static final String ROOMS = "https://efir.umno.cloud/rooms/";
    /** Сколько секунд после приглашения звонок считается «идущим» — дальше это пропущенный звонок. */
    static final int RING_SEC = 60;
    /** Ссылка на комнату любого эфира (…/rooms/<имя>): в Production IT адрес эфира меняется в админке. */
    private static final Pattern LINK = Pattern.compile("https://[^\\s/]+(?:/[^\\s]*)?/rooms/([A-Za-z0-9_-]{6,64})");

    static String rooms() {
        return Flags.PRODIT ? Prodit.cfg(App.instance, "rooms_url") : ROOMS;
    }

    private Calls() {
    }

    /** Ссылка на комнату звонка из текста сообщения; null — это не звонок. */
    static String link(String text) {
        if (text == null) return null;
        Matcher m = LINK.matcher(text);
        return m.find() ? m.group() : null;
    }

    static boolean isDecline(String text) {
        return text != null && text.startsWith("📵");
    }

    private static String newRoom() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder(Flags.PRODIT ? Prodit.cfg(App.instance, "room_prefix") : "smartai-");   // efir.umno.cloud оформляет такие комнаты под Умный плеер
        for (int i = 0; i < 14; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    /** Позвонить: приглашение в личку, затем открыть комнату в браузере. */
    static void start(final Activity a, final String peer, final String peerName) {
        if (peer == null || !peer.matches("[a-f0-9]{16}")) return;
        Toast.makeText(a, Ui.t(R.string.call_starting), Toast.LENGTH_SHORT).show();
        final String url = rooms() + newRoom();
        new Thread(() -> {
            String err = null;
            try {
                JSONObject o = Social.call(a, ChatActivity.CHAT_API, "", "action=dm_post", "POST", new JSONObject()
                        .put("to", peer).put("to_name", peerName == null ? "" : peerName)
                        .put("name", Social.name(a).isEmpty() ? "Guest" : Social.name(a))
                        .put("text", "📞 " + Ui.t(R.string.call_invite) + "\n" + url).toString());
                if (!o.optBoolean("ok")) err = "blocked".equals(o.optString("error")) ? Ui.t(R.string.dm_blocked) : Ui.t(R.string.call_failed);
            } catch (Exception e) {
                err = Ui.t(R.string.call_failed);
            }
            final String error = err;
            a.runOnUiThread(() -> {
                if (error != null) Toast.makeText(a, error, Toast.LENGTH_LONG).show();
                else open(a, url);
            });
        }).start();
    }

    /** Комната звонка — в браузере (камера и микрофон — его разрешения, приложению они не нужны). */
    static void open(Context c, String url) {
        // сразу в комнату под своим именем, без экрана входа (efir.umno.cloud понимает autojoin)
        String n = Social.name(c);
        if (Flags.PRODIT) {   // Production IT: звонок внутри приложения (LiveKit SDK), без браузера
            Matcher m = LINK.matcher(url);
            if (m.find()) {
                Prodit.openCall(c, m.group(1), null);
                return;
            }
        }
        if (url.startsWith(ROOMS) && !url.contains("?"))
            url += "?autojoin=1&name=" + Uri.encode(n == null || n.isEmpty() ? "Guest" : n);
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(c, Ui.t(R.string.call_failed), Toast.LENGTH_LONG).show();
        }
    }

    /** Отклонить: собеседнику — «📵 Звонок отклонён». */
    static void decline(final Context c, final String peer, final String peerName) {
        new Thread(() -> {
            try {
                Social.call(c, ChatActivity.CHAT_API, "", "action=dm_post", "POST", new JSONObject()
                        .put("to", peer).put("to_name", peerName == null ? "" : peerName)
                        .put("name", Social.name(c).isEmpty() ? "Guest" : Social.name(c))
                        .put("text", "📵 " + Ui.t(R.string.call_declined)).toString());
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** Отметить переписку с собеседником прочитанной (сервер отмечает при чтении лички; новых сообщений не берём). */
    static void markRead(final Context c, final String peer) {
        new Thread(() -> {
            try {
                Social.call(c, ChatActivity.CHAT_API, "", "action=dm&with=" + peer + "&since=2000000000", "GET", null);
            } catch (Exception ignored) {
            }
        }).start();
    }
}
