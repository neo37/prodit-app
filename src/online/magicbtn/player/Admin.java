package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 3.9: админ-режим на телефоне владельца — вход мастер-ключом сайта (долгое нажатие на номер версии в меню ☰).
 * Удалить любое сообщение общего чата, закрыть/открыть общий чат, снять все блокировки в личке.
 */
final class Admin {

    private Admin() {
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("admin", 0);
    }

    static String key(Context c) {
        return prefs(c).getString("key", "");
    }

    static boolean on(Context c) {
        return !key(c).isEmpty();
    }

    /** Долгое нажатие на версию: вход по ключу или меню админа. */
    static void open(final Activity a) {
        if (on(a)) menu(a);
        else login(a);
    }

    private static void login(final Activity a) {
        final EditText in = new EditText(a);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        in.setHint(Ui.t(R.string.admin_key));
        new AlertDialog.Builder(a).setTitle(Ui.t(R.string.admin_title)).setView(in)
                .setPositiveButton(Ui.t(R.string.admin_enter), (d, w) -> {
                    final String k = in.getText().toString().trim();
                    if (k.isEmpty()) return;
                    call(a, k, "action=admin_check", null, o -> {
                        prefs(a).edit().putString("key", k).apply();
                        Toast.makeText(a, Ui.t(R.string.admin_on), Toast.LENGTH_SHORT).show();
                        menu(a);
                    });
                })
                .setNegativeButton(Ui.t(R.string.cancel), null).show();
    }

    private static void menu(final Activity a) {
        call(a, key(a), "action=admin_check", null, st -> {
            final boolean closed = st.optBoolean("chat_closed");
            String[] items = {
                    (closed ? "🔓 " : "🔒 ") + Ui.t(closed ? R.string.admin_chat_open : R.string.admin_chat_close),
                    "⛔ " + Ui.t(R.string.admin_unblock),
                    "✕ " + Ui.t(R.string.admin_off)};
            new AlertDialog.Builder(a).setTitle(Ui.t(R.string.admin_title)).setItems(items, (d, w) -> {
                if (w == 0) call(a, key(a), "action=admin_chat_close", "{\"on\":\"" + (closed ? "0" : "1") + "\"}",
                        o -> Toast.makeText(a, Ui.t(o.optBoolean("chat_closed") ? R.string.admin_chat_closed : R.string.admin_chat_opened),
                                Toast.LENGTH_LONG).show());
                else if (w == 1) call(a, key(a), "action=admin_unblock_all", "{}",
                        o -> Toast.makeText(a, Ui.t(R.string.admin_unblocked, o.optInt("unblocked")), Toast.LENGTH_LONG).show());
                else {
                    prefs(a).edit().remove("key").apply();
                    Toast.makeText(a, Ui.t(R.string.admin_off_done), Toast.LENGTH_SHORT).show();
                }
            }).show();
        });
    }

    interface Ok {
        void ok(JSONObject o);
    }

    /** Запрос с мастер-ключом в фоне; ответ ok — в UI-потоке, иначе тост с ошибкой. */
    static void call(final Activity a, final String key, final String query, final String body, final Ok ok) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            JSONObject o;
            try {
                o = Social.call(a, ChatActivity.CHAT_API, key, query, body == null ? "GET" : "POST", body);
            } catch (Exception e) {
                o = null;
            }
            final JSONObject r = o;
            main.post(() -> {
                if (a.isFinishing()) return;
                if (r != null && r.optBoolean("ok")) ok.ok(r);
                else Toast.makeText(a, Ui.t(R.string.admin_fail), Toast.LENGTH_LONG).show();
            });
        }).start();
    }
}
