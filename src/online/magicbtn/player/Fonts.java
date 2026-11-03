package online.magicbtn.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 3.4, тема «Консоль»: шрифт Onest (SIL OFL, assets/fonts — кириллица и латиница) вместо системного.
 * Экраны собраны кодом, поэтому шрифт ставится обходом дерева видов при показе экрана и при добавлении новых
 * (сообщения чата, списки). Жирный → Onest Medium (спокойнее), обычный → Regular; текст мельче 12 sp — до 12.
 */
final class Fonts {

    private static Typeface regular, medium;
    private static final int DONE = 0x7f0f0f01;   // тег «уже обработан» (свой id, не пересекается с ресурсами)

    private Fonts() {
    }

    /** 3.9: значки Material Symbols и подписи для TalkBack — во всех темах. */
    static boolean on() {
        return true;
    }

    /** Шрифт Onest — только в «Консоли» (у остальных тем свой характер шрифта). */
    private static boolean fontOn() {
        return Ui.STYLE == Ui.CONSOLE;
    }

    private static void load(Context c) {
        if (regular != null) return;
        try {
            regular = Typeface.createFromAsset(c.getAssets(), "fonts/Onest-Regular.ttf");
            medium = Typeface.createFromAsset(c.getAssets(), "fonts/Onest-Medium.ttf");
        } catch (Exception e) {
            regular = Typeface.DEFAULT;
            medium = Typeface.DEFAULT_BOLD;
        }
    }

    private static void iconify(TextView t) {
        CharSequence s = t.getText();
        if (Icons.done(s)) return;
        CharSequence r = Icons.iconify(t.getContext(), s);
        if (r != null) t.setText(r, TextView.BufferType.SPANNABLE);
    }

    static Typeface medium(Context c) {
        load(c);
        return medium;
    }

    /** Подключить к экрану: сразу и при каждой перестройке (новые строки). */
    static void attach(final Activity a) {
        attach(a.getWindow());
    }

    /** Любое окно (экран, диалог меню ☰). */
    static void attach(android.view.Window w) {
        if (!on() || w == null) return;
        load(w.getContext());
        final View root = w.getDecorView();
        apply(root);
        if (root.getTag(DONE) == null) {
            root.setTag(DONE, Boolean.TRUE);
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (on()) apply(root);
            });
        }
    }

    private static final int WATCHED = 0x7f0f0f02;

    static void apply(View v) {
        if (v instanceof TextView) {
            final TextView t = (TextView) v;
            // значки вместо эмодзи (не в полях ввода) — и заново при каждой смене текста
            if (!(t instanceof android.widget.EditText)) {
                A11y.describe(t);
                iconify(t);
                if (t.getTag(WATCHED) == null) {
                    t.setTag(WATCHED, Boolean.TRUE);
                    t.addTextChangedListener(new android.text.TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                        }

                        @Override
                        public void onTextChanged(CharSequence s, int a, int b, int c) {
                        }

                        @Override
                        public void afterTextChanged(android.text.Editable e) {
                            if (on()) t.post(() -> iconify(t));
                        }
                    });
                }
            }
            Typeface cur = t.getTypeface();
            if (fontOn() && cur != regular && cur != medium) {
                boolean bold = cur != null && cur.isBold();
                t.setTypeface(bold ? medium : regular);
                float sp = t.getTextSize() / t.getResources().getDisplayMetrics().scaledDensity;
                if (sp < 11.5f) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }
}
