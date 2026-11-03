package online.magicbtn.player;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Список видео: mode=local — память телефона (MediaStore), mode=api — video_api.php источника
 * (magicbtn.online, hn.eyetrick.online и любой сервер с тем же API).
 * У каждого ролика превью: с телефона — системное, из сети — poster из API или кадр из самого видео.
 * Без сети источник показывает то, что уже сохранено офлайн.
 */
public class VideoListActivity extends Activity {

    static final class Video {
        final String title, uri, folder, meta, poster;
        final long id;      // _ID в MediaStore, -1 для сети
        final boolean image;
        long bytes, time;   // для сортировки
        Social.Ref ref;     // лайки/комментарии (только источники с API)

        Video(String title, String uri, String folder, String meta, String poster, long id, boolean image) {
            this.title = title;
            this.uri = uri;
            this.folder = folder;
            this.meta = meta;
            this.poster = poster;
            this.id = id;
            this.image = image;
        }
    }

    private static final int REQ_PERM = 7;
    private final String ALL = Ui.t(R.string.all);

    private boolean local;
    private TextView foldersBtn;
    private List<String> apiFolders = new ArrayList<>();
    private Sources.Source source;
    private final List<Video> all = new ArrayList<>();
    private final List<Video> shown = new ArrayList<>();
    private String folder = ALL;
    private LinearLayout tabs;
    private TextView info, title, shareBtn, menuBtn;
    private LinearLayout sourceTabs;
    private int gen; // номер загрузки: ответы от прошлой вкладки отбрасываются
    private LinearLayout permBar; // подсказка про доступ к видео (ограниченный / нет доступа)
    private Adapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private File thumbDir;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // 3.6: с иконки приложения — сначала главный экран (Чат / Видеоплеер / Игра / Pro)
        if (state == null && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        // первый экран приложения — видео с телефона (запуск с иконки — без mode)
        local = !"api".equals(getIntent().getStringExtra("mode"));
        if (!local) source = Sources.get(this, getIntent().getIntExtra("source", 0));
        thumbDir = new File(getCacheDir(), "thumbs");
        thumbDir.mkdirs();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, Ui.STYLE == Ui.CONSOLE ? 12 : 4));
        header.addView(menuBtn = Nav.button(this)); // ☰ — всё остальное через меню
        title = new TextView(this);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView slideshow = Ui.button(this, Ui.t(R.string.slideshow), Ui.YELLOW);
        slideshow.setTextSize(14);
        slideshow.setOnClickListener(v -> play(0, true));
        TextView sort = Ui.button(this, "⇅", Ui.WHITE);
        sort.setOnClickListener(v -> Sort.ask(this, sortKey(), () -> show(new ArrayList<>(all), null)));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = Ui.dp(this, 6);
        header.addView(slideshow);
        header.addView(sort, sl);
        shareBtn = Ui.button(this, "📤", Ui.WHITE);
        shareBtn.setOnClickListener(v -> {
            if (source != null) ShareLink.send(this, source);
        });
        header.addView(shareBtn, new LinearLayout.LayoutParams(sl));
        foldersBtn = Ui.button(this, Ui.t(R.string.folders_btn), Ui.WHITE);   // 3.4: включить/выключить папки источника
        foldersBtn.setOnClickListener(v -> {
            if (source != null) FolderFilter.edit(this, source.url, apiFolders, () -> select(Sources.currentIndex(this, Sources.all(this).size())));
        });
        header.addView(foldersBtn, new LinearLayout.LayoutParams(sl));
        col.addView(header);

        // вкладки источников: 📱 телефон, magicbtn.online, подключённые, ＋
        HorizontalScrollView ss = new HorizontalScrollView(this);
        ss.setHorizontalScrollBarEnabled(false);
        sourceTabs = new LinearLayout(this);
        sourceTabs.setPadding(p, Ui.dp(this, 8), p, 0);
        ss.addView(sourceTabs);
        col.addView(ss);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 4));
        hs.addView(tabs);
        col.addView(hs);

        info = new TextView(this);
        info.setTextColor(Ui.GRAY);
        info.setTextSize(13);
        info.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 6));
        info.setText(Ui.t(R.string.loading));
        col.addView(info);
        permBar = new LinearLayout(this);
        permBar.setOrientation(LinearLayout.VERTICAL);
        permBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(-1, -2);
        pb.setMargins(p, Ui.dp(this, 4), p, Ui.dp(this, 6));
        col.addView(permBar, pb);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 160));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        boolean console = Ui.STYLE == Ui.CONSOLE;   // «Консоль»: ритм — плотно внутри группы, шире между группами
        grid.setHorizontalSpacing(Ui.dp(this, console ? 12 : 8));
        grid.setVerticalSpacing(Ui.dp(this, console ? 20 : 8));
        grid.setPadding(p, Ui.dp(this, 6), p, p);
        grid.setClipToPadding(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> play(pos, false));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {   // 3.4: удалить видео с телефона
            if (!local || pos >= shown.size()) return false;
            final Video v = shown.get(pos);
            new android.app.AlertDialog.Builder(this)
                    .setTitle(v.title)
                    .setItems(new String[]{Ui.t(R.string.delete_from_phone)}, (d, w) -> new android.app.AlertDialog.Builder(this)
                            .setMessage(Ui.t(R.string.delete_phone_confirm))
                            .setPositiveButton(Ui.t(R.string.remove), (d2, w2) -> Trash.deletePhone(this, Uri.parse(v.uri), () -> {
                                all.remove(v);
                                shown.remove(v);
                                adapter.notifyDataSetChanged();
                            }))
                            .setNegativeButton(Ui.t(R.string.cancel), null)
                            .show())
                    .show();
            return true;
        });
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(col);
        Ui.fitSystemBars(this);
        select(local ? -1 : getIntent().getIntExtra("source", 0));
    }

    /** Открыть вкладку: -1 — телефон, иначе источник (сайт без API — отдельным экраном). */
    private void select(int idx) {
        if (idx >= 0) {
            Sources.Source s = Sources.get(this, idx);
            if (s == null) {
                idx = -1;
            } else if (!s.isApi()) {
                Nav.openSource(this, idx); // сайт: ищем у него API, иначе WebView
                return;
            } else {
                Sources.save(this, Sources.all(this), idx);
                source = s;
            }
        }
        local = idx < 0;
        if (local) source = null;
        gen++;
        folder = ALL;
        all.clear();
        shown.clear();
        adapter.notifyDataSetChanged();
        tabs.removeAllViews();
        permBar.setVisibility(View.GONE);
        info.setText(Ui.t(R.string.loading));
        title.setText(local ? Ui.t(R.string.on_phone) : "🎬 " + source.name);
        shareBtn.setVisibility(local ? View.GONE : View.VISIBLE);
        foldersBtn.setVisibility(local ? View.GONE : View.VISIBLE);
        renderSourceTabs();
        if (local) {
            if (access() != NONE) loadLocal();
            else requestPermissions(permissions(), REQ_PERM);
        } else {
            loadApi();
        }
    }

    private void renderSourceTabs() {
        sourceTabs.removeAllViews();
        sourceTab("📱 " + Ui.t(R.string.tab_phone), local, v -> select(-1), null);
        List<Sources.Source> l = Sources.all(this);
        for (int i = 0; i < l.size(); i++) {
            final int idx = i;
            Sources.Source s = l.get(i);
            boolean on = !local && source != null && s.url.equals(source.url) && s.key.equals(source.key);
            sourceTab((s.isApi() ? "🎬 " : "🌐 ") + s.name, on, v -> select(idx), v -> {
                Sources.edit(this, idx, x -> renderSourceTabs());
                return true;
            });
        }
        sourceTab("＋", false, v -> Sources.edit(this, -1, x -> renderSourceTabs()), null);
    }

    private void sourceTab(String text, boolean on, View.OnClickListener click, View.OnLongClickListener longClick) {
        TextView t = Ui.button(this, text, on ? Ui.YELLOW : Ui.WHITE);
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setOnClickListener(click);
        if (longClick != null) t.setOnLongClickListener(longClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(this, 8);
        sourceTabs.addView(t, lp);
    }

    /** Источник выбран из меню ☰ — переключаем вкладку на этом же экране. */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        select("api".equals(i.getStringExtra("mode")) ? i.getIntExtra("source", 0) : -1);
    }

    // ---------- телефон ----------

    private static final int NONE = 0, LIMITED = 1, FULL = 2;

    /** Android 14+: кроме «Разрешить все» есть «Ограниченный доступ» — только выбранные пользователем видео. */
    private static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 34)
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"};
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private static String permission() {
        return permissions()[0];
    }

    private int access() {
        if (checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED) return FULL;
        if (Build.VERSION.SDK_INT >= 34 && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                == PackageManager.PERMISSION_GRANTED) return LIMITED;
        return NONE;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (!Trash.onResult(this, req, res)) super.onActivityResult(req, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERM) return;
        if (access() != NONE) loadLocal();
        else showPermBar(0);
    }

    /** Плашка-подсказка: ограниченный доступ (видно только выбранное) или доступа нет. */
    private void showPermBar(int visible) {
        int a = access();
        permBar.removeAllViews();
        if (a == FULL) {
            permBar.setVisibility(View.GONE);
            return;
        }
        permBar.setVisibility(View.VISIBLE);
        permBar.setBackground(Ui.box(this, Ui.WHITE, 3));
        int p = Ui.dp(this, 12);
        permBar.setPadding(p, p, p + Ui.dp(this, 3), p + Ui.dp(this, 3));
        TextView t = new TextView(this);
        t.setText(a == LIMITED ? Ui.t(R.string.perm_limited, visible) : Ui.t(R.string.no_perm));
        t.setTextColor(Ui.BLACK);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        permBar.addView(t);
        TextView how = new TextView(this);
        how.setText(Ui.t(R.string.perm_how));
        how.setTextColor(Ui.GRAY);
        how.setTextSize(13);
        how.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        permBar.addView(how);
        LinearLayout row = new LinearLayout(this);
        TextView all = Ui.button(this, Ui.t(R.string.perm_allow_all), Ui.YELLOW);
        all.setTextSize(13);
        all.setOnClickListener(v -> openAppSettings());
        row.addView(all, new LinearLayout.LayoutParams(0, -2, 1));
        if (Build.VERSION.SDK_INT >= 34 || a == NONE) {
            TextView more = Ui.button(this, Ui.t(a == LIMITED ? R.string.perm_pick_more : R.string.perm_ask_again), Ui.WHITE);
            more.setTextSize(13);
            more.setOnClickListener(v -> requestPermissions(permissions(), REQ_PERM)); // система снова покажет выбор
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1);
            ml.leftMargin = Ui.dp(this, 8);
            row.addView(more, ml);
        }
        permBar.addView(row);
    }

    /** Настройки приложения → «Разрешения» → «Фото и видео» → «Разрешить все». */
    private void openAppSettings() {
        android.widget.Toast.makeText(this, Ui.t(R.string.perm_how), android.widget.Toast.LENGTH_LONG).show();
        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void loadLocal() {
        final int g = gen;
        bg.execute(() -> {
            List<Video> out = new ArrayList<>();
            Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED};
            try (Cursor c = getContentResolver().query(base, cols, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(0);
                    String meta = duration(c.getLong(3)) + " · " + Ui.bytes(c.getLong(4));
                    Video v = new Video(c.getString(1), ContentUris.withAppendedId(base, id).toString(),
                            c.getString(2) == null ? Ui.t(R.string.no_folder) : c.getString(2), meta, null, id, false);
                    v.bytes = c.getLong(4);
                    v.time = c.getLong(5) * 1000;
                    out.add(v);
                }
            } catch (Exception e) {
                main.post(() -> {
                    if (g == gen) info.setText(Ui.t(R.string.read_failed, e.getMessage()));
                });
                return;
            }
            main.post(() -> {
                if (g != gen) return;
                show(out, null);
                showPermBar(out.size());
            });
        });
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---------- video_api.php ----------

    /** Адрес из ответа API: «?action=…» — от video_api.php, «/media/…» — от корня сайта. */
    private String resolve(String u) {
        if (u == null || u.isEmpty()) return null;
        if (u.startsWith("http")) return u;
        if (u.startsWith("?")) return source.apiBase() + u;
        Uri b = Uri.parse(source.apiBase());
        return b.getScheme() + "://" + b.getAuthority() + (u.startsWith("/") ? u : "/" + u);
    }

    /** Метка офлайн-кэша: «хост/папка». */
    private String label(String folder) {
        return source.host() + "/" + folder;
    }

    private void loadApi() {
        final int g = gen;
        final Sources.Source source = this.source;
        bg.execute(() -> {
            try {
                JSONObject f = getJson(source.apiBase() + "?action=folders");
                if (!f.optBoolean("ok")) throw new Exception(f.optString("error", Ui.t(R.string.no_access)));
                JSONArray folders = f.getJSONArray("folders");
                List<Video> out = new ArrayList<>();
                final List<String> names = new ArrayList<>();
                java.util.Set<String> off = FolderFilter.off(this, source.url);
                for (int i = 0; i < folders.length(); i++) names.add(folders.getString(i));
                main.post(() -> {
                    if (g == gen) apiFolders = names;
                });
                for (int i = 0; i < folders.length(); i++) {
                    String name = folders.getString(i);
                    if (off.contains(name)) continue;   // папку выключили (📁)
                    JSONObject l = getJson(source.apiBase() + "?action=list&folder=" + Uri.encode(name));
                    JSONArray items = l.optJSONArray("items");
                    for (int k = 0; items != null && k < items.length(); k++) {
                        JSONObject it = items.getJSONObject(k);
                        boolean image = "image".equals(it.optString("type"));
                        String url = resolve(it.optString("url"));
                        Video v = new Video(it.optString("fname"), url, name,
                                (image ? Ui.t(R.string.photo_prefix) : "") + Ui.bytes(it.optLong("size")),
                                resolve(it.optString("poster", "")), -1, image);
                        v.bytes = it.optLong("size");
                        v.time = it.optLong("mtime") * 1000;
                        v.ref = new Social.Ref(source.apiBase(), name, v.title, source.key);
                        out.add(v);
                    }
                }
                main.post(() -> {
                    if (g == gen) show(out, folders.length() == 0 ? Ui.t(R.string.no_folders) : null);
                });
                // счётчики лайков/комментариев — для плиток и сортировки
                for (int i = 0; i < folders.length(); i++)
                    Social.refreshStats(this, source.apiBase(), source.key, folders.getString(i));
                Social.flush(this);
                main.post(() -> {
                    if (g == gen) show(new ArrayList<>(all), null);
                });
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                List<Video> offline = offlineItems();
                main.post(() -> {
                    if (g != gen) return;
                    if (!offline.isEmpty()) show(offline, Ui.t(R.string.offline_note));
                    else info.setText(msg.contains("Unauthorized")
                            ? Ui.t(R.string.need_key)
                            : Ui.t(R.string.no_conn, msg));
                });
            }
        });
    }

    /** Сохранённое офлайн из этого источника (метки «хост/папка»). */
    private List<Video> offlineItems() {
        List<Video> out = new ArrayList<>();
        MediaDb db = MediaDb.get(this);
        if (source == null) return out;   // источник успели удалить/переключить (падение 3.0 на Xiaomi)
        String prefix = source.host() + "/";
        for (String l : db.labels()) {
            if (!l.startsWith(prefix)) continue;
            for (MediaDb.Item it : db.list(l)) {
                String name = Uri.parse(it.url).getQueryParameter("file");
                if (name == null) name = Uri.parse(it.url).getLastPathSegment();
                Video v = new Video(name, it.url, l.substring(prefix.length()), Ui.t(R.string.offline_meta), null, -1, !it.isVideo());
                v.bytes = it.size;
                v.time = it.ts;
                v.ref = Social.ref(this, it.url, l);
                out.add(v);
            }
        }
        return out;
    }

    private JSONObject getJson(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (!source.key.isEmpty()) c.setRequestProperty("X-API-Key", source.key);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        c.disconnect();
        if (code == 401 || code == 403) throw new Exception("Unauthorized");
        return new JSONObject(b.toString("UTF-8"));
    }

    // ---------- общий список ----------

    private void show(List<Video> videos, String note) {
        all.clear();
        all.addAll(videos);
        LinkedHashSet<String> folders = new LinkedHashSet<>();
        folders.add(ALL);
        for (Video v : all) folders.add(v.folder);
        tabs.removeAllViews();
        for (final String f : folders) {
            TextView t = Ui.button(this, f, f.equals(folder) ? Ui.YELLOW : Ui.WHITE);
            t.setTextSize(13);
            t.setOnClickListener(v -> {
                folder = f;
                show(new ArrayList<>(all), note);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 8);
            tabs.addView(t, lp);
        }
        shown.clear();
        for (Video v : all) if (ALL.equals(folder) || v.folder.equals(folder)) shown.add(v);
        final MediaDb db = MediaDb.get(this);
        java.util.Collections.sort(shown, Sort.by(Sort.get(this, sortKey()), new Sort.Keys<Video>() {
            public String name(Video v) { return v.title; }
            public long time(Video v) { return v.time; }
            public long size(Video v) { return v.bytes; }
            public MediaDb.Stat stat(Video v) { return v.ref == null ? new MediaDb.Stat() : db.stat(v.ref.skey()); }
        }));
        String count = shown.isEmpty() ? Ui.t(R.string.no_videos) : Ui.t(R.string.count, shown.size());
        info.setText(note == null ? count : note + " · " + count);
        adapter.notifyDataSetChanged();
    }

    private String sortKey() {
        return local ? "local" : "api:" + (source == null ? "" : source.host());
    }

    private void play(int pos, boolean slideshow) {
        if (shown.isEmpty()) return;
        int n = shown.size();
        String[] uris = new String[n], titles = new String[n], labels = new String[n];
        boolean[] images = new boolean[n];
        for (int i = 0; i < n; i++) {
            Video v = shown.get(i);
            uris[i] = v.uri;
            titles[i] = v.title;
            images[i] = v.image;
            labels[i] = local ? null : label(v.folder);
        }
        PlayerActivity.Playlist pl = new PlayerActivity.Playlist();
        pl.uris = uris;
        pl.titles = titles;
        pl.images = images;
        pl.labels = labels;
        pl.loop = slideshow;
        if (source != null) {
            pl.page = source.apiBase();
            pl.sourceName = source.name;
            pl.key = source.key;
        }
        PlayerActivity.next = pl;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("pos", pos);
        startActivity(i);
    }

    // ---------- превью ----------

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** Превью ролика: диск-кэш → системное (телефон) → poster из API → кадр из файла/сети. */
    private Bitmap thumb(Video v) {
        File disk = new File(thumbDir, sha1(v.uri) + ".jpg");
        if (disk.exists()) {
            Bitmap b = BitmapFactory.decodeFile(disk.getPath());
            if (b != null) return b;
        }
        Bitmap b = null;
        try {
            if (v.id >= 0) {
                if (Build.VERSION.SDK_INT >= 29) {
                    Uri u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id);
                    return getContentResolver().loadThumbnail(u, new Size(320, 320), null);
                }
                return MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), v.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
            File local = MediaCache.get(this).cached(v.uri);
            if (local != null) {
                b = v.image ? decodeScaled(local) : ThumbnailUtils.createVideoThumbnail(local.getPath(),
                        MediaStore.Images.Thumbnails.MINI_KIND);
            }
            if (b == null && v.poster != null) b = download(v.poster);
            if (b == null && !v.image) b = frame(v.uri);
        } catch (Exception ignored) {
        }
        if (b != null) {
            try (FileOutputStream out = new FileOutputStream(disk)) {
                b.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } catch (Exception ignored) {
            }
        }
        return b;
    }

    private static Bitmap decodeScaled(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private Bitmap download(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (source != null && !source.key.isEmpty()) c.setRequestProperty("X-API-Key", source.key);
        try (InputStream in = c.getInputStream()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = u.contains("action=poster") ? 1 : 4;
            return BitmapFactory.decodeStream(in, null, o);
        } finally {
            c.disconnect();
        }
    }

    /** Кадр на 1-й секунде прямо из видео в сети (читает только нужные байты через Range). */
    private Bitmap frame(String u) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            Map<String, String> h = new HashMap<>();
            if (source != null && !source.key.isEmpty()) h.put("X-API-Key", source.key);
            r.setDataSource(u, h);
            Bitmap f = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (f == null) return null;
            int w = 320, hgt = Math.max(1, f.getHeight() * w / Math.max(1, f.getWidth()));
            return Bitmap.createScaledBitmap(f, w, hgt, true);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) view;
            if (cell == null) {
                cell = new LinearLayout(VideoListActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                FrameLayout frame = new FrameLayout(VideoListActivity.this);
                frame.setBackground(Ui.box(VideoListActivity.this, Color.parseColor("#222222"), 0));
                ImageView img = new ImageView(VideoListActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
                TextView badge = new TextView(VideoListActivity.this);
                badge.setTextSize(26);
                badge.setTextColor(Ui.YELLOW);
                badge.setShadowLayer(6, 0, 0, Color.BLACK);
                badge.setGravity(Gravity.CENTER);
                frame.addView(badge, new FrameLayout.LayoutParams(-1, -1));
                cell.addView(frame, new LinearLayout.LayoutParams(-1, Ui.dp(VideoListActivity.this, 100)));
                TextView name = new TextView(VideoListActivity.this);
                name.setTextColor(Ui.BLACK);
                name.setTextSize(13);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setMaxLines(2);
                name.setPadding(0, Ui.dp(VideoListActivity.this, 4), 0, 0);
                cell.addView(name);
                TextView meta = new TextView(VideoListActivity.this);
                meta.setTextColor(Ui.GRAY);
                meta.setTextSize(11);
                cell.addView(meta);
            }
            final Video v = shown.get(i);
            FrameLayout fr = (FrameLayout) cell.getChildAt(0);
            final ImageView img = (ImageView) fr.getChildAt(0);
            ((TextView) fr.getChildAt(1)).setText(v.image ? "" : "▶");
            ((TextView) cell.getChildAt(1)).setText(v.title);
            String meta = v.meta;
            if (v.ref != null) {
                MediaDb.Stat st = MediaDb.get(VideoListActivity.this).stat(v.ref.skey());
                if (st.likes > 0 || st.comments > 0) meta += " · ♥ " + st.likes + " · 💬 " + st.comments;
            }
            ((TextView) cell.getChildAt(2)).setText(meta);
            img.setTag(v.uri);
            Bitmap cached = thumbs.get(v.uri);
            img.setImageBitmap(cached);
            if (cached == null) bg.execute(() -> {
                if (!v.uri.equals(img.getTag())) return; // ячейку уже переиспользовали
                Bitmap b = thumb(v);
                if (b == null) return;
                thumbs.put(v.uri, b);
                main.post(() -> {
                    if (v.uri.equals(img.getTag())) img.setImageBitmap(b);
                });
            });
            return cell;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (menuBtn != null) Nav.badge(this, menuBtn); // ☰• — есть непрочитанные личные
        if (sourceTabs != null) renderSourceTabs(); // источники могли добавить/удалить в меню, чате или по ссылке
        if (local) {
            Updater.onResume(this);
            CrashLog.offer(this);
            Contacts.hello(this, false); // имя — в каталог «Все пользователи» (раз в 6 ч)
            if (permBar != null && permBar.getVisibility() == View.VISIBLE && access() != NONE) loadLocal();
        }
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }
}
