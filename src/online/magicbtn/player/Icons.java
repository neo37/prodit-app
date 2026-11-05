package online.magicbtn.player;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

import java.util.HashMap;
import java.util.Map;

/**
 * 3.4 (27), тема «Консоль»: эмодзи-значки интерфейса (☰ ✕ ⋮ ♥ 💬 🗑 …) → Material Symbols Rounded
 * (Google, Apache 2.0; assets/fonts/Icons*.ttf — только нужные значки, ~10 КБ). Значок рисуется цветом текста,
 * одинаково на всех телефонах. ♥ ⏺ ★ — закрашенные. Подменяются эмодзи в любом месте текста (Fonts.apply).
 */
final class Icons {

    private static final String[] PAIRS = {
            "☰", "\ue5d2",
            "✕", "\ue5cd",
            "⋮", "\ue5d4",
            "♥", "\ue87e",
            "♡", "\ue87e",
            "\ud83d\udcac", "\ue0cb",
            "✉", "\ue159",
            "\ud83d\uddd1", "\ue92e",
            "▶", "\ue037",
            "\ud83d\udc4e", "\uf578",
            "\ud83d\udcde", "\uf0d4",
            "\ud83c\udfac", "\ue404",
            "\ud83d\udc65", "\uea21",
            "⏺", "\ue061",
            "\ud83d\udcbe", "\uf091",
            "\ud83d\udcf1", "\ue7ba",
            "⬇", "\uf090",
            "⬆", "\uf09b",
            "⛔", "\uf08c",
            "\ud83d\udca1", "\ue90f",
            "\ud83c\udf9e", "\ue04a",
            "✎", "\uf097",
            "⚙", "\ue8b8",
            "\ud83d\udd17", "\ue250",
            "\ud83d\udcc1", "\ue2c7",
            "\ud83c\udfb2", "\ueb40",
            "\ud83c\udf10", "\ue80b",
            "\ud83d\udcf5", "\ue9cc",
            "\ud83d\udea9", "\uf0c6",
            "⌂", "\ue9b2",
            "\ud83d\udd14", "\ue7f5",
            "↗", "\ue163",
            "✓", "\ue668",
            "⧉", "\ue14d",
            "\ud83d\udcce", "\ue226",
            "\ud83d\udc46", "\ue913",
            "\ud83d\uddbc", "\ue3f4",
            "⏸", "\ue034",
            "\ud83d\udce4", "\ue6b8",
            "⇅", "\ue8d5",
            "\ud83d\udd00", "\ue043",
            "➤", "\ue163",
            "\ud83d\udd12", "\ue899",
            "⏪", "\ue020",
            "⏩", "\ue01f",
            "\ud83d\udd52", "\uefd6",
            "⏱", "\ue425",
            "\ud83c\udfa8", "\ue40a",
            "⚠", "\uf083",
            "★", "\uf09a",
            "☆", "\uf09a",
            "‹", "\ue5cb",
            "\ud83d\udce5", "\uf090",
            "\ud83d\udd04", "\ue5d5",
            "\ud83d\udcf2", "\uf2cd",
            // 05.11.2026 Production IT: календарь, компания, профиль, бэклог, звонок, чат
            "\ud83d\udcc5", "\uebcc",
            "\ud83c\udfe2", "\ue7ee",
            "\ud83d\udc64", "\ue7fd",
            "\ud83d\udccb", "\ue85d",
            "\ud83c\udfa4", "\ue029",
            "\ud83d\udd07", "\ue02b",
            "\ud83d\udcf7", "\ue04b",
            "\u25a6", "\ue8f3",
            "\u21c4", "\ue8d4",
            "\u27a4", "\ue163",
            "\uff0b", "\ue145",
            "\u2197", "\uf1e1",
    };
    private static final String FILLED = "♥⏺★";
    private static final Map<String, String> MAP = new HashMap<>();
    private static Typeface outline, filled;

    static {
        for (int i = 0; i + 1 < PAIRS.length; i += 2) MAP.put(PAIRS[i], PAIRS[i + 1]);
    }

    private Icons() {
    }

    private static void load(Context c) {
        if (outline != null) return;
        try {
            outline = Typeface.createFromAsset(c.getAssets(), "fonts/Icons.ttf");
            filled = Typeface.createFromAsset(c.getAssets(), "fonts/IconsFilled.ttf");
        } catch (Exception e) {
            outline = filled = null;
        }
    }

    /** Есть ли что подменять (быстрая проверка). */
    static boolean has(CharSequence s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            if (MAP.containsKey(new String(Character.toChars(cp)))) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** Текст со значками вместо эмодзи (или null — подменять нечего / шрифт не загрузился). */
    static CharSequence iconify(Context c, CharSequence s) {
        load(c);
        if (outline == null || !has(s)) return null;
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            int n = Character.charCount(cp);
            String g = new String(Character.toChars(cp));
            String icon = MAP.get(g);
            if (icon != null) {
                if (i + n < s.length() && s.charAt(i + n) == '\ufe0f') n++;   // вариант-селектор эмодзи
                int at = b.length();
                b.append(icon);
                b.setSpan(new IconSpan(FILLED.contains(g) ? filled : outline), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                b.append(s, i, i + n);
            }
            i += n;
        }
        return b;
    }

    private static final Map<String, String> BACK = new HashMap<>();

    /** Обратно: значок шрифта → исходный эмодзи (для подписей TalkBack). */
    static CharSequence original(CharSequence s) {
        synchronized (BACK) {
            if (BACK.isEmpty()) for (int i = PAIRS.length - 2; i >= 0; i -= 2) BACK.put(PAIRS[i + 1], PAIRS[i]);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            String c = String.valueOf(s.charAt(i));
            String e = BACK.get(c);
            b.append(e != null ? e : c);
        }
        return b;
    }

    /** Признак «текст уже со значками» — чтобы не обрабатывать повторно. */
    static boolean done(CharSequence s) {
        return s instanceof Spanned && ((Spanned) s).getSpans(0, s.length(), IconSpan.class).length > 0;
    }

    static final class IconSpan extends MetricAffectingSpan {
        private final Typeface tf;

        IconSpan(Typeface tf) {
            this.tf = tf;
        }

        private void apply(TextPaint p) {
            p.setTypeface(tf);
            p.setTextSize(p.getTextSize() * 1.15f);
            p.baselineShift += (int) (p.getTextSize() * 0.12f);   // значок по центру строки, а не на базовой линии
        }

        @Override
        public void updateDrawState(TextPaint p) {
            apply(p);
        }

        @Override
        public void updateMeasureState(TextPaint p) {
            apply(p);
        }
    }
}
