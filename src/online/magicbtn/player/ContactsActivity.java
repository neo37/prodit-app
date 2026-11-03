package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Контакты: «Мои контакты» (сохранённые на устройстве + собеседники из диалогов) и «Все пользователи»
 * (каталог с сервера, action=users, поиск по имени). Тап — личка, 📞 — звонок, ＋ — в контакты,
 * долгое нажатие — переименовать, убрать, заблокировать.
 */
public class ContactsActivity extends Activity {

    private LinearLayout list;
    private TextView status, tabMine, tabAll;
    private EditText search;
    private boolean all;
    private int gen, offset;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::render;

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
        title.setText(Ui.t(R.string.contacts_title));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView link = Ui.button(this, "🔗", Ui.WHITE);   // моя ссылка «написать мне» — добавить меня другу
        link.setOnClickListener(v -> ShareLink.text(this, Ui.t(R.string.dm_my_link_text) + "\n" + ShareLink.dmLink(this)));
        header.addView(link);
        col.addView(header);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(p, 0, p, 0);
        tabMine = Ui.button(this, Ui.t(R.string.contacts_mine), Ui.YELLOW);
        tabAll = Ui.button(this, Ui.t(R.string.contacts_all_tab), Ui.WHITE);
        tabMine.setTextSize(14);
        tabAll.setTextSize(14);
        tabMine.setOnClickListener(v -> tab(false));
        tabAll.setOnClickListener(v -> tab(true));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.rightMargin = Ui.dp(this, 8);
        tabs.addView(tabMine, tl);
        tabs.addView(tabAll, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(tabs);

        search = new EditText(this);
        search.setHint(Ui.t(R.string.contacts_search));
        search.setSingleLine(true);
        search.setTextColor(Ui.BLACK);
        search.setHintTextColor(Ui.GRAY);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.leftMargin = sl.rightMargin = p;
        sl.topMargin = Ui.dp(this, 6);
        col.addView(search, sl);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {   // поиск — через полсекунды после последней буквы
                main.removeCallbacks(reload);
                main.postDelayed(reload, all ? 450 : 50);
            }
        });

        status = new TextView(this);
        status.setTextColor(Ui.GRAY);
        status.setTextSize(13);
        status.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 4));
        col.addView(status);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(p, 0, p, p);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(col);
        Ui.fitSystemBars(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notify.askOnce(this);
        render();
    }

    private void tab(boolean toAll) {
        all = toAll;
        tabMine.setBackground(Ui.button(this, all ? Ui.WHITE : Ui.YELLOW));
        tabAll.setBackground(Ui.button(this, all ? Ui.YELLOW : Ui.WHITE));
        tabMine.setTextColor(all ? Ui.BLACK : Ui.ON_ACCENT);
        tabAll.setTextColor(all ? Ui.ON_ACCENT : Ui.BLACK);
        render();
    }

    private String query() {
        return search.getText().toString().trim();
    }

    private void render() {
        offset = 0;
        list.removeAllViews();
        if (all) loadUsers();
        else showMine();
    }

    // ---- мои контакты

    private void showMine() {
        final int my = ++gen;
        final String q = query().toLowerCase(Locale.ROOT);
        List<Contacts.Contact> l = Contacts.all(this);
        int shown = 0;
        for (Contacts.Contact c : l) {
            if (Block.blocked(this, c.author) || (!q.isEmpty() && !c.name.toLowerCase(Locale.ROOT).contains(q))) continue;
            row(c.author, c.name, c.avatar, null, true);
            shown++;
        }
        status.setText(shown == 0 && q.isEmpty() ? Ui.t(R.string.contacts_empty) : "");
        // собеседники из диалогов, которых ещё нет в контактах — добавить одним нажатием
        new Thread(() -> {
            try {
                JSONObject o = Social.call(this, ChatActivity.CHAT_API, "", "action=dm_threads", "GET", null);
                final JSONArray a = o.optJSONArray("items");
                main.post(() -> {
                    if (my != gen || a == null) return;
                    boolean head = false;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject t = a.optJSONObject(i);
                        String peer = t.optString("peer");
                        Contacts.touch(this, peer, t.optString("name"), t.optString("avatar"));
                        if (Contacts.has(this, peer) || Block.blocked(this, peer) || !peer.matches("[a-f0-9]{16}")) continue;
                        if (!q.isEmpty() && !t.optString("name").toLowerCase(Locale.ROOT).contains(q)) continue;
                        if (!head) {
                            section(Ui.t(R.string.contacts_from_dm));
                            head = true;
                        }
                        row(peer, t.optString("name"), t.optString("avatar"),
                                t.optInt("unread") > 0 ? "•" + t.optInt("unread") + "  " + t.optString("text") : t.optString("text"), false);
                    }
                    if (head) status.setText("");
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ---- все пользователи

    private void loadUsers() {
        final int my = ++gen;
        final int off = offset;
        if (off == 0) status.setText(Ui.t(R.string.loading));
        final String q = query();
        new Thread(() -> {
            try {
                String query = "action=users&offset=" + off + (q.isEmpty() ? "" : "&q=" + java.net.URLEncoder.encode(q, "UTF-8"));
                final JSONObject o = Social.call(this, ChatActivity.CHAT_API, "", query, "GET", null);
                main.post(() -> {
                    if (my != gen) return;
                    if (!o.optBoolean("ok")) {   // сервер ещё без каталога (до заливки PHP 3.3)
                        status.setText(Ui.t(R.string.users_unavailable));
                        return;
                    }
                    showUsers(o);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (my == gen) status.setText(Ui.t(R.string.chat_offline));
                });
            }
        }).start();
    }

    private void showUsers(JSONObject o) {
        JSONArray a = o.optJSONArray("items");
        if (offset == 0) {
            tabAll.setText(Ui.t(R.string.contacts_all, o.optInt("total")));
            status.setText(a == null || a.length() == 0 ? Ui.t(R.string.users_none) : Ui.t(R.string.users_hint));
        }
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject u = a.optJSONObject(i);
            String au = u.optString("author");
            if (Block.blocked(this, au)) continue;
            Contacts.touch(this, au, u.optString("name"), u.optString("avatar"));
            row(au, u.optString("name"), u.optString("avatar"),
                    u.optBoolean("online") ? "🟢 " + Ui.t(R.string.users_online) : Ui.t(R.string.users_seen, when(u.optString("seen"))), false);
        }
        offset += a == null ? 0 : a.length();
        if (o.optBoolean("more")) {
            final TextView more = Ui.button(this, Ui.t(R.string.users_more), Ui.WHITE);
            more.setOnClickListener(v -> {
                list.removeView(more);
                loadUsers();
            });
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
            ml.topMargin = Ui.dp(this, 10);
            list.addView(more, ml);
        }
    }

    /** «12.10 14:20» в часовом поясе телефона. */
    private static String when(String iso) {
        try {
            java.util.Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso);
            return new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(d);
        } catch (Exception e) {
            return "—";
        }
    }

    // ---- строки

    private void section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Ui.GRAY);
        v.setTextSize(12);
        v.setPadding(0, Ui.dp(this, 16), 0, 0);
        list.addView(v);
    }

    private void row(final String author, final String name, String avatar, String sub, boolean saved) {
        final String nm = name == null || name.isEmpty() ? "Guest" : name;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.box(this, Ui.WHITE, 2));
        int p = Ui.dp(this, 10);
        row.setPadding(p, p, p, p + Ui.dp(this, 2));
        ImageView ava = new ImageView(this);
        ava.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ava.setBackground(Ui.box(this, Ui.YELLOW, 0));
        ava.setClipToOutline(true);
        row.addView(ava, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        Profile.loadAvatar(this, avatar, ava);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        TextView n = new TextView(this);
        n.setText(nm);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextSize(15);
        n.setTextColor(Ui.BLACK);
        n.setSingleLine(true);
        txt.addView(n);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(Ui.GRAY);
            s.setSingleLine(true);
            txt.addView(s);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        if (!Contacts.has(this, author)) {
            final TextView add = Ui.button(this, "＋", Ui.WHITE);
            add.setOnClickListener(v -> {
                Contacts.add(this, author, nm, avatar);
                Toast.makeText(this, Ui.t(R.string.contacts_added), Toast.LENGTH_SHORT).show();
                add.setText("★");
                add.setEnabled(false);
            });
            row.addView(add);
        }
        TextView call = Ui.button(this, "📞", Ui.YELLOW);
        call.setOnClickListener(v -> Calls.start(this, author, nm));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = Ui.dp(this, 6);
        row.addView(call, cl);
        row.setOnClickListener(v -> startActivity(ChatActivity.dm(this, author, nm)));
        row.setOnLongClickListener(v -> {
            menu(author, nm, avatar);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 8);
        list.addView(row, lp);
    }

    private void menu(final String author, final String name, final String avatar) {
        final List<String> acts = new ArrayList<>();
        final List<Runnable> run = new ArrayList<>();
        acts.add("✉ " + Ui.t(R.string.dm_write));
        run.add(() -> startActivity(ChatActivity.dm(this, author, name)));
        acts.add("📞 " + Ui.t(R.string.call));
        run.add(() -> Calls.start(this, author, name));
        if (Contacts.has(this, author)) {
            acts.add("✎ " + Ui.t(R.string.contacts_rename));
            run.add(() -> {
                final EditText e = new EditText(this);
                e.setSingleLine(true);
                e.setText(name);
                new AlertDialog.Builder(this).setTitle(Ui.t(R.string.contacts_rename)).setView(e)
                        .setPositiveButton("OK", (d, w) -> {
                            String nn = e.getText().toString().trim();
                            if (!nn.isEmpty()) Contacts.rename(this, author, nn);
                            render();
                        }).setNegativeButton(Ui.t(R.string.cancel), null).show();
            });
            acts.add("✕ " + Ui.t(R.string.contacts_remove));
            run.add(() -> {
                Contacts.remove(this, author);
                render();
            });
        } else {
            acts.add("＋ " + Ui.t(R.string.contacts_add));
            run.add(() -> {
                Contacts.add(this, author, name, avatar);
                render();
            });
        }
        acts.add("⛔ " + Ui.t(R.string.block_author, name));
        run.add(() -> {
            Block.block(this, author, name);
            ChatActivity.dmBlock(this, author, true);
            Toast.makeText(this, Ui.t(R.string.blocked_done), Toast.LENGTH_SHORT).show();
            render();
        });
        new AlertDialog.Builder(this).setTitle(name)
                .setItems(acts.toArray(new String[0]), (d, w) -> run.get(w).run()).show();
    }
}
