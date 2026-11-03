package online.magicbtn.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Входящий звонок: имя и аватар собеседника, мелодия и вибрация, «Принять» (комната efir в браузере)
 * или «Отклонить» (собеседнику — «📵»). Через минуту без ответа экран закрывается сам.
 * extra auto=accept|decline — нажали кнопку прямо в уведомлении.
 */
public class IncomingCallActivity extends Activity {

    private Ringtone ring;
    private Vibrator vib;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String peer, name, url;

    static Intent intent(Context c, String peer, String name, String avatar, String url) {
        return new Intent(c, IncomingCallActivity.class).putExtra("peer", peer).putExtra("name", name)
                .putExtra("avatar", avatar).putExtra("url", url);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        peer = getIntent().getStringExtra("peer");
        name = getIntent().getStringExtra("name");
        url = getIntent().getStringExtra("url");
        if (peer == null || url == null) {
            finish();
            return;
        }
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel("dm", peer.hashCode());
        String auto = getIntent().getStringExtra("auto");
        if ("accept".equals(auto)) {
            accept();
            return;
        }
        if ("decline".equals(auto)) {
            decline();
            return;
        }

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(0xFF15132B);
        int p = Ui.dp(this, 24);
        col.setPadding(p, Ui.dp(this, 90), p, Ui.dp(this, 60));

        ImageView ava = new ImageView(this);
        ava.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ava.setBackground(Ui.box(this, Ui.YELLOW, 0));
        ava.setClipToOutline(true);
        col.addView(ava, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)));
        Profile.loadAvatar(this, getIntent().getStringExtra("avatar"), ava);

        TextView who = new TextView(this);
        who.setText(name == null || name.isEmpty() ? "Guest" : name);
        who.setTextColor(Color.WHITE);
        who.setTextSize(28);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        who.setGravity(Gravity.CENTER);
        who.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 6));
        col.addView(who);
        TextView sub = new TextView(this);
        sub.setText("📞 " + Ui.t(R.string.call_incoming));
        sub.setTextColor(0xFFB8B5D6);
        sub.setTextSize(17);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub);

        col.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        TextView no = Ui.button(this, "✕  " + Ui.t(R.string.call_decline), 0xFFE5484D);
        no.setTextColor(Color.WHITE);
        no.setOnClickListener(v -> decline());
        TextView yes = Ui.button(this, "📞  " + Ui.t(R.string.call_accept), 0xFF30A46C);
        yes.setTextColor(Color.WHITE);
        yes.setOnClickListener(v -> accept());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1);
        bl.leftMargin = bl.rightMargin = Ui.dp(this, 8);
        btns.addView(no, bl);
        btns.addView(yes, new LinearLayout.LayoutParams(bl));
        col.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        setContentView(col);
        Ui.fitSystemBars(this);

        startRinging();
        main.postDelayed(this::finish, Calls.RING_SEC * 1000L); // не ответили — пропущенный (видно в личке)
    }

    private void startRinging() {
        try {
            ring = RingtoneManager.getRingtone(this, RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE));
            if (ring != null) {
                ring.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                if (Build.VERSION.SDK_INT >= 28) ring.setLooping(true);
                ring.play();
            }
        } catch (Exception ignored) {
        }
        try {
            vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long[] pattern = {0, 800, 600};
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pattern, 0));
                else vib.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopRinging() {
        try {
            if (ring != null) ring.stop();
            if (vib != null) vib.cancel();
        } catch (Exception ignored) {
        }
        ring = null;
        vib = null;
    }

    private void accept() {
        stopRinging();
        Calls.markRead(this, peer);
        Calls.open(this, url);
        finish();
    }

    private void decline() {
        stopRinging();
        Calls.markRead(this, peer);
        Calls.decline(this, peer, name);
        finish();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        stopRinging();
        super.onDestroy();
    }
}
