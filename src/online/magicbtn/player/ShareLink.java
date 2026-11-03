package online.magicbtn.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ссылки для Telegram, WhatsApp и любых мессенджеров:
 *   источник — https://magicbtn.online/ai-video/add.htm#n=<имя>&u=<адрес>&k=<ключ>
 *   видео    — то же + &f=<адрес файла>&t=<название> (открывается сразу в плеере)
 *   личка    — https://magicbtn.online/ai-video/add.htm#dm=<номер автора>&n=<имя> («написать мне»)
 * Данные — после «#»: на сервер не уходят и в логи не попадают. Открывает приложение (AddSourceActivity),
 * без приложения — страница с кнопкой «Скачать».
 */
final class ShareLink {

    static final String BASE = "https://magicbtn.online/ai-video/add.htm";
    private static final Pattern OURS = Pattern.compile("(https://magicbtn\\.online/ai-video/add\\.htm|smartplayer://add)[^\\s]*");
    private static final Pattern WEB = Pattern.compile("https://[^\\s]+");

    /** Разобранная ссылка: источник (может не быть у «лички»), видео в нём, собеседник. */
    static final class Link {
        Sources.Source source;
        String file, title, dm, name;
    }

    private ShareLink() {
    }

    static String link(Sources.Source s) {
        return BASE + "#n=" + Uri.encode(s.name) + "&u=" + Uri.encode(s.url) + (s.key.isEmpty() ? "" : "&k=" + Uri.encode(s.key));
    }

    static String videoLink(String name, String api, String key, String file, String title) {
        return BASE + "#n=" + Uri.encode(name) + "&u=" + Uri.encode(api) + (key == null || key.isEmpty() ? "" : "&k=" + Uri.encode(key))
                + "&f=" + Uri.encode(file) + (title == null ? "" : "&t=" + Uri.encode(title));
    }

    static String dmLink(Context c) {
        String n = Social.name(c);
        return BASE + "#dm=" + Social.author(c) + (n.isEmpty() ? "" : "&n=" + Uri.encode(n));
    }

    static void send(Activity a, Sources.Source s) {
        text(a, Ui.t(R.string.share_link_text, s.name) + "\n" + link(s));
    }

    /** Системное «Поделиться» (Telegram, WhatsApp, почта…). */
    static void text(Activity a, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        a.startActivity(Intent.createChooser(i, Ui.t(R.string.share_link)));
    }

    /** Наша ссылка в тексте (сообщение чата, «Поделиться» из мессенджера). */
    static Uri findIn(String text) {
        if (text == null) return null;
        Matcher m = OURS.matcher(text);
        return m.find() ? Uri.parse(m.group()) : null;
    }

    /** Любая https-ссылка в тексте — например, сайт, которым поделились из браузера. */
    static String webIn(String text) {
        if (text == null) return null;
        Matcher m = WEB.matcher(text);
        return m.find() ? m.group().replaceAll("[).,!?»”]+$", "") : null;
    }

    /** Разбор ссылки (https://…/add.htm#… или smartplayer://add#… / ?…). null — не наша ссылка. */
    static Link parse(Uri u) {
        if (u == null) return null;
        String data = u.getEncodedFragment();
        if (data == null || data.isEmpty()) data = u.getEncodedQuery();
        if (data == null) return null;
        String url = null, k = "";
        Link l = new Link();
        for (String kv : data.split("&")) {
            int e = kv.indexOf('=');
            if (e < 0) continue;
            String key = kv.substring(0, e), val = Uri.decode(kv.substring(e + 1));
            if ("n".equals(key)) l.name = val;
            else if ("u".equals(key)) url = val;
            else if ("k".equals(key)) k = val;
            else if ("f".equals(key) && val.startsWith("https://")) l.file = val;
            else if ("t".equals(key)) l.title = val;
            else if ("dm".equals(key) && val.matches("[a-f0-9]{16}")) l.dm = val;
        }
        if (url != null && url.startsWith("https://"))
            l.source = new Sources.Source(l.name == null || l.name.isEmpty() ? Uri.parse(url).getHost() : l.name, url, k);
        return l.source != null || l.dm != null ? l : null;
    }

    /** Открыть видео в плеере (с лайками и комментариями). Адрес — как в сообщении: папка в ?folder= или #f=. */
    static void watch(Activity a, String file, String title, String sourceName, String api, String key) {
        Uri u = Uri.parse(file);
        String folder = u.getQueryParameter("folder");
        String frag = u.getFragment();
        if (folder == null && frag != null && frag.startsWith("f=")) folder = frag.substring(2);
        if (frag != null) file = file.substring(0, file.indexOf('#'));
        PlayerActivity.Playlist pl = new PlayerActivity.Playlist();
        pl.uris = new String[]{file};
        pl.titles = new String[]{title == null || title.isEmpty() ? u.getLastPathSegment() : title.replaceFirst("^🎬 ", "")};
        pl.labels = new String[]{u.getHost() + "/" + (folder != null ? folder : "chat")};
        String lower = file.toLowerCase(java.util.Locale.ROOT);
        pl.images = new boolean[]{lower.matches(".*\\.(jpe?g|png|gif|webp)$") || lower.matches(".*file=[^&]*\\.(jpe?g|png|gif|webp)(&.*)?$")};
        pl.key = key;
        pl.page = api;
        pl.sourceName = sourceName;
        PlayerActivity.next = pl;
        Intent i = new Intent(a, PlayerActivity.class);
        if (a instanceof AddSourceActivity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // пришли из мессенджера — в задачу приложения
        a.startActivity(i);
    }
}
