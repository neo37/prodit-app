package online.magicbtn.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

/**
 * Пуши через Firebase Cloud Messaging (с 3.4). Ключи проекта Firebase попадают в ресурсы fcm_* при сборке
 * (gradle-build читает ../google-services.json); если их нет — пушей нет и работает прежняя проверка лички (DmJob).
 * Сервер шлёт только «есть новое» (data-сообщение) — дальше приложение само забирает данные (PushService).
 * Токен устройства регистрируется на сервере: action=push_register (по X-UID).
 */
final class Push {

    /** Тема общего чата: у Production IT своя (Firebase-проект общий с Умным плеером). */
    static final String TOPIC_CHAT = Flags.PRODIT ? "prodit_chat" : "chat";
    private static boolean ready;

    private Push() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("push", 0);
    }

    /** Есть ли ключи Firebase в этой сборке. */
    static boolean configured(Context c) {
        return !c.getString(R.string.fcm_app_id).isEmpty() && !c.getString(R.string.fcm_api_key).isEmpty();
    }

    /** Пуши работают: Firebase поднят и токен зарегистрирован на сервере. */
    static boolean active(Context c) {
        return ready && prefs(c).getString("sent", null) != null && prefs(c).getBoolean("server", false);
    }

    /** Вызывается из App.onCreate. Без Google Play Services/ключей — тихо выходим. */
    static void init(Context c) {
        if (!configured(c)) return;
        try {
            if (FirebaseApp.getApps(c).isEmpty()) {
                FirebaseApp.initializeApp(c, new FirebaseOptions.Builder()
                        .setApplicationId(c.getString(R.string.fcm_app_id))
                        .setApiKey(c.getString(R.string.fcm_api_key))
                        .setProjectId(c.getString(R.string.fcm_project_id))
                        .setGcmSenderId(c.getString(R.string.fcm_sender_id))
                        .build());
            }
            ready = true;
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(t -> register(c, t, false));
            chatTopic(c, chatEnabled(c));
        } catch (Throwable e) {   // нет сервисов Google (часть китайских прошивок) — остаёмся на опросе
            ready = false;
        }
    }

    static boolean chatEnabled(Context c) {
        return prefs(c).getBoolean("chat", true);
    }

    /** Уведомления об общем чате — подписка на тему FCM «chat». */
    static void setChatEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("chat", on).apply();
        chatTopic(c, on);
    }

    private static void chatTopic(Context c, boolean on) {
        if (!ready) return;
        try {
            if (on) FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_CHAT);
            else FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC_CHAT);
        } catch (Throwable ignored) {
        }
    }

    /** Отправить токен на сервер, если он новый, сменился X-UID или прошло больше суток. */
    static void register(final Context c, final String token, boolean force) {
        if (token == null || token.isEmpty()) return;
        SharedPreferences p = prefs(c);
        String stamp = token + "|" + Social.uid(c);
        if (!force && stamp.equals(p.getString("sent", null))
                && System.currentTimeMillis() - p.getLong("sent_at", 0) < 24 * 3600_000L) return;
        new Thread(() -> {
            try {
                JSONObject o = Social.call(c, ChatActivity.CHAT_API, "", "action=push_register", "POST",
                        new JSONObject().put("token", token).put("app", BuildConfig.APPLICATION_ID).toString());
                if (o.optBoolean("ok"))   // push=false — на сервере ещё нет ключа Firebase, рассылки нет
                    p.edit().putString("sent", stamp).putLong("sent_at", System.currentTimeMillis())
                            .putBoolean("server", o.optBoolean("push")).apply();
            } catch (Exception ignored) {   // нет сети — повторим при следующем запуске
            }
        }).start();
    }
}
