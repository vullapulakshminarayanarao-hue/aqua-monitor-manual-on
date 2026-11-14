package com.elive.aqua;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        Log.d("AquaAlarm", "Fired — running poll");
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Poller.run(ctx);
                } catch (Exception e) {
                    Log.e("AquaAlarm", "poll error", e);
                } finally {
                    result.finish();
                }
                // Schedule next alarm
                AlarmScheduler.schedule(ctx);
            }
        }).start();
    }
}
