package online.magicbtn.player;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Перехват запросов WebView: медиа, которые грузит страница архива,
 * параллельно пишутся на диск и потом отдаются уже из файла (в том числе офлайн).
 */
public class MediaCache {

    /** Хост источника, открытого как сайт (WebView), — его медиа кэшируются. Адресов в приложении нет. */
    public static volatile String sourceHost = "";

    private static final Set<String> IMAGE_EXT = new HashSet<>(java.util.Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp"));
    private static final Set<String> VIDEO_EXT = new HashSet<>(java.util.Arrays.asList(
            "mp4", "webm", "mov", "m4v", "3gp", "mkv"));

    public interface Listener {
        void onCached(int total, int queued);
    }

    private static MediaCache instance;

    public static synchronized MediaCache get(Context ctx) {
        if (instance == null) instance = new MediaCache(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final MediaDb db;
    private final File dir;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    public volatile String userAgent;

    private MediaCache(Context ctx) {
        this.ctx = ctx;
        this.db = MediaDb.get(ctx);
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        dir = new File(base, "media");
        dir.mkdirs();
        try {
            new File(dir, ".nomedia").createNewFile(); // медиасканер не заходит в папку
        } catch (IOException ignored) {
        }
        final MediaDb d = db;
        bg.execute(d::renameToJ01); // старые .mp4/.jpg → .j01
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ---------- классификация ----------

    /** Метка «хост/папка» для страницы открытого источника; null — страница не источника. */
    public static String labelFor(String pageUrl) {
        if (pageUrl == null) return null;
        Uri u = Uri.parse(pageUrl);
        String host = u.getHost();
        if (host == null || host.isEmpty() || !host.equals(sourceHost)) return null;
        String folder = query(u, "folder");
        if (folder == null) folder = u.getLastPathSegment();
        return host + (folder == null || folder.isEmpty() ? "" : "/" + folder);
    }

    public static String titleFor(String label) {
        if (label == null) return Ui.t(R.string.all_media);
        if (label.startsWith("archive")) return Ui.t(R.string.archive_n, label.substring("archive".length()));
        return label;
    }

    private static String query(Uri u, String name) {
        try {
            return u.isHierarchical() ? u.getQueryParameter(name) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String ext(Uri u) {
        String p = query(u, "file");          // video_api.php?action=stream&file=x.mp4
        if (p == null) p = u.getLastPathSegment();
        if (p == null) return "";
        int i = p.lastIndexOf('.');
        return i < 0 ? "" : p.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isMedia(Uri u) {
        String scheme = u.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.startsWith("/static/")) return false;
        String e = ext(u);
        if (IMAGE_EXT.contains(e) || VIDEO_EXT.contains(e)) return true;
        if ("stream".equals(query(u, "action"))) return true;
        return u.getHost() != null && u.getHost().equals(sourceHost) && path.startsWith("/media/");
    }

    static String guessMime(Uri u, String fallback) {
        String e = ext(u);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (m != null) return m;
        if (VIDEO_EXT.contains(e)) return "video/" + e;
        if (IMAGE_EXT.contains(e)) return "image/" + e;
        return fallback != null ? fallback : "application/octet-stream";
    }

    /** Все офлайн-файлы — с расширением .j01: галерея и другие приложения их не видят и не открывают. */
    private File fileForUrl(String url, Uri u) {
        return new File(dir, sha1(url) + ".j01");
    }

    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ---------- перехват: отдаём уже скачанное из файла ----------

    public WebResourceResponse intercept(WebResourceRequest req, String pageUrl) {
        if (!"GET".equalsIgnoreCase(req.getMethod())) return null;
        Uri u = req.getUrl();
        if (!isMedia(u)) return null;
        String url = u.toString();
        String range = header(req.getRequestHeaders(), "Range");

        File cached = db.fileFor(url);
        if (cached != null) {
            db.tag(url, labelFor(pageUrl));
            try {
                return serveFile(cached, db.mimeFor(url), range);
            } catch (IOException e) {
                return null;
            }
        }

        String label = labelFor(pageUrl);
        if (label == null || !isOnline()) return null; // офлайн/не архив — отдаём как есть
        if (Dl.policy(ctx, Dl.host(label)) != Dl.ALL) return null;

        // Последовательный запрос (видео с начала, картинка): стримим плееру и
        // ОДНОВРЕМЕННО пишем в файл — что проиграно, то и сохранено. Плеер не ждёт
        // полной загрузки: байты идут в него сразу по мере скачивания.
        boolean sequential = range == null || range.replace(" ", "").startsWith("bytes=0-");
        if (sequential) {
            WebResourceResponse tee = teeStream(url, u, label, pageUrl);
            if (tee != null) return tee;
        }
        // перемотка/докачка остатка — пусть играет из сети, а полную копию добираем в фоне
        db.addPending(url, label, pageUrl);
        kick();
        return null;
    }

    /** Отдаёт поток плееру и дублирует его в файл .part; при полном скачивании → в кэш. */
    private WebResourceResponse teeStream(final String url, final Uri u, final String label, String pageUrl) {
        HttpURLConnection c;
        try {
            c = open(url, pageUrl);
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        final File target = fileForUrl(url, u);
        final File tmp = new File(target.getPath() + ".part");
        final String mime = cleanMime(c.getContentType(), u);
        final long expected = c.getContentLengthLong();
        final HttpURLConnection conn = c;
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            InputStream tee = new TeeStream(c.getInputStream(), fos, expected, new TeeStream.Done() {
                @Override
                public void done(boolean complete, long bytes) {
                    conn.disconnect();
                    if (complete && tmp.renameTo(target)) {
                        db.put(url, target, mime, bytes);
                        db.tag(url, label);
                        db.removePending(url);
                        notifyCached();
                    } else {
                        // недосмотрели: оставляем .part и ставим в очередь на докачку
                        db.addPending(url, label, pageUrl2(label));
                        kick();
                    }
                }

                private String pageUrl2(String lbl) {
                    return "https://" + lbl;
                }
            });
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            if (expected > 0) h.put("Content-Length", String.valueOf(expected));
            return new WebResourceResponse(mime, null, 200, "OK", h, tee);
        } catch (IOException e) {
            conn.disconnect();
            tmp.delete();
            return null;
        }
    }

    private static String header(Map<String, String> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    // ---------- «показали в слайдшоу» -> в очередь на скачивание ----------

    /** Вызывается из JS страницы, когда медиа появилось в слайдшоу/просмотрщике. */
    public void seen(String url, String pageUrl) {
        if (url == null) return;
        Uri u = Uri.parse(url);
        if (!isMedia(u)) return;
        String label = labelFor(pageUrl);
        if (label == null) return;
        if (Dl.policy(ctx, Dl.host(label)) != Dl.ALL && db.fileFor(url) == null) return;
        if (db.fileFor(url) != null) {
            db.tag(url, label);
            return;
        }
        db.addPending(url, label, pageUrl);
        kick();
        notifyCached();
    }

    /** Нативный плеер: сохранить просмотренное для офлайна (метка — «хост/папка»). */
    public void save(String url, String label, String page) {
        save(url, label, page, false);
    }

    /** liked — файл лайкнут: при правиле «только лайкнутые» его тоже скачиваем. */
    public void save(String url, String label, String page, boolean liked) {
        if (url == null || label == null || !url.startsWith("http")) return;
        int p = Dl.policy(ctx, Dl.host(label));
        if (db.fileFor(url) == null && (p == Dl.NONE || (p == Dl.LIKED && !liked))) return;
        if (db.fileFor(url) != null) {
            db.tag(url, label);
            return;
        }
        db.addPending(url, label, page);
        kick();
        notifyCached();
    }

    /** 3.4: пока открыт плеер, фоновое скачивание стоит на паузе — длинное видео по сети не тормозит. */
    static volatile boolean playing;
    /** Автосохранение просмотренного видео — не больше этого размера (больше — только смотреть онлайн). */
    static final long AUTO_MAX_BYTES = 300L * 1024 * 1024;

    /** Просмотренное в плеере видео: в очередь с пометкой «авто» (крупнее AUTO_MAX_BYTES не качаем). */
    public void saveWatched(String url, String label, String page, boolean liked) {
        if (url == null || label == null || !url.startsWith("http")) return;
        int p = Dl.policy(ctx, Dl.host(label));
        if (db.fileFor(url) == null && (p == Dl.NONE || (p == Dl.LIKED && !liked))) return;
        ctx.getSharedPreferences("cache_auto", 0).edit().putBoolean(url, true).apply();
        save(url, label, page, liked);
    }

    /** Очередь на скачивание: [url, label]. */
    public java.util.List<String[]> queue() {
        return db.pendingList();
    }

    public void cancel(String url) {
        db.removePending(url);
        File part = new File(fileForUrl(url, Uri.parse(url)).getPath() + ".part");
        part.delete();
        notifyCached();
    }

    /** Уже скачанный файл для URL или null. */
    public File cached(String url) {
        return url == null ? null : db.fileFor(url);
    }

    private final Object loopLock = new Object();
    private boolean looping;

    /** Запустить обработку очереди (если есть сеть и она ещё не крутится). */
    public void kick() {
        synchronized (loopLock) {
            if (looping) return;
            looping = true;
        }
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int failures = 0;
                    while (isOnline() && failures < 3) {
                        MediaDb.Pending p = db.nextPending();
                        if (p == null) break;
                        if (download(p)) {
                            db.removePending(p.url);
                            failures = 0;
                        } else {
                            db.bumpPending(p.url); // в конец очереди, попробуем позже
                            failures++;
                        }
                        notifyCached();
                    }
                } finally {
                    synchronized (loopLock) {
                        looping = false;
                    }
                }
            }
        });
    }

    /** Качает файл целиком, с докачкой недокачанного .part. */
    private boolean download(MediaDb.Pending p) {
        if (db.fileFor(p.url) != null) {
            db.tag(p.url, p.label);
            return true;
        }
        Uri u = Uri.parse(p.url);
        File target = fileForUrl(p.url, u);
        File tmp = new File(target.getPath() + ".part");
        HttpURLConnection c = null;
        try {
            c = open(p.url, p.page);
            long have = tmp.exists() ? tmp.length() : 0;
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            boolean append;
            long total;
            if (code == 206 && have > 0) {
                append = true;
                total = have + c.getContentLengthLong();
            } else if (code == 200) {
                append = false;
                have = 0;
                total = c.getContentLengthLong();
            } else if (code == 416 && have > 0) {
                // уже скачано целиком
                append = true;
                total = have;
            } else {
                return code == 404; // файла больше нет — выкидываем из очереди
            }
            android.content.SharedPreferences auto = ctx.getSharedPreferences("cache_auto", 0);
            if (total > AUTO_MAX_BYTES && auto.getBoolean(p.url, false)) {   // слишком большое для автосохранения
                tmp.delete();
                auto.edit().remove(p.url).apply();
                return true;   // убираем из очереди
            }
            String mime = cleanMime(c.getContentType(), u);
            long n = have;
            if (code != 416) {
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp, append)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        n += r;
                        while (playing) {   // смотрят видео — подождём, соединение подержим
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                return false;
                            }
                        }
                    }
                }
            }
            if (total > 0 && n != total) return false;
            if (!tmp.renameTo(target)) return false;
            auto.edit().remove(p.url).apply();
            db.put(p.url, target, mime, n);
            db.tag(p.url, p.label);
            return true;
        } catch (IOException e) {
            return false; // .part остаётся — докачаем
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private HttpURLConnection open(String url, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        if (referer != null) c.setRequestProperty("Referer", referer);
        return c;
    }

    private static String cleanMime(String ct, Uri u) {
        if (ct != null) {
            int i = ct.indexOf(';');
            String m = (i >= 0 ? ct.substring(0, i) : ct).trim().toLowerCase(Locale.ROOT);
            if (m.startsWith("image/") || m.startsWith("video/")) return m;
        }
        return guessMime(u, ct);
    }

    private void notifyCached() {
        final int total = db.count();
        final int queued = db.pendingCount();
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onCached(total, queued);
            }
        });
    }

    // ---------- отдача из файла с поддержкой Range (перемотка видео) ----------

    public static WebResourceResponse serveFile(File f, String mime, String range) throws IOException {
        long len = f.length();
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "max-age=31536000");
        if (mime == null) mime = "application/octet-stream";
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            long start, end;
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0, len - suffix);
                    end = len - 1;
                } else {
                    start = Long.parseLong(spec.substring(0, dash));
                    String e = spec.substring(dash + 1);
                    end = e.isEmpty() ? len - 1 : Math.min(Long.parseLong(e), len - 1);
                }
            } catch (NumberFormatException ex) {
                start = 0;
                end = len - 1;
            }
            if (start >= len || start > end) {
                headers.put("Content-Range", "bytes */" + len);
                return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers,
                        new java.io.ByteArrayInputStream(new byte[0]));
            }
            FileInputStream in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < start) {
                long s = in.skip(start - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            long n = end - start + 1;
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            headers.put("Content-Length", String.valueOf(n));
            return new WebResourceResponse(mime, null, 206, "Partial Content", headers, new Limited(in, n));
        }
        headers.put("Content-Length", String.valueOf(len));
        return new WebResourceResponse(mime, null, 200, "OK", headers, new FileInputStream(f));
    }

    static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** Ограничивает поток n байтами. */
    static class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long n) {
            super(in);
            left = n;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int r = super.read();
            if (r >= 0) left--;
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = super.read(b, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }

    /** Поток, который всё прочитанное плеером дублирует в файл. */
    static class TeeStream extends FilterInputStream {
        interface Done {
            void done(boolean complete, long bytes);
        }

        private final OutputStream out;
        private final long expected;
        private final Done done;
        private long bytes;
        private boolean eof, writeFailed, finished;

        TeeStream(InputStream in, OutputStream out, long expected, Done done) {
            super(in);
            this.out = out;
            this.expected = expected;
            this.done = done;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r;
            try {
                r = super.read(b, off, len);
            } catch (IOException e) {
                finish();
                throw e;
            }
            if (r < 0) {
                eof = true;
                finish();
            } else if (r > 0 && !writeFailed) {
                try {
                    out.write(b, off, r);
                    bytes += r;
                } catch (IOException e) {
                    writeFailed = true;
                }
            }
            return r;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                finish();
            }
        }

        private synchronized void finish() {
            if (finished) return;
            finished = true;
            try {
                out.close();
            } catch (IOException e) {
                writeFailed = true;
            }
            boolean complete = eof && !writeFailed && bytes > 0 && (expected <= 0 || bytes == expected);
            done.done(complete, bytes);
        }
    }
}
