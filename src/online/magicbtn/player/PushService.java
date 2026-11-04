package online.magicbtn.player;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Приём пушей FCM. Сервер присылает только тип события, данные приложение забирает само:
 *   dm / call — личка и звонки: Notify.check (то же, что делает фоновая проверка, но сразу);
 *   chat      — новое сообщение в общем чате (имя, текст);
 *   comment   — комментарий к моему видео (имя, текст, название файла).
 */
public class PushService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        Push.register(this, token, true);
    }

    @Override
    public void onMessageReceived(RemoteMessage msg) {
        Map<String, String> d = msg.getData();
        String type = d.get("type");
        if (type == null) return;
        switch (type) {
            case "dm":
            case "call":
                Notify.check(this, App.foreground());
                break;
            case "chat":
                Notify.chat(this, d.get("name"), d.get("text"), d.get("author"));
                break;
            case "room":      // Production IT: чат компании / «Бэклог»
                Notify.room(this, d.get("room"), d.get("name"), d.get("text"), d.get("author"));
                break;
            case "meeting":   // Production IT: напоминание о встрече
                Notify.meeting(this, d.get("title"), d.get("room"), d.get("minutes"));
                break;
            case "comment":
                Notify.comment(this, d.get("name"), d.get("text"), d.get("file"));
                break;
            default:
                break;
        }
    }
}
