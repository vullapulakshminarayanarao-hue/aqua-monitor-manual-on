package com.elive.aqua;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class AlarmScheduler {
    private static final long INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

    /** Arms the next background check. Always uses a "works while the phone is idle" alarm. */
    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = getPendingIntent(ctx);
        long at = System.currentTimeMillis() + INTERVAL_MS;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Exact alarms not allowed: still use the idle-friendly (not exact) kind
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                Log.d("AquaAlarm", "Scheduled (idle-friendly, not exact)");
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                Log.d("AquaAlarm", "Scheduled exact in 5 min");
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                else am.set(AlarmManager.RTC_WAKEUP, at, pi);
                Log.d("AquaAlarm", "Scheduled (fallback)");
            } catch (Exception e2) { Log.e("AquaAlarm", "could not schedule", e2); }
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(getPendingIntent(ctx));
    }

    private static PendingIntent getPendingIntent(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        return PendingIntent.getBroadcast(ctx, 42, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
