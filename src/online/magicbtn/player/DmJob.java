package online.magicbtn.player;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Фоновая проверка лички, когда приложение закрыто: Android запускает её примерно раз в 15 минут (чаще не даёт). */
public class DmJob extends JobService {

    private static final int ID = 7301;

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(ID) != null) return;
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, DmJob.class))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            if (Notify.enabled(this)) {
                Notify.check(this, App.foreground());
                Inbox.checkChat(this, App.foreground());   // 3.4: общий чат — тоже
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
