package com.elive.aqua;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class MonitorService extends Service {
    private static final int NOTIF_ID = 1001;
    public static volatile boolean isRunning = false;
    private LocalBinder binder;
    private Thread loop;
    private volatile boolean stopLoop = false;

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
        // Three independent triggers, so one being delayed never stops alerts:
        // 1) alarm every 5 min  2) job every 15 min  3) this service's own loop every 3 min
        PollJobService.schedule(this);
        AlarmScheduler.schedule(this);
        startLoop();
        return START_STICKY;
    }

    private void startLoop() {
        if (loop != null && loop.isAlive()) return;
        stopLoop = false;
        loop = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(20 * 1000L); } catch (InterruptedException e) { return; }
                while (!stopLoop) {
                    try { Poller.run(MonitorService.this); }
                    catch (Exception e) { Log.e("AquaService", "poll error", e); }
                    try { Thread.sleep(3 * 60 * 1000L); } catch (InterruptedException e) { return; }
                }
            }
        }, "tw-poll");
        loop.setDaemon(true);
        loop.start();
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
        stopLoop = true;
        if (loop != null) loop.interrupt();
        AlarmScheduler.schedule(this);
        restartSelf();
        super.onDestroy();
    }

    private void restartSelf() {
        // Android 12+ may refuse to start a foreground service from the background.
        // That must never crash us: the alarm and job above keep checking anyway.
        try {
            Intent i = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Log.w("AquaService", "restart not allowed: " + e.getMessage());
        }
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
