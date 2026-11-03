package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 3.4: «🎞 Мои просмотры» — записи слайдшоу (⏺ в плеере). У каждой: «Собрать видео» (Render — один ролик
 * с учётом перемоток, зума и 👎), «Открыть» готовый ролик, долгое нажатие — переименовать / удалить.
 */
public class RecordingsActivity extends Activity {

    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 8));
        TextView back = Ui.button(this, "‹", Ui.WHITE);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(Ui.t(R.string.rec_title));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(header);
        status = new TextView(this);
        status.setTextColor(Ui.GRAY);
        status.setTextSize(13);
        status.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 8));
        status.setText(Ui.t(R.string.rec_hint));
        col.addView(status);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(p, 0, p, p);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(col);
        Ui.fitSystemBars(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private static String dur(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void render() {
        list.removeAllViews();
        java.util.List<Recorder.Rec> recs = Recorder.list(this);
        if (recs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(Ui.t(R.string.rec_empty));
            t.setTextColor(Ui.GRAY);
            t.setTextSize(15);
            t.setPadding(0, Ui.dp(this, 24), 0, 0);
            list.addView(t);
            return;
        }
        for (final Recorder.Rec r : recs) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.box(this, Ui.WHITE, 2));
            int cp = Ui.dp(this, 14);
            card.setPadding(cp, cp, cp, cp);
            TextView name = new TextView(this);
            String when = DateFormat.format("dd.MM.yyyy HH:mm", r.created).toString();
            name.setText((r.title == null || r.title.isEmpty() ? Ui.t(R.string.slideshow) : r.title) + " · " + when);
            name.setTextSize(16);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(Ui.BLACK);
            card.addView(name);
            final TextView meta = new TextView(this);
            meta.setText(Ui.t(R.string.rec_meta, r.clips, dur(r.durationMs), r.excluded));
            meta.setTextSize(13);
            meta.setTextColor(Ui.GRAY);
            meta.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
            card.addView(meta);
            LinearLayout row = new LinearLayout(this);
            final TextView make = Ui.button(this, Render.running == r.id ? Ui.t(R.string.rec_making, Render.pct)
                    : Ui.t(R.string.rec_make), Ui.YELLOW);
            make.setTextSize(14);
            make.setOnClickListener(v -> make(r, make));
            row.addView(make);
            if (r.output != null) {
                TextView open = Ui.button(this, Ui.t(R.string.rec_open), Ui.WHITE);
                open.setTextSize(14);
                open.setOnClickListener(v -> open(Uri.parse(r.output)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.leftMargin = Ui.dp(this, 8);
                row.addView(open, lp);
            }
            card.addView(row);
            card.setOnLongClickListener(v -> {
                menu(r);
                return true;
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.bottomMargin = Ui.dp(this, 12);
            list.addView(card, cl);
        }
    }

    private void make(final Recorder.Rec r, final TextView btn) {
        if (Render.running >= 0) {
            Toast.makeText(this, Ui.t(R.string.rec_busy), Toast.LENGTH_SHORT).show();
            return;
        }
        if (r.clips == 0) {
            Toast.makeText(this, Ui.t(R.string.rec_nothing), Toast.LENGTH_SHORT).show();
            return;
        }
        Notify.askOnce(this);
        final int nid = (int) (9000 + r.id);
        final String t = r.title == null || r.title.isEmpty() ? Ui.t(R.string.slideshow) : r.title;
        btn.setText(Ui.t(R.string.rec_making, 0));
        Render.start(this, r.id, t, new Render.Listener() {
            @Override
            public void progress(int pct) {
                btn.setText(Ui.t(R.string.rec_making, pct));
                Notify.upload(getApplicationContext(), nid, "🎬 " + t, pct);
            }

            @Override
            public void done(Uri out, String error) {
                Notify.upload(getApplicationContext(), nid, null, -1);
                if (out == null) {
                    Toast.makeText(getApplicationContext(), Ui.t(R.string.rec_failed, error), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getApplicationContext(), Ui.t(R.string.rec_done), Toast.LENGTH_LONG).show();
                }
                if (!isFinishing()) render();
            }
        });
    }

    private void open(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(this, Ui.t(R.string.error_prefix, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(final Recorder.Rec r) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"✎ " + Ui.t(R.string.rec_rename), "🗑 " + Ui.t(R.string.remove)}, (d, w) -> {
                    if (w == 0) {
                        final EditText e = new EditText(this);
                        e.setText(r.title);
                        new AlertDialog.Builder(this).setTitle(Ui.t(R.string.rec_rename)).setView(e)
                                .setPositiveButton(Ui.t(R.string.save), (x, y) -> {
                                    Recorder.rename(this, r.id, e.getText().toString().trim());
                                    render();
                                })
                                .setNegativeButton(Ui.t(R.string.cancel), null).show();
                    } else {
                        if (Render.running == r.id) Render.cancel();
                        Recorder.delete(this, r.id);
                        render();
                    }
                })
                .show();
    }
}
