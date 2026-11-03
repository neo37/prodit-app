package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;

/** Перед любой публикацией в ОБЩИЙ чат — подтверждение: это увидят все пользователи приложения. */
final class ChatConfirm {

    private ChatConfirm() {
    }

    static void ask(Activity a, String what, Runnable go) {
        String preview = what == null ? "" : what.trim();
        if (preview.length() > 160) preview = preview.substring(0, 160) + "…";
        new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.chat_confirm_title))
                .setMessage(Ui.t(R.string.chat_confirm_text) + (preview.isEmpty() ? "" : "\n\n«" + preview + "»"))
                .setPositiveButton(Ui.t(R.string.chat_confirm_yes), (d, w) -> go.run())
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }
}
