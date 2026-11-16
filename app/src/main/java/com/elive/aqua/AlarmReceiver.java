package com.elive.aqua;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        Log.d("AquaAlarm", "Fired - running poll");
        // Arm the NEXT alarm first, so the chain keeps going even if this check is cut short.
        AlarmScheduler.schedule(ctx);

        final PendingResult result = goAsync();
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrustWaterController::Poll");
            wl.acquire(45 * 1000L);
        } catch (Exception ignored) {}
        final PowerManager.WakeLock lock = wl;

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Poller.run(ctx);
                } catch (Exception e) {
                    Log.e("AquaAlarm", "poll error", e);
                } finally {
                    try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
                    result.finish();
                }
            }
        }).start();
    }
}
