package online.magicbtn.player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 3.4: «Поделиться → Умный плеер» из галереи/камеры: видео грузится в хранилище и уходит в общий чат или в личку.
 * Экран остаётся открытым до конца загрузки: доступ к чужому файлу даётся только пока он открыт.
 */
public class ShareInActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent i = getIntent();
        Uri u = Intent.ACTION_SEND.equals(i.getAction()) ? i.getParcelableExtra(Intent.EXTRA_STREAM) : null;
        String type = i.getType() != null ? i.getType() : (u != null ? getContentResolver().getType(u) : null);
        if (u == null || type == null || !type.startsWith("video/")) {
            Toast.makeText(this, Ui.t(R.string.share_not_video), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        TextView t = new TextView(this);
        t.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        t.setText(Ui.t(R.string.share_to_title) + "…");
        setContentView(t);
        if (state == null) ShareVideo.pick(this, u, null, () -> {
            t.setText(Ui.t(R.string.share_done));
            finish();
        });
    }
}
