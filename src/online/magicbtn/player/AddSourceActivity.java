package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.List;

/**
 * Открыта ссылка из мессенджера (или текст со ссылкой через «Поделиться» → Умный плеер):
 *   ссылка на видео — сразу в плеер; «написать мне» — личка с этим человеком;
 *   ссылка на источник — спросить и подключить; любой другой сайт — предложить подключить как источник.
 */
public class AddSourceActivity extends Activity {

    private boolean leaving; // источник открывается (сеть, потом экран) — закрыться, когда он появится

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        Uri data = in.getData();
        String shared = Intent.ACTION_SEND.equals(in.getAction()) ? in.getStringExtra(Intent.EXTRA_TEXT) : null;
        if (data == null && shared != null) data = ShareLink.findIn(shared);
        final ShareLink.Link l = ShareLink.parse(data);
        if (l == null) {
            String web = ShareLink.webIn(shared);
            if (web != null) askSite(web);
            else {
                Toast.makeText(this, Ui.t(R.string.link_bad), Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        if (l.dm != null) {
            if (l.dm.equals(Social.author(this))) {
                Toast.makeText(this, Ui.t(R.string.dm_self), Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, DmListActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Contacts.add(this, l.dm, l.name, null); // ссылка «написать мне» от друга — сразу в контакты
                startActivity(ChatActivity.dm(this, l.dm, l.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            finish();
            return;
        }
        final Sources.Source s = l.source;
        if (l.file != null) {   // ссылка на видео — смотреть сразу, источник подключать не обязательно
            ShareLink.watch(this, l.file, l.title, s.name, s.apiBase(), s.key);
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(Ui.t(R.string.link_add_title))
                .setMessage(s.name + "\n" + Uri.parse(s.url).getHost())
                .setPositiveButton(Ui.t(R.string.connect), (d, w) -> {
                    int at = add(s);
                    Intent home = Nav.home(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    if (s.isApi()) home.putExtra("mode", "api").putExtra("source", at); // сразу его вкладка
                    startActivity(home);
                    finish();
                })
                .setNegativeButton(Ui.t(R.string.cancel), (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (leaving) finish();
    }

    /** Добавить (если ещё нет) и сделать текущим. Возвращает номер источника. */
    private int add(Sources.Source s) {
        List<Sources.Source> l = Sources.all(this);
        int at = -1;
        for (int i = 0; i < l.size(); i++)
            if (l.get(i).url.equals(s.url) && l.get(i).key.equals(s.key)) at = i;
        if (at < 0) {
            l.add(s);
            at = l.size() - 1;
        }
        Sources.save(this, l, at);
        return at;
    }

    /** Поделились обычной ссылкой на сайт: подключить его как источник (найдём у него API или откроем страницу). */
    private void askSite(String url) {
        final Sources.Source s = new Sources.Source(Uri.parse(url).getHost(), url, "");
        new AlertDialog.Builder(this)
                .setTitle(Ui.t(R.string.link_add_title))
                .setMessage(url)
                .setPositiveButton(Ui.t(R.string.connect), (d, w) -> {
                    leaving = true;
                    Nav.openSource(this, add(s));
                })
                .setNegativeButton(Ui.t(R.string.cancel), (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }
}
