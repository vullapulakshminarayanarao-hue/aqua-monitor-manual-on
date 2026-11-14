package com.elive.aqua;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        Log.d("AquaBoot", "Boot received - starting everything");
        // Start foreground service
        Intent svc = new Intent(ctx, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(svc);
        else
            ctx.startService(svc);
        // Schedule both JobScheduler and AlarmManager
        PollJobService.schedule(ctx);
        AlarmScheduler.schedule(ctx);
        // Re-arm Manual/Bypass schedules (alarms are cleared on reboot / app update)
        ScheduleManager.rescheduleAll(ctx.getApplicationContext());
    }
}
