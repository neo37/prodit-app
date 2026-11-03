package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Общий чат всех пользователей приложения (сервер — video_api.php на magicbtn.online),
 * а с extra «peer» — личная переписка с одним человеком (action=dm / dm_post).
 * В сообщение можно вложить свой источник — получатель подключает его одной кнопкой.
 * Долгое нажатие на сообщение — пожаловаться (от 3 человек скрывается).
 */
public class ChatActivity extends Activity {

    static final String CHAT_API = Flags.PRODIT ? "https://spacecode.business/prodit/video_api.php"
            : "https://magicbtn.online/video_api.php";
    private static final long POLL_MS = 4000;
    private static final int REQ_ATTACH = 21;

    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private TextView status;
    private int lastId;
    /** Уже показанные сообщения: опрос и загрузка после отправки могли прийти одновременно — без дублей. */
    private final java.util.Set<Integer> shown = new java.util.HashSet<>();
    private volatile boolean loading;
    private boolean polling;
    private TextView dmBtn;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::load;
    private String peer, peerName; // личка: номер собеседника (author) и его имя
    /** Чья личка сейчас открыта — по нему уведомление не показываем. */
    static volatile String openPeer;
    /** Открыт общий чат (не личка) — пуши об общем чате не показываем. */
    static volatile boolean generalOpen;

    /** Открыть личную переписку с автором. */
    static android.content.Intent dm(android.content.Context c, String author, String name) {
        return new android.content.Intent(c, ChatActivity.class).putExtra("peer", author).putExtra("peer_name", name == null ? "" : name);
    }

    private boolean isDm() {
        return peer != null;
    }

    private String post() {
        return isDm() ? "action=dm_post" : "action=chat_post";
    }

    /** Тело сообщения: в личке — кому и как я его называю. */
    private JSONObject body(String text) throws Exception {
        JSONObject b = new JSONObject().put("text", text).put("name", Social.name(this).isEmpty() ? "Guest" : Social.name(this));
        if (isDm()) b.put("to", peer).put("to_name", peerName);
        return b;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        peer = getIntent().getStringExtra("peer");
        peerName = getIntent().getStringExtra("peer_name");
        if (peerName == null || peerName.isEmpty()) peerName = "Guest";
        String saved = peer == null ? null : Contacts.name(this, peer);
        if (saved != null && !saved.isEmpty()) peerName = saved; // как я записал его в контактах
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
        title.setText(isDm() ? "✉ " + peerName : Ui.t(R.string.chat_title));
        title.setSingleLine(true);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (isDm()) {   // 📞 — звонок через efir.umno.cloud, ☆ — в контакты
            TextView call = Ui.button(this, "📞", Ui.WHITE);
            call.setOnClickListener(v -> Calls.start(this, peer, peerName));
            header.addView(call);
            final TextView star = Ui.button(this, Contacts.has(this, peer) ? "★" : "☆", Ui.WHITE);
            star.setOnClickListener(v -> {
                if (Contacts.has(this, peer)) Contacts.remove(this, peer);
                else {
                    Contacts.add(this, peer, peerName, null);
                    Toast.makeText(this, Ui.t(R.string.contacts_added), Toast.LENGTH_SHORT).show();
                }
                star.setText(Contacts.has(this, peer) ? "★" : "☆");
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.leftMargin = Ui.dp(this, 6);
            header.addView(star, sl);
        }
        if (!isDm()) {   // ✉ — личные сообщения (с числом непрочитанных)
            dmBtn = Ui.button(this, "✉", Ui.WHITE);
            dmBtn.setOnClickListener(v -> startActivity(new android.content.Intent(this, DmListActivity.class)));
            header.addView(dmBtn);
        }
        col.addView(header);

        status = new TextView(this);
        status.setTextColor(Ui.GRAY);
        status.setTextSize(12);
        status.setPadding(p, 0, p, Ui.dp(this, 4));
        status.setText(Ui.t(R.string.loading));
        col.addView(status);

        scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(p, 0, p, p);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        // открылась клавиатура (список стал ниже) — держим последние сообщения над полем ввода
        scroll.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (bt - t < ob - ot) scroll.post(this::toBottom);
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.WHITE);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        TextView share = Ui.button(this, "📎", Ui.WHITE);
        share.setOnClickListener(v -> Rules.ensure(this, this::attachMenu));
        if (!Flags.PRODIT) bar.addView(share);   // Production IT 1.0: вложения позже (нужна загрузка файлов на свой сервер)
        input = new EditText(this);
        input.setHint(Ui.t(R.string.message_hint));
        input.setMaxLines(4);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.leftMargin = Ui.dp(this, 8);
        ip.rightMargin = Ui.dp(this, 8);
        bar.addView(input, ip);
        TextView send = Ui.button(this, "➤", Ui.YELLOW);
        send.setOnClickListener(v -> Rules.ensure(this, () -> send(input.getText().toString().trim(), null)));
        bar.addView(send);
        col.addView(bar);

        setContentView(col);
        Ui.fitSystemBars(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isDm() && getIntent().getBooleanExtra("help", false)) {   // 3.10: из «Помощи» — подробный показ чата
            getIntent().removeExtra("help");
            final java.util.List<Tour.Step> hs = new java.util.ArrayList<>();
            hs.add(new Tour.Step(list, Ui.t(R.string.helpc_list), "helpc_list"));
            hs.add(new Tour.Step(null, Ui.t(R.string.helpc_long), "helpc_long"));
            hs.add(new Tour.Step(input, Ui.t(R.string.hint_chat), "hint_chat"));
            if (dmBtn != null) hs.add(new Tour.Step(dmBtn, Ui.t(R.string.hint_dm), "hint_dm"));
            hs.add(new Tour.Step(null, Ui.t(R.string.helpc_video), "helpc_video"));
            list.postDelayed(() -> {
                if (!isFinishing()) Tour.show(this, hs.toArray(new Tour.Step[0]));
            }, 800);
        } else if (!isDm()) {   // 3.9: при первом открытии общего чата — короткая подсказка Алисы
            java.util.List<Tour.Step> st = new java.util.ArrayList<>();
            st.add(new Tour.Step(input, Ui.t(R.string.hint_chat), "hint_chat"));
            if (dmBtn != null) st.add(new Tour.Step(dmBtn, Ui.t(R.string.hint_dm), "hint_dm"));
            Tour.once(this, "chat", st.toArray(new Tour.Step[0]));
        }
        if (isDm()) {
            openPeer = peer;
            Notify.cancel(this, peer);
            Notify.askOnce(this);
        }
        else {
            generalOpen = true;
            Notify.clearChat(this);
        }
        polling = true;
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isDm() && peer.equals(openPeer)) openPeer = null;
        if (!isDm()) generalOpen = false;
        polling = false;
        main.removeCallbacks(poll);
    }

