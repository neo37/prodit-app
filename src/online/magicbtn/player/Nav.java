package online.magicbtn.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Бургер-меню ☰ — боковая панель: профиль, видео на телефоне, источники (тап — открыть, долгий — изменить,
 * «📤» — ссылка другу), офлайн, мой источник, чат, личные сообщения, контакты, идея, настройки, тема.
 */
final class Nav {

    private Nav() {
    }

    /** Кнопка ☰ для шапки экрана; есть непрочитанные личные — «☰•». */
    static TextView button(final Activity a) {
        final TextView b = Ui.button(a, "☰", Ui.WHITE);
        b.setOnClickListener(v -> open(a));
        badge(a, b);
        return b;
    }

    static void badge(final Activity a, final TextView b) {
        new Thread(() -> DmListActivity.refreshUnread(a, n -> a.runOnUiThread(() -> paint(b)))).start();
        Inbox.listener = () -> paint(b);   // новое сообщение — число на ☰ обновится сразу (3.4)
    }

    /** ☰ с числом непрочитанного (личка + общий чат), подсвеченная. */
    static void paint(TextView b) {
        int n = Inbox.total();
        b.setText(n > 0 ? "☰ " + (n > 99 ? "99+" : n) : "☰");
        if (b.getTag() == null) b.setTag(b.getCurrentTextColor());   // цвет темы — вернуть, когда всё прочитано
        b.setTextColor(n > 0 ? 0xFFFF5A5F : (Integer) b.getTag());
    }

