package online.magicbtn.player;

import android.app.Activity;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Пароль на вход — своя цифровая клавиатура в стиле приложения + отпечаток пальца.
 * mode: unlock — вход (назад закрывает приложение); set — задать/сменить (ввод, повтор); off — снять (ввод текущего).
 * Содержимое не попадает в скриншоты и «Недавние».
 */
public class LockActivity extends Activity {

    private static final int MIN = 4, MAX = 8;

    private String mode;
    private final StringBuilder pin = new StringBuilder();
    private String first; // для mode=set: первый ввод
    private TextView title, dots, err;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "unlock";
        // смена пароля — сначала текущий
        if ("set".equals(mode) && Lock.enabled(this) && !getIntent().getBooleanExtra("verified", false)) mode = "verify_set";

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(Ui.YELLOW);
        int p = Ui.dp(this, 24);
        col.setPadding(p, Ui.dp(this, 48), p, p);

        TextView logo = new TextView(this);
        logo.setText("🔒");
        logo.setTextSize(44);
        logo.setGravity(Gravity.CENTER);
        col.addView(logo);
        title = new TextView(this);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.ON_ACCENT);
        title.setGravity(Gravity.CENTER);
        col.addView(title);
        dots = new TextView(this);
        dots.setTextSize(30);
        dots.setTextColor(Ui.ON_ACCENT);
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(0.3f);
        dots.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        col.addView(dots);
        err = new TextView(this);
        err.setTextColor(Ui.ON_ACCENT);
        err.setGravity(Gravity.CENTER);
        err.setMinHeight(Ui.dp(this, 22));
        col.addView(err);

        // клавиатура 3×4
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"},
                {canBio() ? "👆" : "", "0", "⌫"}};
        for (String[] row : keys) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER);
            for (final String k : row) {
                TextView b = Ui.button(this, k, android.graphics.Color.WHITE);
                b.setTextColor(android.graphics.Color.BLACK);
                b.setTextSize(26);
                b.setVisibility(k.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                b.setOnClickListener(v -> key(k));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 68));
                lp.setMargins(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
                r.addView(b, lp);
            }
            col.addView(r);
        }
        TextView ok = Ui.button(this, Ui.t("unlock".equals(mode) ? R.string.lock_unlock : R.string.lock_save), android.graphics.Color.BLACK);
        ok.setTextColor(Ui.YELLOW);
        ok.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(Ui.dp(this, 300), -2);
        op.topMargin = Ui.dp(this, 14);
        col.addView(ok, op);
        if (!"unlock".equals(mode)) {
            TextView cancel = new TextView(this);
            cancel.setText(Ui.t(R.string.cancel));
            cancel.setTextColor(Ui.ON_ACCENT);
            cancel.setPadding(0, Ui.dp(this, 18), 0, 0);
            cancel.setOnClickListener(v -> finish());
            col.addView(cancel);
        }
        setContentView(col);
        refresh();
        if (("unlock".equals(mode) || "off".equals(mode)) && Lock.bioEnabled(this) && canBio()) bio();
    }

    private void refresh() {
        int id;
        switch (mode) {
            case "set":
                id = first == null ? R.string.lock_new : R.string.lock_repeat;
                break;
            case "verify_set":
            case "off":
                id = R.string.lock_current;
                break;
            default:
                id = R.string.lock_enter;
        }
        title.setText(Ui.t(id));
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < Math.max(MIN, pin.length()); i++) d.append(i < pin.length() ? "●" : "○");
        dots.setText(d);
    }

    private void key(String k) {
        err.setText("");
        if ("⌫".equals(k)) {
            if (pin.length() > 0) pin.setLength(pin.length() - 1);
        } else if ("👆".equals(k)) {
            bio();
        } else if (pin.length() < MAX) {
            pin.append(k);
            // вход — проверяем сразу, без кнопки
            if ("unlock".equals(mode) && pin.length() >= MIN && Lock.check(this, pin.toString())) {
                done();
                return;
            }
        }
        refresh();
    }

    private void submit() {
        String p = pin.toString();
        pin.setLength(0);
        switch (mode) {
            case "unlock":
                if (Lock.check(this, p)) done();
                else wrong();
                break;
            case "verify_set":
                if (Lock.check(this, p)) mode = "set";
                else wrong();
                break;
            case "off":
                if (Lock.check(this, p)) {
                    Lock.clear(this);
                    Toast.makeText(this, Ui.t(R.string.lock_removed), Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                wrong();
                break;
            case "set":
                if (p.length() < MIN) {
                    err.setText(Ui.t(R.string.lock_mismatch));
                } else if (first == null) {
                    first = p;
                } else if (first.equals(p)) {
                    Lock.set(this, p);
                    App.unlocked = true;
                    Toast.makeText(this, Ui.t(R.string.lock_set), Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                } else {
                    first = null;
                    err.setText(Ui.t(R.string.lock_mismatch));
                }
                break;
        }
        refresh();
    }

    private void wrong() {
        err.setText(Ui.t(R.string.lock_wrong));
        dots.animate().translationX(20).setDuration(60).withEndAction(() ->
                dots.animate().translationX(-20).setDuration(60).withEndAction(() ->
                        dots.animate().translationX(0).setDuration(60)));
    }

    private void done() {
        App.unlocked = true;
        finish();
    }

    // ---------- отпечаток ----------

    private boolean canBio() {
        return Build.VERSION.SDK_INT >= 28 && getPackageManager().hasSystemFeature("android.hardware.fingerprint");
    }

    private void bio() {
        if (Build.VERSION.SDK_INT < 28) return;
        BiometricPrompt bp = new BiometricPrompt.Builder(this)
                .setTitle(Ui.t(R.string.app_name))
                .setSubtitle(Ui.t(R.string.lock_bio_prompt))
                .setNegativeButton(Ui.t(R.string.lock_use_pin), getMainExecutor(), (d, w) -> { })
                .build();
        bp.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                if ("off".equals(mode)) {
                    Lock.clear(LockActivity.this);
                    finish();
                } else {
                    done();
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if ("unlock".equals(mode)) finishAffinity();
        else finish();
    }
}
