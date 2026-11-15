package com.elive.aqua;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import org.json.JSONObject;

/** Fires when a Manual/Bypass schedule is due and sends the ON/OFF command. */
public class ScheduleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final int id = intent.getIntExtra("id", -1);
        final long due = intent.getLongExtra("at", 0L);
        final Context app = ctx.getApplicationContext();

        // Arm the next occurrence right away (cheap, synchronous).
        ScheduleManager.rescheduleAll(app);

        // Keep the monitoring service (and therefore the process) alive while we work.
        try {
            Intent svc = new Intent(app, MonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(svc);
            else app.startService(svc);
        } catch (Exception e) {
            Log.w("AquaSchedule", "service start: " + e.getMessage());
        }

        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm == null ? null
            : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AquaMonitor::Schedule");
        if (wl != null) wl.acquire(90000L);

        new Thread(new Runnable() {
            public void run() {
                try {
                    execute(app, id, due);
                } finally {
                    if (wl != null && wl.isHeld()) wl.release();
                }
            }
        }).start();
    }

    /** An alarm that runs later than this (phone off / asleep) is dropped, never replayed. */
    private static final long MAX_LATE_MS = 10 * 60 * 1000L;

    private void execute(Context app, int id, long due) {
        JSONObject s = ScheduleManager.find(app, id);
        if (s == null || !s.optBoolean("enabled", true)) return;

        boolean on = "on".equals(s.optString("action", "off"));
        String label = on ? "Manual/Bypass ON" : "Manual/Bypass OFF";

        // 1. Too late? (phone was off or asleep) -> do nothing, wait for the next schedule.
        long late = due > 0 ? System.currentTimeMillis() - due : 0;
        if (late > MAX_LATE_MS) {
            skip(app, on, label, "Skipped - ran " + (late / 60000L) + " min late, not applied", true);
            return;
        }

        // 2. Power present? Main tank below the limit (for ON)?
        BypassGuard.Result g = BypassGuard.evaluate(app, on);
        if (!g.allowed) {
            skip(app, on, label, "Skipped - " + g.reason, g.warn);
            return;
        }

        // 3. Send. Do not keep retrying if the device went offline (no power).
        String err = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            err = ControlClient.sendBypass(app, on);
            if (err == null || err.toLowerCase().contains("offline")
                    || err.toLowerCase().contains("did not respond")) break;
            try { Thread.sleep(5000L); } catch (InterruptedException ignored) {}
        }

        boolean ok = err == null;
        String msg = ok ? "Sent at " + s.optString("time") + " - " + g.reason : "FAILED: " + err;
        ScheduleManager.addLog(app, on ? "on" : "off", ok, msg);
        ScheduleManager.notifyResult(app, (ok ? "✅ " : "⚠️ ") + label, msg, ok);
        ControlClient.telegram(app, (ok ? "✅ " : "⚠️ ") + "Scheduled " + label + " - " + msg);
    }

    private static void skip(Context app, boolean on, String label, String msg, boolean warn) {
        ScheduleManager.addLog(app, on ? "on" : "off", !warn, msg);
        ScheduleManager.notifyResult(app, (warn ? "⚠️ " : "⏭️ ") + label, msg, !warn);
        ControlClient.telegram(app, (warn ? "⚠️ " : "⏭️ ") + "Scheduled " + label + " - " + msg);
    }
}