    static void open(final Activity a) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.STYLE == Ui.GLASS ? 0 : Ui.BG);
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 16);
        col.setPadding(p, Ui.dp(a, 36), p, p);
        sv.addView(col);

        // профиль
        LinearLayout prof = new LinearLayout(a);
        prof.setGravity(Gravity.CENTER_VERTICAL);
        prof.setPadding(0, 0, 0, Ui.dp(a, 14));
        ImageView ava = new ImageView(a);
        ava.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ava.setBackground(Ui.box(a, Ui.YELLOW, 2));
        Bitmap b = Profile.myAvatar(a);
        if (b != null) ava.setImageBitmap(b);
        prof.addView(ava, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52)));
        TextView nm = new TextView(a);
        String n = Social.name(a);
        nm.setText((n.isEmpty() ? Ui.t(R.string.profile_set_name) : n) + "\n" + Ui.t(R.string.profile));
        nm.setTextColor(Ui.BLACK);
        nm.setTextSize(15);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setPadding(Ui.dp(a, 12), 0, 0, 0);
        prof.addView(nm);
        prof.setOnClickListener(v -> go(a, d, new Intent(a, ProfileActivity.class)));
        col.addView(prof);

        // 3.7: проще — сверху частое, редкое под «⋯ Ещё»
        item(a, col, Ui.t(R.string.home2_menu), false, v -> go(a, d, new Intent(a, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)), null);
        item(a, col, Ui.t(R.string.home_phone), true, v -> go(a, d, home(a)), null);
        item(a, col, Ui.t(R.string.home_chat) + (Inbox.chatUnread > 0 ? "  • " + Inbox.chatUnread : ""), false,
                v -> go(a, d, new Intent(a, ChatActivity.class)), null);
        LinearLayout dm = item(a, col, Ui.t(R.string.home_dm), false, v -> go(a, d, new Intent(a, DmListActivity.class)), null);
        final TextView dmText = (TextView) dm.getChildAt(0);
        new Thread(() -> DmListActivity.refreshUnread(a, cnt -> a.runOnUiThread(() -> {
            if (cnt > 0) dmText.setText(Ui.t(R.string.home_dm_n, cnt));
        }))).start();
        item(a, col, Ui.t(R.string.game_menu), false, v -> go(a, d, new Intent(a, GameActivity.class).putExtra("continue", true)), null);
        item(a, col, Ui.t(R.string.tour_menu_item), false, v -> { d.dismiss(); Guide.open(a); }, null);   // 3.12: продолжить обучение или справочник

        section(a, col, Ui.t(R.string.sources));
        List<Sources.Source> l = Sources.all(a);
        for (int i = 0; i < l.size(); i++) {
            final int idx = i;
            final Sources.Source s = l.get(i);
            LinearLayout row = item(a, col, (s.isApi() ? "🎬  " : "🌐  ") + s.name, false, v -> {
                d.dismiss();
                openSource(a, idx);
            }, v -> {
                d.dismiss();
                Sources.edit(a, idx, x -> { });
                return true;
            });
            if (s.isApi()) {
                TextView share = new TextView(a);
                share.setText("📤");
                share.setTextSize(18);
                share.setPadding(Ui.dp(a, 8), 0, 0, 0);
                share.setOnClickListener(v -> ShareLink.send(a, s));
                row.addView(share);
            }
            TextView del = new TextView(a);
            del.setText("🗑");
            del.setTextSize(18);
            del.setPadding(Ui.dp(a, 10), 0, 0, 0);
            del.setOnClickListener(v -> {
                d.dismiss();
                Sources.remove(a, idx, () -> { });
            });
            row.addView(del);
        }
        item(a, col, Ui.t(R.string.home_add), false, v -> {
            d.dismiss();
            Sources.edit(a, -1, x -> { });
        }, null);

        // ⋯ Ещё — редкое, свёрнуто
        final LinearLayout more = new LinearLayout(a);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setVisibility(android.view.View.GONE);
        final LinearLayout moreRow = item(a, col, Ui.t(R.string.menu_more), false, null, null);
        moreRow.setOnClickListener(v -> {
            boolean open = more.getVisibility() != android.view.View.VISIBLE;
            more.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
            ((TextView) moreRow.getChildAt(0)).setText(Ui.t(open ? R.string.menu_less : R.string.menu_more));
        });
        col.addView(more);
        item(a, more, Ui.t(R.string.host_menu), false, v -> {   // 3.4: свой сайт → источник (файлы PHP/Python)
            d.dismiss();
            Hosting.open(a);
        }, null);

        item(a, more, Ui.t(R.string.home_offline), false, v -> go(a, d, new Intent(a, GalleryActivity.class)), null);
        item(a, more, Ui.t(R.string.rec_menu), false, v -> go(a, d, new Intent(a, RecordingsActivity.class)), null);
        item(a, more, Ui.t(R.string.home_my), false, v -> go(a, d, new Intent(a, MySourceActivity.class)), null);
        item(a, more, Ui.t(R.string.home_contacts), false, v -> go(a, d, new Intent(a, ContactsActivity.class)), null);
        item(a, more, Ui.t(R.string.home_idea), false, v -> go(a, d, new Intent(a, IdeaActivity.class)), null);
        item(a, more, Ui.t(R.string.home_settings), false, v -> go(a, d, new Intent(a, SettingsActivity.class)), null);
        item(a, more, "🎨  " + Ui.t(R.string.theme) + ": " + Look.themeTitle(Look.theme(a)), false, v -> {
            d.dismiss();
            String[] names = new String[Look.COUNT];
            for (int i = 0; i < names.length; i++) names[i] = Look.themeTitle(i);
            new android.app.AlertDialog.Builder(a).setTitle(Ui.t(R.string.theme))
                    .setSingleChoiceItems(names, Look.theme(a), (dd, w) -> {
                        dd.dismiss();
                        Look.set(a, Look.lang(a), w);
                        a.startActivity(home(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    }).show();
        }, null);
        TextView ver = new TextView(a);
        ver.setText(Ui.t(R.string.version, Updater.currentName(a)));
        ver.setTextColor(Ui.GRAY);
        ver.setTextSize(11);
        ver.setPadding(0, Ui.dp(a, 12), 0, 0);
        if (!Flags.PLAY) ver.setOnLongClickListener(v -> {   // 3.9: вход владельца в админ-режим — только в сборке с сайта
            d.dismiss();
            Admin.open(a);
            return true;
        });
        col.addView(ver);

        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            // в «Авроре» фон экрана прозрачный (под ним 3D) — у меню своё «матовое стекло»
            w.setBackgroundDrawable(new ColorDrawable(Ui.STYLE == Ui.GLASS ? 0xF01B1446 : Ui.BG));
            w.setGravity(Gravity.START | Gravity.TOP);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = Math.min(Ui.dp(a, 340), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.85));
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.windowAnimations = android.R.style.Animation_Translucent;
            w.setAttributes(lp);
        }
        d.show();
        Fonts.attach(d.getWindow());   // «Консоль»: шрифт и значки и в меню
    }

    static Intent home(Activity a) {
        return new Intent(a, VideoListActivity.class).putExtra("mode", "local");
    }

    private static void go(Activity a, Dialog d, Intent i) {
        d.dismiss();
        if (VideoListActivity.class.getName().equals(i.getComponent().getClassName()) && "local".equals(i.getStringExtra("mode")))
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(i);
    }

    private static void section(Activity a, LinearLayout col, String t) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextColor(Ui.GRAY);
        v.setTextSize(12);
        v.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 4));
        col.addView(v);
    }

    private static LinearLayout item(Activity a, LinearLayout col, String t, boolean primary,
                                     View.OnClickListener click, View.OnLongClickListener longClick) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.button(a, primary ? Ui.YELLOW : Ui.WHITE));
        int p = Ui.dp(a, 12);
        r.setPadding(p, Ui.dp(a, 11), p + Ui.dp(a, 3), Ui.dp(a, 14));
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(primary ? Ui.ON_ACCENT : Ui.BLACK);
        tv.setSingleLine(true);
        r.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        r.setClickable(true);
        r.setOnClickListener(click);
        if (longClick != null) r.setOnLongClickListener(longClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(a, 8);
        col.addView(r, lp);
        return r;
    }

    /**
     * Открыть источник: video_api.php — экран видео; адрес сайта — сначала ищем у него video_api.php
     * (тогда тот же экран и адрес запоминается), иначе — как веб-страницу.
     */
    static void openSource(final Activity a, final int idx) {
        final Sources.Source s = Sources.get(a, idx);
        if (s == null) return;
        final List<Sources.Source> l = Sources.all(a);
        Sources.save(a, l, idx);
        if (s.isApi()) {
            launch(a, idx, true);
            return;
        }
        Toast.makeText(a, Ui.t(R.string.connecting), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String api = Sources.probeApi(s.url, s.key);
            a.runOnUiThread(() -> {
                if (api != null) {
                    s.url = api;
                    l.set(idx, s);
                    Sources.save(a, l, idx);
                }
                launch(a, idx, api != null);
            });
        }).start();
    }

    private static void launch(Activity a, int idx, boolean api) {
        // источник с API — вкладка на главном экране (тот же экран, onNewIntent), сайт — WebView
        Intent i = api ? new Intent(a, VideoListActivity.class).putExtra("mode", "api")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP) : new Intent(a, MainActivity.class);
        if (a instanceof AddSourceActivity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i.putExtra("source", idx));
    }
}
