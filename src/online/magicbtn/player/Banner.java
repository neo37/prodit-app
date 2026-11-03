package online.magicbtn.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 3.4: плашка «пришло сообщение» поверх текущего экрана (приложение открыто): выезжает сверху, тап — открыть
 * переписку, свайп/тап по ✕ или 5 с — скрыть. Работает без пушей: её показывают проверки лички и общего чата.
 */
final class Banner {

    private static final String TAG = "msg_banner";

    private Banner() {
    }

    static void show(final Activity a, final String title, final String text, final Intent open) {
        if (a == null || a.isFinishing() || a instanceof LockActivity || a instanceof IncomingCallActivity) return;
        a.runOnUiThread(() -> {
            ViewGroup root = (ViewGroup) a.getWindow().getDecorView();
            View old = root.findViewWithTag(TAG);
            if (old != null) root.removeView(old);

            LinearLayout box = new LinearLayout(a);
            box.setTag(TAG);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            int p = Ui.dp(a, 14);
            box.setPadding(p, Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF01E1E24);
            bg.setCornerRadius(Ui.dp(a, 16));
            bg.setStroke(Ui.dp(a, 2), 0xFF7C88FC);
            box.setBackground(bg);
            box.setElevation(Ui.dp(a, 12));

            TextView t = new TextView(a);
            t.setTextColor(Color.WHITE);
            t.setTextSize(15);
            t.setMaxLines(3);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String body = text == null ? "" : text.replace('\n', ' ');
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title + "\n" + body);
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, title.length(), 0);
            t.setText(sb);
            box.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            TextView x = new TextView(a);
            x.setText("✕");
            x.setTextColor(0xFFB0B0B8);
            x.setTextSize(18);
            x.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
            box.addView(x);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            int m = Ui.dp(a, 10);
            lp.setMargins(m, Ui.dp(a, 34), m, 0);
            root.addView(box, lp);

            final Runnable hide = () -> box.animate().translationY(-Ui.dp(a, 160)).alpha(0f).setDuration(220)
                    .withEndAction(() -> root.removeView(box)).start();
            box.setTranslationY(-Ui.dp(a, 160));
            box.setAlpha(0f);
            box.animate().translationY(0).alpha(1f).setDuration(260).start();
            box.postDelayed(hide, 5000);
            x.setOnClickListener(v -> {
                box.removeCallbacks(hide);
                hide.run();
            });
            box.setOnClickListener(v -> {
                box.removeCallbacks(hide);
                root.removeView(box);
                if (open != null) a.startActivity(open);
            });
        });
    }
}