    private void load() {
        main.removeCallbacks(poll);
        if (loading) {   // уже грузим — повторим чуть позже, а не параллельно
            main.postDelayed(poll, 400);
            return;
        }
        loading = true;
        new Thread(() -> {
            try {
                JSONObject o = Social.call(this, CHAT_API, "", (isDm() ? "action=dm&with=" + peer : "action=chat")
                        + "&since=" + lastId, "GET", null);
                if (!isDm()) DmListActivity.refreshUnread(this, n -> main.post(() -> {
                    if (dmBtn != null) dmBtn.setText(n > 0 ? "✉ " + n : "✉");
                }));
                final JSONArray a = o.optJSONArray("items");
                main.post(() -> {
                    status.setText(isDm() ? Ui.t(R.string.dm_rules) : Ui.t(R.string.chat_rules_short));
                    if (a != null && a.length() > 0) {
                        for (int i = 0; i < a.length(); i++) add(a.optJSONObject(i));
                        scroll.post(this::toBottom);
                    } else if (list.getChildCount() == 0) {
                        status.setText(Ui.t(R.string.chat_empty));
                    }
                });
            } catch (Exception e) {
                main.post(() -> status.setText(Ui.t(R.string.chat_offline)));
            }
            loading = false;
            if (polling) main.postDelayed(poll, POLL_MS);
        }).start();
    }

