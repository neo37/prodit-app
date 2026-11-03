package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * «Мой источник»: свой источник на magicbtn.online — до 3 видео (до 3 минут) и до 10 фото с телефона.
 * Появляется на главном экране, им можно поделиться в чате, его можно удалить целиком.
 */
public class MySourceActivity extends Activity {

    private static final int REQ_PICK = 11;

    private LinearLayout body;
    private TextView info;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, Ui.dp(this, 12), p, p);
        sv.addView(col);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.button(this, "‹", Ui.WHITE);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(Ui.t(R.string.my_source));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        col.addView(header);

        info = new TextView(this);
        info.setTextColor(Ui.GRAY);
        info.setTextSize(13);
        info.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        info.setText(Ui.t(R.string.my_source_info, Uploads.MAX_FILES, Uploads.MAX_SECONDS / 60, Uploads.MAX_PHOTOS));
        col.addView(info);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        col.addView(body);
        setContentView(sv);
        Ui.fitSystemBars(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();
        final JSONObject sp = Uploads.space(this);
        if (sp == null) {
            final EditText name = new EditText(this);
            name.setHint(Ui.t(R.string.src_name));
            name.setSingleLine(true);
            body.addView(name);
            TextView create = Ui.button(this, Ui.t(R.string.my_source_create), Ui.YELLOW);
            create.setOnClickListener(v -> Rules.ensure(this, () -> new Thread(() -> {
                try {
                    Uploads.ensureSpace(this, name.getText().toString().trim());
                } catch (Exception e) {
                    toast(Ui.t(R.string.error_prefix, e.getMessage()));
                }
                main.post(this::render);
            }).start()));
            body.addView(create, lp(12));
            return;
        }
        TextView nm = new TextView(this);
        nm.setText("🎬 " + sp.optString("name"));
        nm.setTextSize(18);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(Ui.BLACK);
        body.addView(nm);
        final LinearLayout files = new LinearLayout(this);
        files.setOrientation(LinearLayout.VERTICAL);
        body.addView(files);
        TextView up = Ui.button(this, Ui.t(R.string.my_source_upload), Ui.YELLOW);
        up.setOnClickListener(v -> Rules.ensure(this, this::pick));
        body.addView(up, lp(12));
        TextView share = Ui.button(this, Ui.t(R.string.my_source_share), Ui.WHITE);
        share.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)));
        body.addView(share, lp(10));
        TextView del = Ui.button(this, "🗑 " + Ui.t(R.string.source_delete), Ui.WHITE);
        del.setOnClickListener(v -> deleteSource(this, () -> main.post(this::render)));
        body.addView(del, lp(22));
        loadFiles(sp, files);
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = Ui.dp(this, top);
        return l;
    }

    /** Удалить свой источник для всех: файлы, доступ по ключу и ссылки в чате. */
    static void deleteSource(final Activity a, final Runnable done) {
        final JSONObject sp = Uploads.space(a);
        if (sp == null) return;
        new AlertDialog.Builder(a)
                .setMessage(Ui.t(R.string.source_delete_confirm))
                .setPositiveButton(Ui.t(R.string.remove), (d, w) -> new Thread(() -> {
                    try {
                        JSONObject o = Social.call(a, ChatActivity.CHAT_API, sp.optString("key"), "action=space_delete", "POST",
                                new JSONObject().put("folder", sp.optString("folder")).toString());
                        if (!o.optBoolean("ok") && !"not found".equals(o.optString("error")))
                            throw new Exception(o.optString("error"));
                        a.getSharedPreferences("my_source", 0).edit().remove("space").apply();
                        List<Sources.Source> l = Sources.all(a);
                        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).key.equals(sp.optString("key"))) l.remove(i);
                        Sources.save(a, l, 0);
                        a.runOnUiThread(() -> Toast.makeText(a, Ui.t(R.string.source_deleted), Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        a.runOnUiThread(() -> Toast.makeText(a, Ui.t(R.string.error_prefix, e.getMessage()), Toast.LENGTH_LONG).show());
                    }
                    if (done != null) done.run();
                }).start())
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }

    private void loadFiles(final JSONObject sp, final LinearLayout files) {
        new Thread(() -> {
            try {
                JSONObject o = Social.call(this, ChatActivity.CHAT_API, sp.optString("key"),
                        "action=list&folder=" + Uri.encode(sp.optString("folder")), "GET", null);
                final JSONArray a = o.optJSONArray("items");
                main.post(() -> {
                    files.removeAllViews();
                    int videos = 0, photos = 0;
                    for (int i = 0; a != null && i < a.length(); i++) {
                        final JSONObject it = a.optJSONObject(i);
                        boolean img = "image".equals(it.optString("type"));
                        if (img) photos++;
                        else videos++;
                        LinearLayout row = new LinearLayout(this);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        TextView t = new TextView(this);
                        t.setText((img ? "🖼 " : "▶ ") + it.optString("fname") + " · " + Ui.bytes(it.optLong("size")));
                        t.setTextColor(Ui.BLACK);
                        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                        TextView del = Ui.button(this, "🗑", Ui.WHITE);
                        del.setOnClickListener(v -> deleteFile(sp, it.optString("fname")));
                        row.addView(del);
                        files.addView(row, lp(8));
                    }
                    info.setText(Ui.t(R.string.my_source_count2, videos, Uploads.MAX_FILES, photos, Uploads.MAX_PHOTOS,
                            Uploads.MAX_SECONDS / 60));
                });
            } catch (Exception e) {
                toast(Ui.t(R.string.chat_offline));
            }
        }).start();
    }

    private void deleteFile(final JSONObject sp, final String file) {
        new AlertDialog.Builder(this)
                .setMessage(Ui.t(R.string.delete_confirm))
                .setPositiveButton(Ui.t(R.string.remove), (d, w) -> new Thread(() -> {
                    try {
                        Social.call(this, ChatActivity.CHAT_API, sp.optString("key"), "action=delete", "POST",
                                new JSONObject().put("folder", sp.optString("folder")).put("file", file).toString());
                    } catch (Exception e) {
                        toast(Ui.t(R.string.chat_offline));
                    }
                    main.post(this::render);
                }).start())
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }

    private void pick() {
        startActivityForResult(Uploads.pickIntent(), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        final Uploads.Picked f = Uploads.check(this, u);
        if (f.error != null) {
            toast(f.error);
            return;
        }
        final JSONObject sp = Uploads.space(this);
        if (sp == null) return;
        UploadDialog.run(this, sp, u, f, r -> {
            toast(Ui.t(R.string.uploaded));
            render();
        });
    }

    private void toast(final String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    /** Диалог с прогрессом загрузки (используется и в чате). */
    static final class UploadDialog {
        interface Done {
            void ok(JSONObject r);
        }

        static void run(final Activity a, final JSONObject sp, final Uri u, final Uploads.Picked f, final Done done) {
            final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            final TextView label = new TextView(a);
            label.setText(f.name);
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = Ui.dp(a, 24);
            box.setPadding(p, p / 2, p, 0);
            box.addView(label);
            box.addView(bar);
            final AlertDialog d = new AlertDialog.Builder(a).setTitle(Ui.t(R.string.uploading)).setView(box)
                    .setCancelable(false).show();
            new Thread(() -> {
                try {
                    final JSONObject r = Uploads.upload(a, sp, u, f, pct -> a.runOnUiThread(() -> bar.setProgress(pct)));
                    a.runOnUiThread(() -> {
                        d.dismiss();
                        done.ok(r);
                    });
                } catch (final Exception e) {
                    a.runOnUiThread(() -> {
                        d.dismiss();
                        Toast.makeText(a, Ui.t(R.string.error_prefix, e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }
    }
}
