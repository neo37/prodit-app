package online.magicbtn.player;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import java.util.Locale;

/** Тема (светлая / тёмная / как в системе) и язык приложения (системный / en / ru / sr / sr-Latn). */
final class Look {

    static final String[] LANGS = {"", "en", "ru", "sr", "sr-Latn"};
    static final int CLASSIC = 0, DARK = 1, BP = 2, GLASS = 3, CONSOLE = 4;
    static final int COUNT = 5;

    private static Context localized;

    private Look() {
    }

    static String lang(Context c) {
        return c.getSharedPreferences("look", 0).getString("lang", "");
    }

    /** 3.4: один раз у всех включить «Консоль» (раньше тема сохранялась и при смене языка — у многих записан «Неон»). */
    static void migrateConsole(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("look", 0);
        String key = "console_v" + BuildConfig.VERSION_CODE;   // при каждом обновлении приложения — снова «Консоль»
        if (p.getBoolean(key, false)) return;
        p.edit().putInt("theme4", CONSOLE).putBoolean(key, true).apply();
    }

    static int theme(Context c) {
        return c.getSharedPreferences("look", 0).getInt("theme4", CONSOLE); // 3.4: по умолчанию — «Консоль»
    }

    /** Тёмный интерфейс (системные диалоги — тёмные). */
    static boolean dark(Context c) {
        return theme(c) != CLASSIC;
    }

    static void set(Context c, String lang, int theme) {
        c.getSharedPreferences("look", 0).edit().putString("lang", lang).putInt("theme4", theme).apply();
        apply(c);
    }

    /** Вызывается при старте приложения и после смены настроек. */
    static void apply(Context c) {
        Context app = c.getApplicationContext();
        migrateConsole(app);
        String l = lang(app);
        if (l.isEmpty()) {
            localized = app;
        } else {
            Configuration conf = new Configuration(app.getResources().getConfiguration());
            conf.setLocale(Locale.forLanguageTag(l));
            localized = app.createConfigurationContext(conf);
        }
        if (Flags.PRODIT) {   // Production IT: тёмная, фиолетовый акцент prod-it.com, форма «Консоли» (без теней)
            palette(Ui.CONSOLE, "#5E27D4", Color.WHITE, "#F1F0F7", "#1A1928", "#9C99B5", "#12111C", "#2F2C4A");
            return;
        }
        switch (theme(app)) {
            case DARK:
                palette(Ui.BRUTAL, "#FFE500", Color.BLACK, "#EDEDED", "#1E1E1E", "#9A9A9A", "#121212", "#3A3A3A");
                break;
            case BP: // «Неон»: чёрный фон, панели #1A1A1A, фиолетовый #7C88FC
                palette(Ui.BP, "#7C88FC", Color.parseColor("#0B0B14"), "#F5F5F7", "#1A1A1A", "#9A9AA3", "#000000", "#2C2C2C");
                break;
            case CONSOLE: // 3.4 «Консоль» (Hallmark Console, графит): тёплый янтарь ≤5% экрана, без теней и свечения
                palette(Ui.CONSOLE, "#F7B83D", Color.parseColor("#1C0E00"), "#ECEFF2", "#15191C", "#9DA2A8", "#0D1013", "#2D3135");
                break;
            case GLASS: // «Аврора 3D»: фон — three.js-сцена (GlassBg), карточки — стекло
                palette(Ui.GLASS, "#7C88FC", Color.WHITE, "#FFFFFF", "#26FFFFFF", "#C9C9E6", "#00000000", "#59FFFFFF");
                break;
            default:
                palette(Ui.BRUTAL, "#FFE500", Color.BLACK, "#000000", "#FFFFFF", "#666666", "#FAFAF5", "#2C2C2C");
        }
    }

    private static void palette(int style, String accent, int onAccent, String ink, String card, String gray, String bg, String line) {
        Ui.STYLE = style;
        Ui.YELLOW = Color.parseColor(accent);
        Ui.ON_ACCENT = onAccent;
        Ui.BLACK = Color.parseColor(ink);
        Ui.WHITE = (int) Long.parseLong(card.substring(1).length() == 8 ? card.substring(1) : "FF" + card.substring(1), 16);
        Ui.GRAY = Color.parseColor(gray);
        Ui.BG = (int) Long.parseLong(bg.substring(1).length() == 8 ? bg.substring(1) : "FF" + bg.substring(1), 16);
        Ui.LINE = (int) Long.parseLong(line.substring(1).length() == 8 ? line.substring(1) : "FF" + line.substring(1), 16);
    }

    /** Фон окна для темы «стекло»: глубокий градиент BP со светящимися пятнами. */
    static android.graphics.drawable.Drawable glassBackground() {
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#1B1446"), Color.parseColor("#2A2F8F"), Color.parseColor("#0E0E22")});
        android.graphics.drawable.GradientDrawable glow1 = new android.graphics.drawable.GradientDrawable();
        glow1.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glow1.setColors(new int[]{0x887C88FC, 0x007C88FC});
        glow1.setGradientRadius(900);
        glow1.setGradientCenter(0.15f, 0.2f);
        android.graphics.drawable.GradientDrawable glow2 = new android.graphics.drawable.GradientDrawable();
        glow2.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glow2.setColors(new int[]{0x66FF8562, 0x00FF8562});
        glow2.setGradientRadius(800);
        glow2.setGradientCenter(0.9f, 0.85f);
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{base, glow1, glow2});
    }

    /** Ресурсы на выбранном языке (для Ui.t). */
    static Context res() {
        return localized != null ? localized : App.instance;
    }

    static String langTitle(String l) {
        switch (l) {
            case "en": return "English";
            case "ru": return "Русский";
            case "sr": return "Српски";
            case "sr-Latn": return "Srpski (latinica)";
            default: return Ui.t(R.string.lang_system);
        }
    }

    static String themeTitle(int t) {
        return Ui.t(t == DARK ? R.string.theme_dark : t == BP ? R.string.theme_bp : t == GLASS ? R.string.theme_glass
                : t == CONSOLE ? R.string.theme_console : R.string.theme_classic);
    }
}
