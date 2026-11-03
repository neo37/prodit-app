package online.magicbtn.player;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.Toast;

import java.util.Collections;

/**
 * 3.4: удалить видео/фото из памяти телефона (MediaStore). Android 11+ — системное окно подтверждения
 * (createDeleteRequest), Android 10 — разрешение на конкретный файл (RecoverableSecurityException),
 * Android 7–9 — WRITE_EXTERNAL_STORAGE. Активность пересылает onActivityResult в onResult().
 */
final class Trash {

    static final int REQ = 4711;
    private static final int REQ_PERM = 4712;
    private static Uri pendingUri;
    private static Runnable pendingDone;

    private Trash() {
    }

    static boolean isPhone(String uri) {
        return uri != null && uri.startsWith("content://media/");
    }

    /** done — после удаления (в UI-потоке); отказ пользователя — ничего не делаем. */
    static void deletePhone(Activity a, Uri u, Runnable done) {
        pendingUri = u;
        pendingDone = done;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent pi = MediaStore.createDeleteRequest(a.getContentResolver(), Collections.singletonList(u));
                a.startIntentSenderForResult(pi.getIntentSender(), REQ, null, 0, 0, 0);
                return;
            }
            if (Build.VERSION.SDK_INT < 29 && a.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_PERM);
                return;   // после разрешения пользователь нажмёт 🗑 ещё раз
            }
            finish(a, a.getContentResolver().delete(u, null, null) > 0);
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT == 29 && e instanceof RecoverableSecurityException) {
                try {
                    a.startIntentSenderForResult(((RecoverableSecurityException) e).getUserAction().getActionIntent()
                            .getIntentSender(), REQ, null, 0, 0, 0);
                    return;
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(a, Ui.t(R.string.error_prefix, e.getMessage()), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, Ui.t(R.string.error_prefix, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** Из onActivityResult активности. true — запрос был наш. */
    static boolean onResult(Activity a, int req, int res) {
        if (req != REQ) return false;
        if (res != Activity.RESULT_OK || pendingUri == null) return true;
        if (Build.VERSION.SDK_INT >= 30) {   // система уже удалила
            finish(a, true);
            return true;
        }
        try {   // Android 10: разрешение получено — удаляем сами
            finish(a, a.getContentResolver().delete(pendingUri, null, null) > 0);
        } catch (Exception e) {
            Toast.makeText(a, Ui.t(R.string.error_prefix, e.getMessage()), Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private static void finish(Activity a, boolean ok) {
        if (!ok) return;
        Toast.makeText(a, Ui.t(R.string.deleted), Toast.LENGTH_SHORT).show();
        Runnable d = pendingDone;
        pendingUri = null;
        pendingDone = null;
        if (d != null) d.run();
    }
}
