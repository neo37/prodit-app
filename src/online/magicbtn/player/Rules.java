package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;

/** Правила публикации (чат, загрузка видео): показываются один раз, без согласия публиковать нельзя. */
final class Rules {

    private Rules() {
    }

    static void ensure(final Activity a, final Runnable then) {
        if (a.getSharedPreferences("social", 0).getBoolean("rules_ok", false)) {
            then.run();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.rules_title))
                .setMessage(Ui.t(R.string.rules_text))
                .setPositiveButton(Ui.t(R.string.rules_accept), (d, w) -> {
                    a.getSharedPreferences("social", 0).edit().putBoolean("rules_ok", true).apply();
                    then.run();
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }
}
