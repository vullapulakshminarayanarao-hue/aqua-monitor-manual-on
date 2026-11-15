package com.elive.aqua;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

public class MonitorService extends Service {
    private static final int NOTIF_ID = 1001;
    public static volatile boolean isRunning = false;
    private LocalBinder binder;

    public static class LocalBinder extends Binder {
        private final MonitorService svc;
        LocalBinder(MonitorService s) { svc = s; }
        public MonitorService getService() { return svc; }
    }

    @Override public void onCreate() {
        super.onCreate();
        binder = new LocalBinder(this);
        isRunning = true;
        Poller.createChannels(this);
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        startForeground(NOTIF_ID, buildNotif());
        // Schedule both mechanisms every time service starts
        PollJobService.schedule(this);
        AlarmScheduler.schedule(this);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return binder; }

    @Override public void onTaskRemoved(Intent r) {
        // App swiped — reschedule alarms and restart
        AlarmScheduler.schedule(this);
        PollJobService.schedule(this);
        restartSelf();
        super.onTaskRemoved(r);
    }

    @Override public void onDestroy() {
        isRunning = false;
        AlarmScheduler.schedule(this);
        restartSelf();
        super.onDestroy();
    }

    private void restartSelf() {
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(i);
        else
            startService(i);
    }

    private Notification buildNotif() {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, Poller.CH_SVC)
                .setContentTitle("Trust Water Controller")
                .setContentText("Monitoring water levels")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi).setOngoing(true).build();
        }
        return new Notification.Builder(this)
            .setContentTitle("Trust Water Controller")
            .setContentText("Monitoring water levels")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(Notification.PRIORITY_MIN)
            .setContentIntent(pi).setOngoing(true).build();
    }
}