    private void add(final JSONObject m) {
        if (m == null) return;
        if (m.optInt("id") > 0 && !shown.add(m.optInt("id"))) return;   // это сообщение уже на экране
        lastId = Math.max(lastId, m.optInt("id"));
        if (!isDm() && generalOpen) Inbox.seenChat(this, lastId);
        if (!m.optBoolean("mine") && Block.blocked(this, m.optString("author"))) return; // автор заблокирован
        boolean mine = m.optBoolean("mine");
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.box(this, mine ? Ui.YELLOW : Ui.WHITE, 2));
        int p = Ui.dp(this, 10);
        b.setPadding(p, p, p + Ui.dp(this, 2), p + Ui.dp(this, 2));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView ava = new android.widget.ImageView(this);
        ava.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        ava.setBackground(Ui.box(this, Ui.YELLOW, 0));
        ava.setClipToOutline(true);
        head.addView(ava, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        Profile.loadAvatar(this, m.optString("avatar"), ava);
        TextView who = new TextView(this);
        String ts = m.optString("ts");
        who.setText(m.optString("name") + (ts.length() >= 16 ? "  " + ts.substring(11, 16) : ""));
        who.setTypeface(Typeface.DEFAULT_BOLD);
        who.setTextSize(12);
        who.setTextColor(mine ? (Ui.ON_ACCENT & 0x00FFFFFF | 0xB3000000) : Ui.GRAY);   // на своём (акцентном) — тёмный
        who.setPadding(Ui.dp(this, 8), 0, 0, 0);
        head.addView(who);
        b.addView(head);
        final String author = m.optString("author");
        if (!isDm() && !mine && author.matches("[a-f0-9]{16}"))   // тап по автору — написать ему лично
            head.setOnClickListener(v -> startActivity(dm(this, author, m.optString("name"))));
        final String image = m.optString("image");
        if (!m.optString("text").isEmpty() && !(image.length() > 0 && "🖼".equals(m.optString("text")))) {
            TextView t = new TextView(this);
            String shownText = m.optString("text");
            String callUrl = Calls.link(shownText);
            if (callUrl != null) shownText = shownText.replace(callUrl, "").trim();   // ссылку прячем — есть кнопка «В звонок»
            t.setText(shownText);
            t.setTextSize(15);
            int ink = mine ? Ui.ON_ACCENT : Ui.BLACK;   // своё сообщение на акцентном фоне — текст контрастный, а не белый
            t.setTextColor(ink);
            // 3.11: ссылки кликабельны (раньше маска ставилась после текста и не срабатывала), текст можно выделить
            android.text.util.Linkify.addLinks(t, android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.EMAIL_ADDRESSES
                    | android.text.util.Linkify.PHONE_NUMBERS);
            t.setLinkTextColor(ink);   // цвет текста + подчёркивание — читается в любой теме
            t.setLinksClickable(true);
            t.setOnLongClickListener(v -> {   // долгое нажатие и по тексту — то же меню сообщения
                messageMenu(m, mine, m.optJSONObject("share"));
                return true;
            });
            b.addView(t);
            final Uri link = ShareLink.findIn(m.optString("text"));
            final ShareLink.Link pl = ShareLink.parse(link);
            if (pl != null) {   // наша ссылка в тексте — кнопка «открыть» прямо в приложении
                TextView open = Ui.button(this, pl.dm != null ? "✉ " + Ui.t(R.string.dm_write)
                        : pl.file != null ? "▶ " + Ui.t(R.string.chat_watch) : "＋ " + Ui.t(R.string.chat_add_source, pl.source.name), Ui.YELLOW);
                open.setTextSize(13);
                open.setOnClickListener(v -> startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, link)
                        .setClass(this, AddSourceActivity.class)));
                LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
                ol.topMargin = Ui.dp(this, 6);
                b.addView(open, ol);
            }
        }
        final String call = Calls.link(m.optString("text"));
        if (call != null) {   // приглашение на звонок — войти в ту же комнату
            TextView join = Ui.button(this, "📞 " + Ui.t(R.string.call_join), Ui.YELLOW);
            join.setTextSize(13);
            join.setOnClickListener(v -> Calls.open(this, call));
            LinearLayout.LayoutParams jl = new LinearLayout.LayoutParams(-2, -2);
            jl.topMargin = Ui.dp(this, 6);
            b.addView(join, jl);
        }
        if (image.matches("[a-f0-9]{32}\\.(jpg|png|webp|gif)")) addImage(b, image);
        final JSONObject sh = m.optJSONObject("share");
        if (sh != null && sh.optString("file").startsWith("https://")) {
            TextView watch = Ui.button(this, "▶ " + Ui.t(R.string.chat_watch), Ui.YELLOW);
            watch.setTextSize(13);
            watch.setOnClickListener(v -> ShareLink.watch(this, sh.optString("file"), m.optString("text"),
                    sh.optString("name"), sh.optString("url"), sh.optString("key")));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
            wl.topMargin = Ui.dp(this, 6);
            b.addView(watch, wl);
        }
        if (sh != null) {
            TextView add = Ui.button(this, "＋ " + Ui.t(R.string.chat_add_source, sh.optString("name")), Ui.WHITE);
            add.setTextSize(13);
            add.setOnClickListener(v -> addSource(sh));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(this, 6);
            b.addView(add, lp);
        }
        b.setOnLongClickListener(v -> {
            messageMenu(m, mine, sh);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 8);
        if (mine) lp.leftMargin = Ui.dp(this, 40);
        else lp.rightMargin = Ui.dp(this, 40);
        list.addView(b, lp);
    }

    /** Текст сообщения для копирования/пересылки: сам текст и ссылка на видео, если оно есть. */
    private static String messageText(JSONObject m, JSONObject sh) {
        StringBuilder t = new StringBuilder(m.optString("text").trim());
        if (sh != null && sh.optString("file").startsWith("https://") && t.indexOf(sh.optString("file")) < 0) {
            if (t.length() > 0) t.append("\n");
            t.append(sh.optString("file"));
        }
        return t.toString();
    }

    /** Картинка в сообщении (ответ поддержки из Telegram): кэш на диске, тап — на весь экран. */
    private void addImage(LinearLayout b, final String name) {
        final android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
        iv.setMaxHeight(Ui.dp(this, 320));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = Ui.dp(this, 6);
        b.addView(iv, il);
        final java.io.File f = new java.io.File(new java.io.File(getCacheDir(), "dm_images"), name);
        new Thread(() -> {
            try {
                if (!f.exists()) {
                    f.getParentFile().mkdirs();
                    java.io.File tmp = new java.io.File(f.getPath() + ".part");
                    java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(CHAT_API + "?action=dm_image&f=" + name).openConnection();
                    if (h.getResponseCode() != 200) return;
                    try (java.io.InputStream in = h.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    h.disconnect();
                    if (!tmp.renameTo(f)) return;
                }
                final android.graphics.Bitmap bm = decode(f, 1280);
                if (bm != null) runOnUiThread(() -> iv.setImageBitmap(bm));
            } catch (Exception ignored) {
            }
        }).start();
        iv.setOnClickListener(v -> {
            if (!f.exists()) return;
            android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            android.widget.ImageView full = new android.widget.ImageView(this);
            full.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            full.setBackgroundColor(0xFF000000);
            full.setImageBitmap(decode(f, 2560));
            full.setOnClickListener(x -> d.dismiss());
            d.setContentView(full);
            d.show();
        });
    }

    /** Картинка с уменьшением до max по большей стороне — чтобы фото с камеры не съели память. */
    private static android.graphics.Bitmap decode(java.io.File f, int max) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > max) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Подключить источник из сообщения (как «＋ Подключить источник» с готовыми полями). */
    private void addSource(JSONObject sh) {
        String url = sh.optString("url");
        if (!url.startsWith("https://")) return;
        List<Sources.Source> l = Sources.all(this);
        for (Sources.Source s : l) {
            if (s.url.equals(url) && s.key.equals(sh.optString("key"))) {
                Toast.makeText(this, Ui.t(R.string.chat_already_added), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        l.add(new Sources.Source(sh.optString("name", Uri.parse(url).getHost()), url, sh.optString("key")));
        Sources.save(this, l, l.size() - 1);
        Toast.makeText(this, Ui.t(R.string.chat_source_added), Toast.LENGTH_SHORT).show();
    }

    /** Долгое нажатие: своё — удалить для всех (и свой источник), чужое — пожаловаться. */
    private void messageMenu(final JSONObject m, boolean mine, final JSONObject sh) {
        final List<String> acts = new ArrayList<>();
        final List<Runnable> run = new ArrayList<>();
        final String full = messageText(m, sh);
        if (!full.isEmpty()) {   // 3.11: копировать и переслать в другие приложения
            acts.add("⧉ " + Ui.t(R.string.msg_copy));
            run.add(() -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("message", full));
                if (android.os.Build.VERSION.SDK_INT < 33)   // с Android 13 система сама показывает «скопировано»
                    Toast.makeText(this, Ui.t(R.string.msg_copied), Toast.LENGTH_SHORT).show();
            });
            acts.add("↗ " + Ui.t(R.string.msg_forward));
            run.add(() -> startActivity(android.content.Intent.createChooser(
                    new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, full), Ui.t(R.string.msg_forward))));
        }
        if (mine) {
            acts.add("🗑 " + Ui.t(R.string.chat_delete_all));
            run.add(() -> new Thread(() -> {
                try {
                    Social.call(this, CHAT_API, "", isDm() ? "action=dm_delete" : "action=chat_delete", "POST",
                            new JSONObject().put("ref", String.valueOf(m.optInt("id"))).toString());
                } catch (Exception ignored) {
                }
                main.post(this::reloadAll);
            }).start());
        }
        if (!mine && !isDm() && Admin.on(this)) {   // 3.9: админ удаляет любое сообщение общего чата
            acts.add("🗑 " + Ui.t(R.string.admin_delete));
            run.add(() -> Admin.call(this, Admin.key(this), "action=chat_delete",
                    "{\"ref\":\"" + m.optInt("id") + "\"}", o -> reloadAll()));
        }
        JSONObject sp = Uploads.space(this);
        if (sh != null && sp != null && sp.optString("key").equals(sh.optString("key"))) {
            acts.add("🗑 " + Ui.t(R.string.source_delete));
            run.add(() -> MySourceActivity.deleteSource(this, () -> main.post(this::reloadAll)));
        }
        if (!mine && !isDm() && m.optString("author").matches("[a-f0-9]{16}")) {
            acts.add("✉ " + Ui.t(R.string.dm_write));
            run.add(() -> startActivity(dm(this, m.optString("author"), m.optString("name"))));
        }
        final String au = m.optString("author");
        if (!mine && au.matches("[a-f0-9]{16}")) {
            if (!Contacts.has(this, au)) {
                acts.add("＋ " + Ui.t(R.string.contacts_add));
                run.add(() -> {
                    Contacts.add(this, au, m.optString("name"), m.optString("avatar"));
                    Toast.makeText(this, Ui.t(R.string.contacts_added), Toast.LENGTH_SHORT).show();
                });
            }
            if (!isDm()) {
                acts.add("📞 " + Ui.t(R.string.call));
                run.add(() -> Calls.start(this, au, m.optString("name")));
            }
        }
        if (!mine) {
            acts.add("🚩 " + Ui.t(R.string.report));
            run.add(() -> SocialUi.report(this, CHAT_API, "", isDm() ? "dm" : "chat", String.valueOf(m.optInt("id"))));
            if (!m.optString("author").isEmpty()) {
                acts.add("⛔ " + Ui.t(R.string.block_author, m.optString("name")));
                run.add(() -> {
                    Block.block(this, m.optString("author"), m.optString("name"));
                    dmBlock(this, m.optString("author"), true); // и в личку он больше не напишет
                    Toast.makeText(this, Ui.t(R.string.blocked_done), Toast.LENGTH_SHORT).show();
                    if (isDm()) finish();
                    else reloadAll();
                });
            }
        }
        new AlertDialog.Builder(this)
                .setItems(acts.toArray(new String[0]), (d, w) -> run.get(w).run())
                .show();
    }

    /** Запрет (или снятие запрета) писать мне в личку — на сервере. */
    static void dmBlock(final android.content.Context c, final String author, final boolean on) {
        new Thread(() -> {
            try {
                Social.call(c, CHAT_API, "", "action=dm_block", "POST",
                        new JSONObject().put("to", author).put("on", on ? "1" : "0").toString());
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** Вниз к последнему сообщению — без fullScroll: тот уводит фокус с поля ввода и прячет клавиатуру. */
    private void toBottom() {
        scroll.scrollTo(0, Math.max(0, list.getHeight() - scroll.getHeight() + scroll.getPaddingBottom()));
    }

    private void reloadAll() {
        list.removeAllViews();
        lastId = 0;
        shown.clear();
        load();
    }

    /** 📎: фото или видео с телефона (сам создаст «Мой источник», загрузит и отправит) или готовый источник. */
    private void attachMenu() {
        String[] items = {"🎬 " + Ui.t(R.string.chat_attach_media), "📁 " + Ui.t(R.string.chat_share_title)};
        new AlertDialog.Builder(this)
                .setItems(items, (d, w) -> {
                    if (w == 0) startActivityForResult(Uploads.pickIntent(), REQ_ATTACH);
                    else askShare();
                })
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ATTACH || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        final Uploads.Picked f = Uploads.check(this, u);
        if (f.error != null) {
            Toast.makeText(this, f.error, Toast.LENGTH_LONG).show();
            return;
        }
        final String caption = input.getText().toString().trim();
        if (!isDm()) {   // общий чат — подтвердить до загрузки
            ChatConfirm.ask(this, (f.photo ? "🖼 " : "🎬 ") + f.name, () -> attach(u, f, caption));
            return;
        }
        attach(u, f, caption);
    }

    private void attach(final Uri u, final Uploads.Picked f, final String caption) {
        new Thread(() -> {
            try {
                final JSONObject sp = Uploads.ensureSpace(this, null);
                main.post(() -> MySourceActivity.UploadDialog.run(this, sp, u, f, r -> new Thread(() -> {
                    try {
                        JSONObject b = body((caption.isEmpty() ? "" : caption + "\n") + (f.photo ? "🖼 " : "🎬 ") + r.optString("fname"))
                                .put("share_name", sp.optString("name")).put("share_url", CHAT_API)
                                .put("share_key", sp.optString("key"))
                                .put("share_file", Uploads.streamUrl(sp, r.optString("fname")));
                        Social.call(this, CHAT_API, "", post(), "POST", b.toString());
                    } catch (Exception ignored) {
                    }
                    main.post(() -> {
                        input.setText("");
                        load();
                    });
                }).start()));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, Ui.t(R.string.error_prefix, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** Поделиться своим источником (из «Мой источник» или любым подключённым с API). */
    private void askShare() {
        final List<Sources.Source> mine = new ArrayList<>();
        for (Sources.Source s : Sources.all(this)) if (s.isApi()) mine.add(s);
        if (mine.isEmpty()) {
            Toast.makeText(this, Ui.t(R.string.chat_nothing_to_share), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[mine.size()];
        for (int i = 0; i < names.length; i++) names[i] = mine.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle(Ui.t(R.string.chat_share_title))
                .setItems(names, (d, w) -> send(input.getText().toString().trim(), mine.get(w)))
                .show();
    }

    private void send(final String text, final Sources.Source share) {
        if (text.isEmpty() && share == null) return;
        if (Social.name(this).isEmpty()) {
            final EditText e = new EditText(this);
            e.setSingleLine(true);
            e.setHint(Ui.t(R.string.your_name));
            new AlertDialog.Builder(this)
                    .setTitle(Ui.t(R.string.your_name))
                    .setView(e)
                    .setPositiveButton("OK", (d, w) -> {
                        String n = e.getText().toString().trim();
                        Social.setName(this, n.isEmpty() ? "Guest" : n);
                        send(text, share);
                    })
                    .show();
            return;
        }
        if (!isDm()) {   // общий чат — только после подтверждения
            ChatConfirm.ask(this, share != null ? "📁 " + share.name + (text.isEmpty() ? "" : "\n" + text) : text,
                    () -> doSend(text, share));
            return;
        }
        doSend(text, share);
    }

    private void doSend(final String text, final Sources.Source share) {
        input.setText("");
        new Thread(() -> {
            try {
                JSONObject b = body(text);
                if (share != null) b.put("share_name", share.name).put("share_url", share.apiBase()).put("share_key", share.key);
                JSONObject o = Social.call(this, CHAT_API, isDm() ? "" : Admin.key(this), post(), "POST", b.toString());
                if (o.optBoolean("ok")) Stats.add(this, Stats.CHAT, 1);   // 3.8: статистика кнопки «Чат»
                if (!o.optBoolean("ok")) {
                    final String err = o.optString("error");
                    main.post(() -> Toast.makeText(this, "too fast".equals(err) ? Ui.t(R.string.chat_too_fast)
                                    : "blocked".equals(err) ? Ui.t(R.string.dm_blocked)
                                    : "chat closed".equals(err) ? Ui.t(R.string.chat_closed) : err,
                            Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, Ui.t(R.string.chat_offline), Toast.LENGTH_SHORT).show());
            }
            main.post(this::load);
        }).start();
    }
}
