package online.magicbtn.player;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Production IT (flavor prodit): настройки с сервера (адреса LiveKit меняются в админке), вход по email, строки ru/en/sr.
 * Звонок — LkCallActivity, мгновенная доставка — Rt (Centrifugo); оба лежат в gradle-build/src/prodit (LiveKit SDK есть
 * только в этой сборке), поэтому отсюда они вызываются по имени класса.
 */
final class Prodit {

    private Prodit() {
    }

    private static final String[][] DEFAULTS = {
            {"token_url", "https://efir.umno.cloud/api/connection-details"},
            {"livekit_url", ""},
            {"rooms_url", "https://efir.umno.cloud/rooms/"},
            {"room_prefix", "productionit-"},
            {"rt_url", ""},
            {"support_email", ""},
    };

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("prodit", 0);
    }

    // ---------- настройки с сервера ----------

    static String cfg(Context c, String k) {
        for (String[] d : DEFAULTS) if (d[0].equals(k)) return prefs(c).getString("cfg_" + k, d[1]);
        return prefs(c).getString("cfg_" + k, "");
    }

    /** Забрать настройки (prodit_config); вызывается при старте и перед звонком. Возвращает false без сети. */
    static boolean refreshConfig(Context c) {
        try {
            JSONObject o = Social.call(c, ChatActivity.CHAT_API, "", "action=prodit_config", "GET", null);
            if (!o.optBoolean("ok")) return false;
            SharedPreferences.Editor e = prefs(c).edit();
            for (String[] d : DEFAULTS) if (o.has(d[0])) e.putString("cfg_" + d[0], o.optString(d[0]));
            e.apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- вход ----------

    static boolean loggedIn(Context c) {
        return !prefs(c).getString("email", "").isEmpty();
    }

    static String email(Context c) {
        return prefs(c).getString("email", "");
    }

    /** Код принят: берём постоянный X-UID этого email (одинаковый на всех телефонах) и имя. */
    static void signedIn(Context c, JSONObject o) {
        c.getSharedPreferences("social", 0).edit().putString("uid", o.optString("uid")).apply();
        prefs(c).edit().putString("email", o.optString("email")).putString("domain", o.optString("domain")).apply();
        String n = o.optString("name");
        if (!n.isEmpty()) Social.setName(c, n);
        Push.init(c.getApplicationContext());   // новый X-UID — токен устройства на сервер заново
        startRealtime(c.getApplicationContext());
    }

    static String domain(Context c) {
        return prefs(c).getString("domain", "");
    }

    static void setDomain(Context c, String d) {
        prefs(c).edit().putString("domain", d == null ? "" : d).apply();
    }

    static void signOut(Context c) {
        prefs(c).edit().remove("email").apply();
        c.getSharedPreferences("social", 0).edit().remove("uid").remove("name").apply();
        c.getSharedPreferences("push", 0).edit().remove("sent").apply();
        stopRealtime();
    }

    // ---------- звонок и мгновенная доставка (классы из сборки prodit) ----------

    /** Комната LiveKit внутри приложения. title — для встреч из календаря. */
    static void openCall(Context c, String room, String title) {
        Intent i = new Intent().setClassName(c, "online.magicbtn.player.LkCallActivity")
                .putExtra("room", room).putExtra("title", title == null ? "" : title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    static void startRealtime(Context c) {
        if (!loggedIn(c)) return;
        try {
            Class.forName("online.magicbtn.player.Rt").getMethod("start", Context.class).invoke(null, c);
        } catch (Throwable ignored) {   // без Centrifugo — обычный опрос App.dmPoll
        }
    }

    static void stopRealtime() {
        try {
            Class.forName("online.magicbtn.player.Rt").getMethod("stop").invoke(null);
        } catch (Throwable ignored) {
        }
    }

    // ---------- строки (ru / en / sr) ----------

    private static final Map<String, String[]> T = new HashMap<>();

    private static void s(String k, String ru, String en, String sr) {
        T.put(k, new String[]{ru, en, sr});
    }

    static {
        s("tagline", "Чат, звонки и встречи команды", "Team chat, calls and meetings", "Чет, позиви и састанци тима");
        s("email", "Рабочий email", "Work email", "Пословни email");
        s("name", "Как вас зовут", "Your name", "Како се зовете");
        s("get_code", "Получить код", "Get a code", "Добиј код");
        s("code_sent", "Код отправлен на %s. Проверьте почту (и «Спам»).", "We sent a code to %s. Check your inbox (and spam).",
                "Код је послат на %s. Проверите пошту (и нежељену).");
        s("code", "Код из письма", "Code from the email", "Код из писма");
        s("sign_in", "Войти", "Sign in", "Пријава");
        s("other_email", "Другой email", "Use another email", "Други email");
        s("resend", "Отправить код ещё раз", "Send the code again", "Пошаљи код поново");
        s("bad_email", "Проверьте адрес почты", "Check the email address", "Проверите адресу");
        s("need_name", "Напишите имя — его увидят собеседники", "Enter your name — people will see it", "Унесите име — видеће га саговорници");
        s("wrong_code", "Неверный код", "Wrong code", "Погрешан код");
        s("code_expired", "Код устарел — запросите новый", "The code expired — request a new one", "Код је истекао — затражите нови");
        s("wait", "Подождите минуту перед новым кодом", "Wait a minute before requesting a new code", "Сачекајте минут пре новог кода");
        s("limit", "Слишком много попыток, попробуйте завтра", "Too many attempts, try tomorrow", "Превише покушаја, покушајте сутра");
        s("mail_failed", "Не удалось отправить письмо, попробуйте позже", "Could not send the email, try later", "Слање није успело, покушајте касније");
        s("offline", "Нет связи с сервером", "No connection to the server", "Нема везе са сервером");
        s("chats", "Чаты", "Chats", "Четови");
        s("team_chat", "Общий чат", "Team chat", "Заједнички чет");
        s("team_chat_sub", "Вся команда Production IT", "Everyone at Production IT", "Цео тим Production IT");
        s("team", "Команда", "Team", "Тим");
        s("team_sub", "Коллеги @%s и чат компании", "Colleagues @%s and company chat", "Колеге @%s и чет компаније");
        s("team_sub_none", "Коллеги по рабочей почте", "Colleagues by work email", "Колеге по пословном email-у");
        s("company_chat", "Чат компании", "Company chat", "Чет компаније");
        s("no_company", "Вы вошли с личной почты — команды по домену нет. Войдите с рабочей почты, чтобы видеть коллег.",
                "You signed in with a personal email — no team by domain. Sign in with your work email to see colleagues.",
                "Пријављени сте личним email-ом — нема тима по домену. Пријавите се пословним email-ом.");
        s("no_colleagues", "Пока никого с @%s — пригласите коллег через «Написать по email».",
                "Nobody from @%s yet — invite colleagues via “Message by email”.", "Још нико са @%s — позовите колеге.");
        s("backlog", "Бэклог", "Backlog", "Беклог");
        s("backlog_sub", "Планы развития Production IT — обсуждаем", "Production IT roadmap — let’s discuss", "Планови развоја Production IT");
        s("soon_meeting", "Встреча через %s мин", "Meeting in %s min", "Састанак за %s мин");
        s("meet_with", "Встреча: %s", "Meeting: %s", "Састанак: %s");
        s("cal_list", "Список", "List", "Листа");
        s("cal_grid", "Календарь", "Calendar", "Календар");
        s("chats_sub", "Личная переписка и звонки", "Direct messages and calls", "Лична преписка и позиви");
        s("meetings", "Встречи", "Meetings", "Састанци");
        s("meetings_sub", "Календарь Production IT", "Production IT calendar", "Календар Production IT");
        s("new_chat", "Написать по email", "Message by email", "Пиши по email-у");
        s("new_chat_sub", "Найти коллегу по адресу почты", "Find a colleague by email", "Пронађи колегу по email-у");
        s("profile", "Профиль", "Profile", "Профил");
        s("sign_out", "Выйти", "Sign out", "Одјава");
        s("sign_out_q", "Выйти из аккаунта %s на этом телефоне?", "Sign out of %s on this phone?", "Одјавити се са %s на овом телефону?");
        s("find", "Найти", "Find", "Пронађи");
        s("not_registered", "%s ещё нет в Production IT. Отправить приглашение установить приложение?",
                "%s is not on Production IT yet. Send an invitation to install the app?",
                "%s још није у Production IT. Послати позивницу за инсталацију?");
        s("invite", "Пригласить", "Invite", "Позови");
        s("invited", "Приглашение отправлено", "Invitation sent", "Позивница је послата");
        s("self", "Это ваш адрес", "This is your address", "То је ваша адреса");
        s("cancel", "Отмена", "Cancel", "Откажи");
        s("no_meetings", "Встреч пока нет", "No meetings yet", "Још нема састанака");
        s("only_mine", "Только мои", "Only mine", "Само моји");
        s("all", "Все", "All", "Сви");
        s("join", "Войти", "Join", "Уђи");
        s("now", "идёт", "now", "у току");
        s("new_meeting", "Новая встреча", "New meeting", "Нови састанак");
        s("title", "Тема", "Title", "Тема");
        s("date", "Дата", "Date", "Датум");
        s("time", "Время", "Time", "Време");
        s("minutes", "Длительность, мин", "Duration, min", "Трајање, мин");
        s("guests", "Гости — email через запятую", "Guests — emails, comma separated", "Гости — email-ови раздвојени зарезом");
        s("note", "Комментарий", "Note", "Напомена");
        s("create", "Создать и разослать", "Create and send", "Направи и пошаљи");
        s("created", "Встреча создана, приглашения отправлены", "Meeting created, invitations sent", "Састанак је направљен, позивнице послате");
        s("need_title", "Нужны тема и хотя бы один гость", "A title and at least one guest are required", "Потребни су тема и бар један гост");
        s("today", "Сегодня", "Today", "Данас");
        s("tomorrow", "Завтра", "Tomorrow", "Сутра");
        s("share_link", "Ссылка для гостей", "Link for guests", "Линк за госте");
        s("organizer", "Организатор", "Organizer", "Организатор");
        // 1.5: регулярные встречи и календарь
        s("r_repeat", "Повтор", "Repeat", "Понављање");
        s("r_none", "Не повторять", "Does not repeat", "Не понавља се");
        s("r_daily", "Каждый день", "Every day", "Сваког дана");
        s("r_weekdays", "По будням", "Every weekday", "Радним данима");
        s("r_weekly", "Каждую неделю", "Every week", "Сваке недеље");
        s("r_biweekly", "Раз в две недели", "Every two weeks", "На две недеље");
        s("r_monthly", "Каждый месяц", "Every month", "Сваког месеца");
        s("r_until", "До даты", "Until", "До датума");
        s("r_until_short", "до %s", "until %s", "до %s");
        s("r_no_end", "Без конца", "No end", "Без краја");
        s("r_scope", "Что изменить", "Apply to", "Шта променити");
        s("r_one", "Только эту встречу", "This meeting only", "Само овај састанак");
        s("r_all", "Всю серию", "The whole series", "Целу серију");
        s("r_bad_until", "Дата окончания раньше начала", "The end date is before the start", "Датум краја је пре почетка");
        s("r_confirm_one", "Отменить только эту встречу? Участники получат уведомление, остальные даты серии останутся.",
                "Cancel this meeting only? Participants will be notified, other dates stay.",
                "Отказати само овај састанак? Учесници ће добити обавештење, остали датуми остају.");
        s("r_confirm_all", "Отменить всю серию встреч? Участники получат уведомление.", "Cancel the whole series? Participants will be notified.",
                "Отказати целу серију? Учесници ће добити обавештење.");
        s("participants", "Участники", "Participants", "Учесници");
        s("close", "Закрыть", "Close", "Затвори");
        s("edit", "Изменить", "Edit", "Измени");
        // 1.3: админы встреч
        s("admin_note", "Вы админ и только поэтому видите эту встречу", "You are an admin — that is the only reason you can see this meeting",
                "Ви сте админ и само зато видите овај састанак");
        s("edit_meeting", "Изменить встречу", "Edit meeting", "Измени састанак");
        s("save", "Сохранить", "Save", "Сачувај");
        s("saved", "Изменения сохранены, участники получат письмо", "Saved, participants will get an email", "Сачувано, учесници ће добити имејл");
        s("cancel_meeting", "Отменить встречу", "Cancel meeting", "Откажи састанак");
        s("confirm_cancel", "Отменить встречу? Участники получат уведомление.", "Cancel the meeting? Participants will be notified.",
                "Отказати састанак? Учесници ће добити обавештење.");
        s("cancelled", "Встреча отменена", "Meeting cancelled", "Састанак је отказан");
        s("admins", "Админы", "Admins", "Админи");
        s("main_admins", "Главные админы", "Main admins", "Главни админи");
        s("add_admin", "Добавить админа", "Add admin", "Додај админа");
        s("admin_email", "Email нового админа", "New admin's email", "Email новог админа");
        s("remove_admin", "Снять админа %s?", "Remove admin %s?", "Уклонити админа %s?");
        s("no_admins", "Назначенных админов пока нет", "No appointed admins yet", "Још нема постављених админа");
        s("admins_hint", "Админы видят все встречи и могут их редактировать. Назначать и снимать админов могут только главные.",
                "Admins see all meetings and can edit them. Only main admins can add or remove admins.",
                "Админи виде све састанке и могу да их мењају. Постављају и уклањају админе само главни.");
        s("remove", "Снять", "Remove", "Уклони");
        s("add", "Добавить", "Add", "Додај");
    }

    static String t(String k, Object... args) {
        String[] v = T.get(k);
        if (v == null) return k;
        String l = Look.lang(App.instance);
        if (l.isEmpty()) l = Locale.getDefault().getLanguage();
        String s = l.startsWith("ru") ? v[0] : l.startsWith("sr") ? v[2] : v[1];
        return args.length == 0 ? s : String.format(s, args);
    }

    static String lang() {
        String l = Look.lang(App.instance);
        if (l.isEmpty()) l = Locale.getDefault().getLanguage();
        return l.startsWith("ru") ? "ru" : l.startsWith("sr") ? "sr" : "en";
    }

    /** Запрос к серверу Production IT; ответ без ok — исключение с кодом ошибки сервера (wrong code, not registered…). */
    static JSONObject api(Context c, String query, String method, JSONObject body) throws Exception {
        JSONObject o = Social.call(c, ChatActivity.CHAT_API, "", query, method, body == null ? null : body.toString());
        if (!o.optBoolean("ok")) throw new Exception(o.optString("error", "error"));
        return o;
    }

    /** Текст ошибки сервера для человека. */
    static String error(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("wrong code")) return t("wrong_code");
        if (m.contains("code expired")) return t("code_expired");
        if (m.contains("wait")) return t("wait");
        if (m.contains("daily limit")) return t("limit");
        if (m.contains("mail failed") || m.equals("HTTP 502")) return t("mail_failed");
        if (m.contains("bad email")) return t("bad_email");
        if (m.contains("self")) return t("self");
        return t("offline");
    }
}
