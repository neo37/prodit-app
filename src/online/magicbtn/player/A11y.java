package online.magicbtn.player;

import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 3.9: подписи для TalkBack у кнопок-значков (☰, ✕, 🗑 …) — иначе незрячий пользователь слышит только «кнопка».
 * Подпись ставится, если на кнопке один значок без слов и своей подписи у неё нет.
 */
final class A11y {
    // значок, ru, en, sr (латиница)
    private static final String[][] NAMES = {
            {"☰", "Меню", "Menu", "Meni"},
            {"✕", "Закрыть", "Close", "Zatvori"},
            {"⋮", "Ещё", "More", "Još"},
            {"♥", "Нравится", "Like", "Sviđa mi se"},
            {"♡", "Нравится", "Like", "Sviđa mi se"},
            {"👎", "Не нравится", "Dislike", "Ne sviđa mi se"},
            {"💬", "Комментарии", "Comments", "Komentari"},
            {"🗑", "Удалить", "Delete", "Obriši"},
            {"▶", "Смотреть", "Play", "Pusti"},
            {"⏸", "Пауза", "Pause", "Pauza"},
            {"⏪", "Назад на 10 секунд", "Back 10 seconds", "Nazad 10 sekundi"},
            {"⏩", "Вперёд на 10 секунд", "Forward 10 seconds", "Napred 10 sekundi"},
            {"⏺", "Запись", "Record", "Snimanje"},
            {"📞", "Позвонить", "Call", "Pozovi"},
            {"⚙", "Настройки", "Settings", "Podešavanja"},
            {"⌂", "Домой", "Home", "Početna"},
            {"‹", "Назад", "Back", "Nazad"},
            {"🔔", "Уведомления", "Notifications", "Obaveštenja"},
            {"📤", "Поделиться", "Share", "Podeli"},
            {"↗", "Поделиться", "Share", "Podeli"},
            {"➤", "Отправить", "Send", "Pošalji"},
            {"📎", "Прикрепить", "Attach", "Priloži"},
            {"⬇", "Скачать", "Download", "Preuzmi"},
            {"🔒", "Замок", "Lock", "Zaključaj"},
            {"🔄", "Обновить", "Refresh", "Osveži"},
            {"🔀", "Перемешать", "Shuffle", "Izmešaj"},
            {"⇅", "Сортировка", "Sort", "Sortiranje"},
            {"✎", "Изменить", "Edit", "Izmeni"},
            {"★", "Избранное", "Favorite", "Omiljeno"},
            {"💡", "Идея", "Idea", "Ideja"},
            {"🚩", "Пожаловаться", "Report", "Prijavi"},
            {"⛔", "Заблокировать", "Block", "Blokiraj"},
            {"👥", "Контакты", "Contacts", "Kontakti"},
    };
    private static final Map<String, String[]> MAP = new HashMap<>();

    static {
        for (String[] n : NAMES) MAP.put(n[0], n);
    }

    private A11y() {
    }

    static void describe(TextView t) {
        if (!t.isClickable() || t.getContentDescription() != null) return;
        CharSequence s = t.getText();
        if (s == null || s.length() == 0 || s.length() > 4) return;
        String[] n = MAP.get(Icons.original(s).toString().trim().replace("️", ""));
        if (n == null) return;
        String l = Look.lang(t.getContext());
        if (l.isEmpty()) l = t.getResources().getConfiguration().getLocales().get(0).getLanguage();
        int i = "ru".equals(l) ? 1 : "sr".equals(l) || l.startsWith("sr") ? 3 : 2;
        t.setContentDescription(n[i]);
    }
}
