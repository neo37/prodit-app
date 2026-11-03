package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Диалоги комментариев и жалоб — общие для плеера и офлайн-галереи. */
final class SocialUi {

    private SocialUi() {
    }

    /** Комментарии к файлу: список (онлайн — с сервера, офлайн — из кэша) и поле ввода. */
    static void comments(final Activity a, final Social.Ref r, final Runnable onChange) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 16);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(a, 260)));
        final EditText name = new EditText(a);
        name.setHint(Ui.t(R.string.your_name));
        name.setSingleLine(true);
        name.setText(Social.name(a));
        box.addView(name);
        final EditText text = new EditText(a);
        text.setHint(Ui.t(R.string.comment_hint));
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setMaxLines(4);
        box.addView(text);
        final TextView loading = new TextView(a);
        loading.setText(Ui.t(R.string.loading));
        list.addView(loading);

        if (r.local()) {   // видео с телефона: комментарии видите только вы
            TextView hint = new TextView(a);
            hint.setText(Ui.t(R.string.local_social_hint));
            hint.setTextColor(Ui.GRAY);
            hint.setTextSize(12);
            box.addView(hint, 0);
        }
        AlertDialog.Builder db = new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.comments_title))
                .setView(box)
                .setPositiveButton(Ui.t(R.string.send), null)
                .setNegativeButton(Ui.t(R.string.close), null);
        if (!r.local()) db.setNeutralButton(Ui.t(R.string.report), (x, y) -> report(a, r.api, r.key, "file", r.folder + "/" + r.file));
        final AlertDialog d = db.create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = text.getText().toString().trim();
            if (t.isEmpty()) return;
            Social.setName(a, name.getText().toString().trim());
            Social.addComment(a, r, t);
            text.setText("");
            fill(a, list, MediaDb.get(a).comments(r.skey()), r);
            if (onChange != null) onChange.run();
        });
        new Thread(() -> {
            final List<String[]> items = Social.comments(a, r);
            a.runOnUiThread(() -> {
                fill(a, list, items, r);
                if (onChange != null) onChange.run();
            });
        }).start();
    }

    private static void fill(final Activity a, final LinearLayout list, final List<String[]> all, final Social.Ref r) {
        list.removeAllViews();
        final List<String[]> items = new java.util.ArrayList<>();
        for (String[] it : all) {
            boolean mine = "1".equals(it[3]);
            if (mine || it.length < 5 || !Block.blocked(a, it[4])) items.add(it); // заблокированные авторы скрыты
        }
        if (items.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(Ui.t(R.string.no_comments));
            t.setTextColor(Ui.GRAY);
            list.addView(t);
        }
        for (final String[] it : items) {
            TextView who = new TextView(a);
            who.setText(it[0] + ("1".equals(it[3]) ? " · " + Ui.t(R.string.you) : "")
                    + (it[2] != null && it[2].length() >= 16 ? "  " + it[2].substring(0, 16).replace('T', ' ') : ""));
            who.setTypeface(Typeface.DEFAULT_BOLD);
            who.setTextSize(13);
            who.setPadding(0, Ui.dp(a, 8), 0, 0);
            list.addView(who);
            TextView body = new TextView(a);
            body.setText(it[1]);
            body.setTextSize(15);
            body.setTextColor(Ui.BLACK);
            list.addView(body);
            // долгое нажатие на чужой комментарий: пожаловаться / заблокировать автора
            if (!"1".equals(it[3]) && it.length > 5) {
                final String[] c = it;
                // личка — только у авторов с magicbtn.online (номер автора у других серверов свой)
                final boolean dm = ChatActivity.CHAT_API.equals(r.api) && c[4] != null && c[4].matches("[a-f0-9]{16}");
                android.view.View.OnLongClickListener menu = v -> {
                    String[] acts = dm ? new String[]{"✉ " + Ui.t(R.string.dm_write), "🚩 " + Ui.t(R.string.report), "⛔ " + Ui.t(R.string.block_author, c[0])}
                            : new String[]{"🚩 " + Ui.t(R.string.report), "⛔ " + Ui.t(R.string.block_author, c[0])};
                    new AlertDialog.Builder(a).setItems(acts, (d, ww) -> {
                        int w = dm ? ww - 1 : ww;
                        if (w < 0) a.startActivity(ChatActivity.dm(a, c[4], c[0]));
                        else if (w == 0) report(a, r.api, r.key, "comment", c[5]);
                        else {
                            Block.block(a, c[4], c[0]);
                            Toast.makeText(a, Ui.t(R.string.blocked_done), Toast.LENGTH_SHORT).show();
                            fill(a, list, all, r);
                        }
                    }).show();
                    return true;
                };
                who.setOnLongClickListener(menu);
                body.setOnLongClickListener(menu);
            }
        }
    }

    /** Жалоба: от 3 разных людей контент скрывается на сервере. */
    static void report(final Activity a, final String api, final String key, final String kind, final String ref) {
        final String[] reasons = {Ui.t(R.string.reason_illegal), Ui.t(R.string.reason_sexual),
                Ui.t(R.string.reason_violence), Ui.t(R.string.reason_spam), Ui.t(R.string.reason_other)};
        new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.report_title))
                .setItems(reasons, (d, w) -> {
                    Social.report(a, api, key, kind, ref, reasons[w]);
                    Toast.makeText(a, Ui.t(R.string.report_sent), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }
}
