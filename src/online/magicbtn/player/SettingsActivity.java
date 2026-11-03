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
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Настройки: пароль и отпечаток, что качать в офлайн по каждому источнику,
 * очередь загрузок (видно, что качается, можно отменить), хранилище, имя в чате.
 */
public class SettingsActivity extends Activity {

    private LinearLayout body;
    private int lastQueue = -1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int n = MediaCache.get(SettingsActivity.this).queue().size();
            if (n != lastQueue) render(); // перерисовываем только когда очередь изменилась
            main.postDelayed(this, 3000);
        }
    };

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
        title.setText(Ui.t(R.string.settings));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        col.addView(header);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        col.addView(body);
        setContentView(sv);
        Ui.fitSystemBars(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    private void render() {
        body.removeAllViews();

        // ---- пароль
        section(Ui.t(R.string.lock_title));
        boolean on = Lock.enabled(this);
        row(on ? Ui.t(R.string.lock_change) : Ui.t(R.string.lock_set_btn), null,
                () -> startActivity(new Intent(this, LockActivity.class).putExtra("mode", "set")));
        if (on) {
            row("👆 " + Ui.t(R.string.lock_bio), Lock.bioEnabled(this) ? "✓" : "—", () -> {
                Lock.setBio(this, !Lock.bioEnabled(this));
                render();
            });
            row(Ui.t(R.string.lock_remove), null,
                    () -> startActivity(new Intent(this, LockActivity.class).putExtra("mode", "off")));
        }

        // ---- уведомления о личке и звонках (значок с числом — тоже через них)
        section(Ui.t(R.string.notif_title));
        if (!Notify.allowed(this)) {
            row("⚠ " + Ui.t(R.string.notif_off_system), Ui.t(R.string.open_settings), () -> startActivity(
                    new Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", getPackageName())));
        }
        row("🔔 " + Ui.t(R.string.notif_dm), Notify.enabled(this) ? "✓" : "—", () -> {
            Notify.setEnabled(this, !Notify.enabled(this));
            if (Notify.enabled(this) && !Notify.allowed(this) && android.os.Build.VERSION.SDK_INT >= 33)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
            render();
        });
        row("💬 " + Ui.t(R.string.notif_channel_chat), Push.chatEnabled(this) ? "✓" : "—", () -> {
            Push.setChatEnabled(this, !Push.chatEnabled(this));
            render();
        });
        note(Ui.t(Push.active(this) ? R.string.notif_hint_push : R.string.notif_hint));

        // ---- видимость в каталоге «Все пользователи»
        section(Ui.t(R.string.directory_title));
        row("👥 " + Ui.t(R.string.directory_show), Contacts.visible(this) ? "✓" : "—", () -> {
            Contacts.setVisible(this, !Contacts.visible(this));
            render();
        });
        note(Ui.t(R.string.directory_hint));

        // ---- что скачивать
        section(Ui.t(R.string.dl_title));
        note(Ui.t(R.string.dl_hint));
        Map<String, String> hosts = new LinkedHashMap<>();
        for (Sources.Source s : Sources.all(this)) if (!s.host().isEmpty()) hosts.put(s.host(), s.name);
        for (String l : MediaDb.get(this).labels()) {
            String h = Dl.host(l);
            if (!hosts.containsKey(h)) hosts.put(h, h);
        }
        for (final Map.Entry<String, String> e : hosts.entrySet()) {
            row(e.getValue(), Dl.title(Dl.policy(this, e.getKey())), () -> {
                String[] opts = {Dl.title(Dl.ALL), Dl.title(Dl.LIKED), Dl.title(Dl.NONE)};
                new AlertDialog.Builder(this).setTitle(e.getValue())
                        .setSingleChoiceItems(opts, Dl.policy(this, e.getKey()), (d, w) -> {
                            Dl.set(this, e.getKey(), w);
                            d.dismiss();
                            render();
                        }).show();
            });
        }

        // ---- очередь
        final MediaCache cache = MediaCache.get(this);
        List<String[]> q = cache.queue();
        lastQueue = q.size();
        section(Ui.t(R.string.dl_queue, q.size()));
        if (q.isEmpty()) note(Ui.t(R.string.dl_queue_empty));
        int shown = 0;
        for (final String[] it : q) {
            if (shown++ >= 30) {
                note("… +" + (q.size() - 30));
                break;
            }
            Uri u = Uri.parse(it[0]);
            String name = u.getQueryParameter("file") != null ? u.getQueryParameter("file") : u.getLastPathSegment();
            row("⬇ " + name, "✕", () -> {
                cache.cancel(it[0]);
                render();
            }).setTag(it[1]);
        }
        if (!q.isEmpty()) row(Ui.t(R.string.dl_queue_clear), null, () -> {
            for (String[] it : cache.queue()) cache.cancel(it[0]);
            render();
        });

        // ---- хранилище
        MediaDb db = MediaDb.get(this);
        section(Ui.t(R.string.storage));
        note(Ui.t(R.string.storage_info, db.count(), Ui.bytes(db.totalBytes())));
        row(Ui.t(R.string.storage_clear), null, () -> new AlertDialog.Builder(this)
                .setMessage(Ui.t(R.string.storage_clear_confirm))
                .setPositiveButton(Ui.t(R.string.remove), (d, w) -> {
                    for (MediaDb.Item it : db.list(null)) db.delete(it.url);
                    render();
                })
                .setNegativeButton(Ui.t(R.string.cancel), null).show());

        // ---- заблокированные авторы (чат и комментарии)
        java.util.Map<String, String> blocked = Block.all(this);
        section(Ui.t(R.string.blocked_title, blocked.size()));
        if (blocked.isEmpty()) note(Ui.t(R.string.blocked_empty));
        for (final java.util.Map.Entry<String, String> e : blocked.entrySet()) {
            row("⛔ " + (e.getValue().isEmpty() ? "—" : e.getValue()), Ui.t(R.string.unblock), () -> {
                Block.unblock(this, e.getKey());
                render();
            });
        }

        // ---- удалить свои данные на серверах (Google Play: Data deletion)
        section(Ui.t(R.string.forget_title));
        note(Ui.t(R.string.forget_note));
        row("🗑 " + Ui.t(R.string.forget_btn), null, () -> new AlertDialog.Builder(this)
                .setTitle(Ui.t(R.string.forget_btn))
                .setMessage(Ui.t(R.string.forget_confirm))
                .setPositiveButton(Ui.t(R.string.remove), (d, w) -> forgetAll())
                .setNegativeButton(Ui.t(R.string.cancel), null).show());

        // ---- имя
        section(Ui.t(R.string.your_name));
        row(Social.name(this).isEmpty() ? "—" : Social.name(this), "✎", () -> {
            final EditText e = new EditText(this);
            e.setSingleLine(true);
            e.setText(Social.name(this));
            new AlertDialog.Builder(this).setTitle(Ui.t(R.string.your_name)).setView(e)
                    .setPositiveButton("OK", (d, w) -> {
                        Social.setName(this, e.getText().toString().trim());
                        render();
                    }).setNegativeButton(Ui.t(R.string.cancel), null).show();
        });
    }

    /** Удалить всё своё: на magicbtn.online и на каждом подключённом источнике с API, затем локальные следы. */
    private void forgetAll() {
        new Thread(() -> {
            java.util.LinkedHashSet<String> apis = new java.util.LinkedHashSet<>();
            apis.add(ChatActivity.CHAT_API);
            for (Sources.Source src : Sources.all(this)) if (src.isApi()) apis.add(src.apiBase());
            int ok = 0;
            for (String api : apis) {
                try {
                    if (Social.call(this, api, "", "action=forget", "POST", "{}").optBoolean("ok")) ok++;
                } catch (Exception ignored) {
                }
            }
            // локально: свой источник, аватар, имя, неотправленные действия
            org.json.JSONObject sp = Uploads.space(this);
            if (sp != null) {
                List<Sources.Source> l = Sources.all(this);
                for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).key.equals(sp.optString("key"))) l.remove(i);
                Sources.save(this, l, 0);
                getSharedPreferences("my_source", 0).edit().clear().apply();
            }
            Profile.localAvatar(this).delete();
            Social.setName(this, "");
            getSharedPreferences("contacts", 0).edit().remove("hello_at").apply();
            for (String[] op : MediaDb.get(this).ops()) MediaDb.get(this).removeOp(op[0]);
            final int done = ok, total = apis.size();
            runOnUiThread(() -> {
                android.widget.Toast.makeText(this, Ui.t(R.string.forget_done, done, total), android.widget.Toast.LENGTH_LONG).show();
                render();
            });
        }).start();
    }

    private void section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(16);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Ui.BLACK);
        v.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 6));
        body.addView(v);
    }

    private void note(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTextColor(Ui.GRAY);
        v.setPadding(0, 0, 0, Ui.dp(this, 6));
        body.addView(v);
    }

    private LinearLayout row(String left, String right, Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.button(this, Ui.WHITE));
        int p = Ui.dp(this, 14);
        r.setPadding(p, Ui.dp(this, 12), p + Ui.dp(this, 3), Ui.dp(this, 15));
        TextView l = new TextView(this);
        l.setText(left);
        l.setTextSize(15);
        l.setTextColor(Ui.BLACK);
        l.setSingleLine(true);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (right != null) {
            TextView rt = new TextView(this);
            rt.setText(right);
            rt.setTextSize(14);
            rt.setTypeface(Typeface.DEFAULT_BOLD);
            rt.setTextColor(Ui.BLACK);
            r.addView(rt);
        }
        r.setClickable(true);
        r.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        body.addView(r, lp);
        return r;
    }
}
