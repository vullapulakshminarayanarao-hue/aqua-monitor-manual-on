package com.elive.aqua;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

public class PollJobService extends JobService {

    private static final int JOB_ID = 1234;
    private Thread pollThread;

    public static void schedule(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        // Check if already scheduled
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == JOB_ID) {
                Log.d("AquaJob", "Job already scheduled");
                return;
            }
        }
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, PollJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60 * 1000L)  // 15 min
            .setPersisted(true)             // survives reboot
            .build();
        int result = js.schedule(job);
        Log.d("AquaJob", "Scheduled: " + (result == JobScheduler.RESULT_SUCCESS ? "OK" : "FAIL"));
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.d("AquaJob", "Job fired");
        pollThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Poller.run(PollJobService.this);
                } catch (Exception e) {
                    Log.e("AquaJob", "poll error", e);
                }
                jobFinished(params, false);
            }
        });
        pollThread.start();
        return true; // async
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (pollThread != null) pollThread.interrupt();
        return true; // reschedule
    }
}
