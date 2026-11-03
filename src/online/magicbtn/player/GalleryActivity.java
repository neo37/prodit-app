package online.magicbtn.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.ThumbnailUtils;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Офлайн: выбор архива и слайдшоу только из того, что уже скачано. */
public class GalleryActivity extends Activity {

    private static final String PREFS = "slideshow";

    private MediaDb db;
    private String label; // null = все медиа
    private List<MediaDb.Item> items = new ArrayList<>();
    private LinearLayout tabs;
    private TextView info;
    private TextView shuffleBtn, intervalBtn;
    private GridView grid;
    private Adapter adapter;
    private boolean shuffle;
    private int interval;
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final int[] INTERVALS = {3, 5, 8, 15};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = MediaDb.get(this);
        shuffle = getSharedPreferences(PREFS, 0).getBoolean("shuffle", false);
        interval = getSharedPreferences(PREFS, 0).getInt("interval", 5);
        label = getSharedPreferences(PREFS, 0).getString("label", "");
        if ("".equals(label)) label = null;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);

        // шапка как на сайте
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Ui.WHITE);
        int p = Ui.dp(this, 16);
        header.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        TextView logo = new TextView(this);
        logo.setText(android.text.Html.fromHtml("HN<font color='#E0C800'>.</font>"));
        logo.setTextSize(24);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(Ui.BLACK);
        header.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView online = Ui.button(this, Ui.t(R.string.home), Ui.WHITE);
        online.setTextSize(13);
        online.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = Nav.home(GalleryActivity.this);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                finish();
            }
        });
        header.addView(online);
        col.addView(header);
        View line = new View(this);
        line.setBackgroundColor(Ui.BLACK);
        col.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(p, p, p, 0);

        TextView title = new TextView(this);
        title.setText(Ui.t(R.string.gallery_title));
        TextView ver = new TextView(this);
        ver.setText(Ui.t(R.string.version, Updater.currentName(this)));
        ver.setTextColor(Ui.GRAY);
        ver.setTextSize(11);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        body.addView(title);
        body.addView(ver);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        hs.addView(tabs);
        body.addView(hs);

        info = new TextView(this);
        info.setTextColor(Ui.GRAY);
        info.setTextSize(13);
        info.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        body.addView(info);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView play = Ui.button(this, Ui.t(R.string.slideshow), Ui.YELLOW);
        play.setTextSize(17);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSlideshow(0);
            }
        });
        controls.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
        shuffleBtn = Ui.button(this, "", Ui.WHITE);
        shuffleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shuffle = !shuffle;
                save();
                refreshControls();
            }
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = Ui.dp(this, 8);
        controls.addView(shuffleBtn, gap);
        intervalBtn = Ui.button(this, "", Ui.WHITE);
        intervalBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int i = 0;
                while (i < INTERVALS.length && INTERVALS[i] != interval) i++;
                interval = INTERVALS[(i + 1) % INTERVALS.length];
                save();
                refreshControls();
            }
        });
        controls.addView(intervalBtn, new LinearLayout.LayoutParams(gap));
        TextView sortBtn = Ui.button(this, "⇅", Ui.WHITE);
        sortBtn.setOnClickListener(v -> Sort.ask(this, "offline", this::reload));
        controls.addView(sortBtn, new LinearLayout.LayoutParams(gap));
        body.addView(controls);

        TextView split = Ui.button(this, Ui.t(R.string.split), Ui.WHITE);
        split.setTextSize(15);
        split.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askSplit();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(this, 8);
        body.addView(split, sp);
        col.addView(body);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 110));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 6));
        grid.setVerticalSpacing(Ui.dp(this, 6));
        grid.setPadding(p, Ui.dp(this, 14), p, p);
        grid.setClipToPadding(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                openPlayer(pos);
            }
        });
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            itemMenu(items.get(pos));
            return true;
        });
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(col);
        Ui.fitSystemBars(this);
        refreshControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        if (MediaCache.get(this).isOnline()) {
            MediaCache.get(this).kick();
            Updater.onResume(this);
        }
    }

    private void save() {
        getSharedPreferences(PREFS, 0).edit()
                .putBoolean("shuffle", shuffle)
                .putInt("interval", interval)
                .putString("label", label == null ? "" : label)
                .apply();
    }

    private void refreshControls() {
        shuffleBtn.setText(shuffle ? "🔀" : "➡️");
        intervalBtn.setText(Ui.t(R.string.interval_sec, interval));
    }

    private void reload() {
        // источники, из которых что-то сохранено, плюс «Все медиа»
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        labels.addAll(db.labels());
        tabs.removeAllViews();
        for (String l : labels) addTab(l);
        addTab(null);

        items = db.list(label);
        java.util.Collections.sort(items, Sort.by(Sort.get(this, "offline"), new Sort.Keys<MediaDb.Item>() {
            public String name(MediaDb.Item it) { return fileName(it.url); }
            public long time(MediaDb.Item it) { return it.ts; }
            public long size(MediaDb.Item it) { return it.size; }
            public MediaDb.Stat stat(MediaDb.Item it) {
                Social.Ref r = refOf(it);
                return r == null ? new MediaDb.Stat() : db.stat(r.skey());
            }
        }));
        int videos = 0;
        for (MediaDb.Item i : items) if (i.isVideo()) videos++;
        String text = Ui.t(R.string.gallery_counts, items.size() - videos, videos);
        if (label == null) text += " · " + Ui.bytes(db.totalBytes());
        int queued = db.pendingCount();
        if (queued > 0) text += "\n" + Ui.t(R.string.gallery_queued, queued);
        if (items.isEmpty() && queued == 0) text = Ui.t(R.string.gallery_empty);
        info.setText(text);
        adapter.notifyDataSetChanged();
    }

    private void addTab(final String l) {
        boolean active = (l == null) ? label == null : l.equals(label);
        TextView t = Ui.button(this, MediaCache.titleFor(l), active ? Ui.YELLOW : Ui.WHITE);
        t.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(this, 8);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                label = l;
                save();
                reload();
            }
        });
        tabs.addView(t, lp);
    }

    private static String fileName(String url) {
        android.net.Uri u = android.net.Uri.parse(url);
        String f = u.isHierarchical() ? u.getQueryParameter("file") : null;
        return f != null ? f : String.valueOf(u.getLastPathSegment());
    }

    private Social.Ref refOf(MediaDb.Item it) {
        List<String> ls = db.labelsOf(it.url);
        return Social.ref(this, it.url, label != null ? label : ls.isEmpty() ? null : ls.get(0));
    }

    /** Просмотр с лайками/комментариями (офлайн — из файла, отметки уйдут на сервер при появлении сети). */
    private void openPlayer(int pos) {
        if (items.isEmpty()) return;
        int n = items.size();
        PlayerActivity.Playlist pl = new PlayerActivity.Playlist();
        pl.uris = new String[n];
        pl.titles = new String[n];
        pl.labels = new String[n];
        pl.images = new boolean[n];
        for (int i = 0; i < n; i++) {
            MediaDb.Item it = items.get(i);
            pl.uris[i] = it.url;
            pl.titles[i] = fileName(it.url);
            List<String> ls = db.labelsOf(it.url);
            pl.labels[i] = label != null ? label : ls.isEmpty() ? null : ls.get(0);
            pl.images[i] = !it.isVideo();
        }
        pl.photoMs = interval * 1000;
        PlayerActivity.next = pl;
        startActivity(new Intent(this, PlayerActivity.class).putExtra("pos", pos));
    }

    /** Долгое нажатие: лайк, комментарии, переместить в другую подборку, удалить с устройства. */
    private void itemMenu(final MediaDb.Item it) {
        final Social.Ref r = refOf(it);
        final List<String> acts = new ArrayList<>();
        final List<Runnable> run = new ArrayList<>();
        if (r != null) {
            MediaDb.Stat st = db.stat(r.skey());
            acts.add((st.liked ? "♥ " : "♡ ") + Ui.t(R.string.like) + " (" + st.likes + ")");
            run.add(() -> {
                Social.toggleLike(this, r);
                reload();
            });
            acts.add("💬 " + Ui.t(R.string.comments_title) + " (" + st.comments + ")");
            run.add(() -> SocialUi.comments(this, r, this::reload));
        }
        acts.add("📁 " + Ui.t(R.string.move_to));
        run.add(() -> askMove(it));
        acts.add("🗑 " + Ui.t(R.string.delete_from_device));
        run.add(() -> new android.app.AlertDialog.Builder(this)
                .setMessage(Ui.t(R.string.delete_confirm))
                .setPositiveButton(Ui.t(R.string.remove), (d, w) -> {
                    db.delete(it.url);
                    thumbCache.remove(it.url);
                    reload();
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show());
        new android.app.AlertDialog.Builder(this)
                .setTitle(fileName(it.url))
                .setItems(acts.toArray(new String[0]), (d, w) -> run.get(w).run())
                .show();
    }

    private void askMove(final MediaDb.Item it) {
        final List<String> ls = new ArrayList<>(db.labels());
        final List<String> names = new ArrayList<>();
        for (String l : ls) names.add(MediaCache.titleFor(l));
        names.add("＋ " + Ui.t(R.string.new_collection));
        new android.app.AlertDialog.Builder(this)
                .setTitle(Ui.t(R.string.move_to))
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w < ls.size()) {
                        db.move(it.url, label, ls.get(w));
                        reload();
                        return;
                    }
                    final android.widget.EditText e = new android.widget.EditText(this);
                    e.setSingleLine(true);
                    e.setHint(Ui.t(R.string.new_collection));
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(Ui.t(R.string.new_collection))
                            .setView(e)
                            .setPositiveButton("OK", (x, y) -> {
                                String n = e.getText().toString().trim();
                                if (n.isEmpty()) return;
                                db.move(it.url, label, n);
                                reload();
                            })
                            .setNegativeButton(Ui.t(R.string.cancel), null)
                            .show();
                })
                .show();
    }

    private void startSlideshow(int start) {
        if (items.isEmpty()) return;
        if (start == 0) {
            openPlayer(0);
            PlayerActivity.Playlist pl = PlayerActivity.next;
            pl.loop = true;
            pl.shuffle = shuffle;
            pl.photoMs = interval * 1000;
            return;
        }
        Intent i = new Intent(this, SlideshowActivity.class);
        i.putExtra("labels", new String[]{label == null ? "" : label});
        i.putExtra("start", start);
        i.putExtra("shuffle", shuffle && start == 0);
        i.putExtra("interval", interval);
        startActivity(i);
    }

    /** Диалог выбора 2–3 источников для показа на одном экране. */
    private void askSplit() {
        final List<String> src = new ArrayList<>();
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        labels.addAll(db.labels());
        for (String l : labels) {
            if (!db.list(l).isEmpty()) src.add(l);
        }
        src.add(null); // Все медиа

        final String[] titles = new String[src.size()];
        final boolean[] checked = new boolean[src.size()];
        for (int i = 0; i < src.size(); i++) {
            String l = src.get(i);
            int n = db.list(l).size();
            titles[i] = MediaCache.titleFor(l) + " (" + n + ")";
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle(Ui.t(R.string.split_title))
                .setMultiChoiceItems(titles, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton(Ui.t(R.string.start), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        List<String> chosen = new ArrayList<>();
                        for (int i = 0; i < checked.length && chosen.size() < 3; i++) {
                            if (checked[i]) {
                                String l = src.get(i);
                                if (!db.list(l).isEmpty()) chosen.add(l == null ? "" : l);
                            }
                        }
                        if (chosen.isEmpty()) {
                            Toast.makeText(GalleryActivity.this, Ui.t(R.string.split_pick), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Intent i = new Intent(GalleryActivity.this, SlideshowActivity.class);
                        i.putExtra("labels", chosen.toArray(new String[0]));
                        i.putExtra("shuffle", shuffle);
                        i.putExtra("interval", interval);
                        startActivity(i);
                    }
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int pos) {
            return items.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            FrameLayout cell;
            ImageView img;
            TextView mark;
            if (convert == null) {
                cell = new Square(GalleryActivity.this);
                cell.setBackground(Ui.box(GalleryActivity.this, Ui.WHITE, 0));
                int b = Ui.dp(GalleryActivity.this, 2);
                cell.setPadding(b, b, b, b);
                img = new ImageView(GalleryActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));
                mark = new TextView(GalleryActivity.this);
                mark.setText("▶");
                mark.setTextColor(Ui.BLACK);
                mark.setTypeface(Typeface.DEFAULT_BOLD);
                mark.setBackgroundColor(Ui.YELLOW);
                mark.setPadding(Ui.dp(GalleryActivity.this, 6), 0, Ui.dp(GalleryActivity.this, 6), 0);
                cell.addView(mark, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
                cell.setTag(new Object[]{img, mark});
            } else {
                cell = (FrameLayout) convert;
            }
            Object[] h = (Object[]) cell.getTag();
            img = (ImageView) h[0];
            mark = (TextView) h[1];
            final MediaDb.Item it = items.get(pos);
            mark.setVisibility(it.isVideo() ? View.VISIBLE : View.GONE);
            final String key = it.file.getPath();
            img.setTag(key);
            Bitmap bm = thumbCache.get(key);
            img.setImageBitmap(bm);
            if (bm == null) {
                final ImageView target = img;
                thumbs.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Bitmap b = thumb(it);
                        if (b == null) return;
                        thumbCache.put(key, b);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (key.equals(target.getTag())) target.setImageBitmap(b);
                            }
                        });
                    }
                });
            }
            return cell;
        }
    }

    /** Квадратная ячейка сетки. */
    static class Square extends FrameLayout {
        Square(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    @SuppressWarnings("deprecation")
    static Bitmap thumb(MediaDb.Item it) {
        try {
            if (it.isVideo()) {
                return ThumbnailUtils.createVideoThumbnail(it.file.getPath(), MediaStore.Images.Thumbnails.MINI_KIND);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.file.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 240 && o.outHeight / (s * 2) >= 240) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(it.file.getPath(), o);
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        thumbs.shutdownNow();
        super.onDestroy();
    }
}
