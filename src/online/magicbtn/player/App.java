package online.magicbtn.player;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

/**
 * Контекст приложения для строк из ресурсов (Ui.t) и пароль на вход:
 * при запуске и после 30 с в фоне показывается LockActivity, если пароль задан.
 * Пока приложение на экране — личка проверяется каждые несколько секунд (входящие звонки, уведомления, значок),
 * в фоне — DmJob.
 */
public class App extends Application implements Application.ActivityLifecycleCallbacks {
    static App instance;

    private static final long RELOCK_MS = 30_000;
    private static final long DM_POLL_MS = 5_000, DM_POLL_PUSH_MS = 30_000;
    private static int started;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean checking;
    private final Runnable dmPoll = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (started == 0) return;
            if (!checking) {
                checking = true;
                final boolean chat = System.currentTimeMillis() - chatCheckedAt > (Push.active(App.this) ? 60_000 : 15_000);
                if (chat) chatCheckedAt = System.currentTimeMillis();
                new Thread(() -> {
                    Notify.check(App.this, true);
                    if (chat) Inbox.checkChat(App.this, true);   // общий чат (у Production IT — чат команды)   // общий чат — реже
                    checking = false;
                }).start();
            }
            // 3.9: пуши работают — сервер сам скажет о новом, опрос редкий (страховка); без пушей — как раньше
            main.postDelayed(this, Push.active(App.this) ? DM_POLL_PUSH_MS : DM_POLL_MS);
        }
    };

    private static java.lang.ref.WeakReference<Activity> current = new java.lang.ref.WeakReference<>(null);
    private long chatCheckedAt;

    /** Проверить личку и звонки прямо сейчас (Production IT: событие из Centrifugo). */
    static void kick() {
        if (instance != null) instance.main.post(instance.dmPoll);
    }

    /** Экран, который сейчас открыт (для плашки о новом сообщении). */
    static Activity current() {
        return current.get();
    }

    /** Приложение сейчас на экране. */
    static boolean foreground() {
        return started > 0;
    }
    private long backgroundSince;
    static boolean unlocked;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashLog.install(this);
        Look.apply(this);
        registerActivityLifecycleCallbacks(this);
        Notify.channels(this);
        DmJob.schedule(this);
        Push.init(this);   // 3.4: пуши FCM (если в сборке есть ключи Firebase)
        if (Flags.PRODIT) Prodit.startRealtime(this);   // Production IT: мгновенная доставка через Centrifugo
        Billing.init(this);   // 3.6: покупки Google Play (только сборка play)
    }

    @Override
    public void onActivityStarted(Activity a) {
        if (started++ == 0) {
            if (backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > RELOCK_MS) unlocked = false;
            main.postDelayed(dmPoll, 1500);
        }
        if (!unlocked && Lock.enabled(a) && !(a instanceof LockActivity)) {
            a.startActivity(new Intent(a, LockActivity.class));
        }
    }

    @Override
    public void onActivityStopped(Activity a) {
        if (--started == 0) {
            backgroundSince = System.currentTimeMillis();
            main.removeCallbacks(dmPoll);
        }
    }

    /** Тёмная тема для системных диалогов (Android 10+): до super.onCreate активности. */
    @Override
    public void onActivityPreCreated(Activity a, Bundle b) {
        if (Look.dark(a) && !(a instanceof PlayerActivity) && !(a instanceof SlideshowActivity) && !(a instanceof LockActivity))
            a.setTheme(android.R.style.Theme_Material_NoActionBar);
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
        if (Ui.STYLE == Ui.GLASS && !(a instanceof PlayerActivity) && !(a instanceof SlideshowActivity) && !(a instanceof LockActivity))
            a.getWindow().setBackgroundDrawable(Look.glassBackground());
    }

    /** Экраны без 3D-фона: плеер, слайдшоу, пароль, сайт в WebView, диалог ссылки. */
    private static boolean glassScreen(Activity a) {
        return Ui.STYLE == Ui.GLASS && !(a instanceof PlayerActivity) && !(a instanceof SlideshowActivity)
                && !(a instanceof LockActivity) && !(a instanceof MainActivity) && !(a instanceof AddSourceActivity)
                && !(a instanceof IncomingCallActivity);
    }

    @Override
    public void onActivityResumed(Activity a) {
        current = new java.lang.ref.WeakReference<>(a);
        Fonts.attach(a);   // тема «Консоль»: шрифт Onest на всём экране
        if (glassScreen(a)) GlassBg.attach(a); // и после setContentView заново
    }

    @Override
    public void onActivityPaused(Activity a) {
        if (glassScreen(a)) GlassBg.pause(a);
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        GlassBg.destroy(a);
    }
}
