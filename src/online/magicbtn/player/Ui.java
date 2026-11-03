package online.magicbtn.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Стиль приложения. Цвета и форма задаются темой (Look.apply):
 * классическая — жёлтый акцент, рамка 2px и жёсткая тень; тёмная — то же на тёмном;
 * Business Pad — фиолетовый акцент, скругления, тонкая линия; стекло 3D — полупрозрачные карточки с бликом и мягкой тенью.
 */
final class Ui {
    static final int BRUTAL = 0, BP = 1, GLASS = 2, CONSOLE = 3;
    /** YELLOW — акцент темы (жёлтый / фиолетовый), ON_ACCENT — текст на нём. */
    static int YELLOW = Color.parseColor("#FFE500");
    static int ON_ACCENT = Color.BLACK;
    /** BLACK — текст и рамки, WHITE — карточки, BG — фон, LINE — тонкие линии. */
    static int BLACK = Color.BLACK;
    static int WHITE = Color.WHITE;
    static int GRAY = Color.parseColor("#666666");
    static int BG = Color.parseColor("#FAFAF5");
    static int LINE = Color.parseColor("#2C2C2C");
    static int STYLE = BRUTAL;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Карточка/кнопка в стиле темы. shadowDp — «высота» (у классической — жёсткая тень вправо-вниз). */
    static Drawable box(Context c, int fill, int shadowDp) {
        if (STYLE == CONSOLE) {   // «Консоль»: глубина — светлотой, без теней; линия 1dp, радиус 10dp
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(dp(c, 10));
            if (fill != YELLOW) g.setStroke(dp(c, 1), LINE);
            return g;
        }
        if (STYLE == BP) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(dp(c, 12));
            g.setStroke(dp(c, 1), fill == YELLOW ? fill : LINE);
            return g;
        }
        if (STYLE == GLASS) {
            int r = dp(c, 22); // скругления как в iOS
            // мягкая тень снизу, стекло (полупрозрачная заливка + светлый край) и блик сверху — «3D»
            GradientDrawable shadow = new GradientDrawable();
            shadow.setColor(0x33000000);
            shadow.setCornerRadius(r);
            GradientDrawable glass = new GradientDrawable();
            glass.setColor(fill == YELLOW ? (0xCC000000 | (fill & 0xFFFFFF)) : 0x26FFFFFF);
            glass.setCornerRadius(r);
            glass.setStroke(dp(c, 1), 0x59FFFFFF);
            GradientDrawable shine = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x40FFFFFF, 0x00FFFFFF});
            shine.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            LayerDrawable ld = new LayerDrawable(new Drawable[]{shadow, glass, shine});
            int s = dp(c, Math.max(1, shadowDp));
            ld.setLayerInset(0, 0, s, 0, 0);
            ld.setLayerInset(1, 0, 0, 0, s);
            ld.setLayerInset(2, dp(c, 1), dp(c, 1), dp(c, 1), dp(c, 24));
            return ld;
        }
        int s = dp(c, shadowDp);
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(BLACK);
        GradientDrawable face = new GradientDrawable();
        face.setColor(fill);
        face.setStroke(dp(c, 2), BLACK);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{shadow, face});
        ld.setLayerInset(0, s, s, 0, 0);
        ld.setLayerInset(1, 0, 0, s, s);
        return ld;
    }

    static Drawable button(Context c, int fill) {
        if (STYLE == CONSOLE) {   // кнопки — радиус 6dp, отклик волной цвета текста (≈12%)
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(dp(c, 6));
            if (fill != YELLOW) g.setStroke(dp(c, 1), LINE);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(dp(c, 6));
            int ripple = (fill == YELLOW ? ON_ACCENT : BLACK) & 0x00FFFFFF | 0x1F000000;
            return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), g, mask);
        }
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, box(c, fill, 0));
        sl.addState(new int[]{}, box(c, fill, 3));
        return sl;
    }

    static TextView button(Context c, String text, int fill) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(fill == YELLOW ? ON_ACCENT : BLACK); // на акценте — свой цвет текста
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(button(c, fill));
        if (STYLE == CONSOLE) {   // ровные отступы и высота пальца: ≥44dp
            b.setTypeface(Fonts.medium(c));
            b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
            b.setMinHeight(dp(c, 44));
            b.setMinWidth(dp(c, 44));
        } else {
            b.setPadding(dp(c, 14), dp(c, 10), dp(c, 17), dp(c, 13));
        }
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** targetSdk 35+ рисует под системными панелями — отодвигаем контент на их размер (и на высоту клавиатуры). */
    static void fitSystemBars(android.app.Activity a) {
        android.view.View content = a.findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, in) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                // клавиатура: в режиме «под панелями» adjustResize не работает — поднимаем низ экрана на её высоту
                int ime = in.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                v.setPadding(i.left, i.top, i.right, Math.max(i.bottom, ime));
            } else {
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            }
            return in;
        });
        content.requestApplyInsets();
    }

    /** Строка из ресурсов на языке устройства (en по умолчанию, ru, sr, sr-Latn). */
    static String t(int id, Object... args) {
        return Look.res().getString(id, args);
    }

    static String bytes(long b) {
        boolean ru = "ru".equals(java.util.Locale.getDefault().getLanguage());
        if (b < 1024) return b + (ru ? " Б" : " B");
        if (b < 1024 * 1024) return (b / 1024) + (ru ? " КБ" : " KB");
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, ru ? "%.1f МБ" : "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.ROOT, ru ? "%.2f ГБ" : "%.2f GB", b / 1073741824.0);
    }
}
