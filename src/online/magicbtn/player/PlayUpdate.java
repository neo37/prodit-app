package online.magicbtn.player;

import android.app.Activity;

import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;

/**
 * 3.6: версия из Google Play — при запуске предложить обновиться (стандартное окно Play «Доступно обновление»).
 * Версию с сайта обновляет Updater (version.json). Раз за запуск приложения.
 */
final class PlayUpdate {

    private static boolean asked;

    private PlayUpdate() {
    }

    static void check(final Activity a) {
        if (!Flags.PLAY || asked) return;
        asked = true;
        try {
            final AppUpdateManager m = AppUpdateManagerFactory.create(a);
            m.getAppUpdateInfo().addOnSuccessListener(info -> {
                int av = info.updateAvailability();
                if ((av == UpdateAvailability.UPDATE_AVAILABLE || av == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
                        && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    m.startUpdateFlow(info, a, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build());
                }
            });
        } catch (Throwable ignored) {   // нет Google Play на устройстве
        }
    }
}
