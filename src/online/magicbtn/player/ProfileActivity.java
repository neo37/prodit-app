package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Профиль: аватар (виден в чате), имя, язык приложения, тема. */
public class ProfileActivity extends Activity {

    private static final int REQ_AVATAR = 31;
    private ImageView avatar;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    private void render() {
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
        title.setText(Ui.t(R.string.profile));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        col.addView(header);

        // аватар
        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(Ui.box(this, Ui.YELLOW, 4));
        Bitmap b = Profile.myAvatar(this);
        if (b != null) avatar.setImageBitmap(b);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120));
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        ap.topMargin = Ui.dp(this, 20);
        col.addView(avatar, ap);
        TextView change = new TextView(this);
        change.setText(Ui.t(R.string.avatar_change));
        change.setTextColor(Ui.GRAY);
        change.setGravity(Gravity.CENTER);
        change.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(change);
        android.view.View.OnClickListener pick = v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(Intent.createChooser(i, Ui.t(R.string.avatar_change)), REQ_AVATAR);
        };
        avatar.setOnClickListener(pick);
        change.setOnClickListener(pick);

        // имя
        label(col, Ui.t(R.string.your_name));
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(Social.name(this));
        name.setTextColor(Ui.BLACK);
        col.addView(name);
        TextView save = Ui.button(this, Ui.t(R.string.lock_save), Ui.YELLOW);
        save.setOnClickListener(v -> {
            Social.setName(this, name.getText().toString().trim());
            Toast.makeText(this, Ui.t(R.string.saved), Toast.LENGTH_SHORT).show();
        });
        col.addView(save, lp(8));

        // язык
        label(col, Ui.t(R.string.language));
        TextView lang = Ui.button(this, Look.langTitle(Look.lang(this)), Ui.WHITE);
        lang.setOnClickListener(v -> {
            String[] names = new String[Look.LANGS.length];
            int cur = 0;
            for (int i = 0; i < names.length; i++) {
                names[i] = Look.langTitle(Look.LANGS[i]);
                if (Look.LANGS[i].equals(Look.lang(this))) cur = i;
            }
            new AlertDialog.Builder(this).setTitle(Ui.t(R.string.language))
                    .setSingleChoiceItems(names, cur, (d, w) -> {
                        d.dismiss();
                        Look.set(this, Look.LANGS[w], Look.theme(this));
                        restartApp();
                    }).show();
        });
        col.addView(lang, lp(4));

        // тема
        label(col, Ui.t(R.string.theme));
        TextView theme = Ui.button(this, Look.themeTitle(Look.theme(this)), Ui.WHITE);
        theme.setOnClickListener(v -> {
            String[] names = {Look.themeTitle(Look.CLASSIC), Look.themeTitle(Look.DARK), Look.themeTitle(Look.BP), Look.themeTitle(Look.GLASS),
                    Look.themeTitle(Look.CONSOLE)};
            new AlertDialog.Builder(this).setTitle(Ui.t(R.string.theme))
                    .setSingleChoiceItems(names, Look.theme(this), (d, w) -> {
                        d.dismiss();
                        Look.set(this, Look.lang(this), w);
                        restartApp();
                    }).show();
        });
        col.addView(theme, lp(4));

        TextView settings = Ui.button(this, Ui.t(R.string.settings), Ui.WHITE);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        col.addView(settings, lp(24));

        setContentView(sv);
        Ui.fitSystemBars(this);
    }

    /** Язык и тема применяются ко всем экранам — пересобираем их, возвращаясь в профиль. */
    private void restartApp() {
        Intent home = new Intent(this, VideoListActivity.class).putExtra("mode", "local")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivities(new Intent[]{home, new Intent(this, ProfileActivity.class)});
    }

    private void label(LinearLayout col, String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Ui.GRAY);
        v.setTextSize(13);
        v.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        col.addView(v);
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = Ui.dp(this, top);
        return l;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_AVATAR || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri u = data.getData();
        new Thread(() -> {
            String msg;
            try {
                Profile.setAvatar(this, u);
                msg = Ui.t(R.string.saved);
            } catch (Exception e) {
                msg = Profile.localAvatar(this).exists() ? Ui.t(R.string.avatar_local_only) : Ui.t(R.string.error_prefix, e.getMessage());
            }
            final String m = msg;
            runOnUiThread(() -> {
                Bitmap b = Profile.myAvatar(this);
                if (b != null) avatar.setImageBitmap(b);
                Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
}
