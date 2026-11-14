package com.elive.aqua;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * Stores the Manual/Bypass ON/OFF schedules and arms one AlarmManager alarm per schedule.
 *
 * Schedule JSON (array in prefs key "schedules"):
 *   { "id": 12345, "time": "06:30", "action": "on"|"off", "days": [0..6], "enabled": true }
 * days: 0 = Sunday ... 6 = Saturday.
 */
final class ScheduleManager {

    static final String TAG          = "AquaSchedule";
    static final String ACTION_FIRE  = "com.elive.aqua.SCHEDULE_FIRE";
    static final String CH_SCHED     = "aqua_sched";
    private static final int NOTIF_BASE = 5000;

    private ScheduleManager() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
    }

    // ── storage ────────────────────────────────────────────────
    static String getSchedulesJson(Context c) {
        return p(c).getString("schedules", "[]");
    }

    static void saveSchedules(Context c, String json) {
        try {
            new JSONArray(json); // validate
        } catch (Exception e) {
            Log.e(TAG, "bad schedule json: " + e.getMessage());
            return;
        }
        p(c).edit().putString("schedules", json).apply();
        rescheduleAll(c);
    }

    static JSONObject find(Context c, int id) {
        try {
            JSONArray a = new JSONArray(getSchedulesJson(c));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optInt("id") == id) return o;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── alarms ─────────────────────────────────────────────────
    /** Cancels every previously armed alarm and arms the enabled ones again. */
    static void rescheduleAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        // cancel the ones we armed last time
        Set<String> old = p(c).getStringSet("sched_armed", new HashSet<String>());
        for (String s : old) {
            try { am.cancel(pending(c, Integer.parseInt(s))); } catch (Exception ignored) {}
        }

        Set<String> armed = new HashSet<String>();
        try {
            JSONArray a = new JSONArray(getSchedulesJson(c));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!o.optBoolean("enabled", true)) continue;
                long at = nextTrigger(o, System.currentTimeMillis());
                if (at < 0) continue;
                int id = o.optInt("id");
                arm(c, am, id, at);
                armed.add(String.valueOf(id));
            }
        } catch (Exception e) {
            Log.e(TAG, "reschedule: " + e.getMessage());
        }
        p(c).edit().putStringSet("sched_armed", armed).apply();
    }

    /** Next time (ms) this schedule should fire after 'now', or -1 if never. */
    static long nextTrigger(JSONObject s, long now) {
        try {
            String[] hm = s.optString("time", "00:00").split(":");
            int hh = Integer.parseInt(hm[0].trim());
            int mm = Integer.parseInt(hm[1].trim());
            JSONArray days = s.optJSONArray("days");
            if (days == null || days.length() == 0) return -1;
            boolean[] on = new boolean[7];
            for (int i = 0; i < days.length(); i++) {
                int d = days.getInt(i);
                if (d >= 0 && d <= 6) on[d] = true;
            }
            for (int off = 0; off <= 7; off++) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(now);
                cal.add(Calendar.DAY_OF_YEAR, off);
                cal.set(Calendar.HOUR_OF_DAY, hh);
                cal.set(Calendar.MINUTE, mm);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() > now && on[cal.get(Calendar.DAY_OF_WEEK) - 1]) {
                    return cal.getTimeInMillis();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "nextTrigger: " + e.getMessage());
        }
        return -1;
    }

    private static PendingIntent pending(Context c, int id) {
        Intent i = new Intent(c, ScheduleReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra("id", id);
        return PendingIntent.getBroadcast(c, id, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void arm(Context c, AlarmManager am, int id, long at) {
        PendingIntent pi = pending(c, id);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // Exact alarms not allowed: still fires, but Android may delay it a few minutes.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    // ── run log (shown in the app) ─────────────────────────────
    static void addLog(Context c, String action, boolean ok, String msg) {
        try {
            JSONArray old = new JSONArray(p(c).getString("sched_log", "[]"));
            JSONArray n = new JSONArray();
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("action", action);
            e.put("ok", ok);
            e.put("msg", msg);
            n.put(e);
            for (int i = 0; i < old.length() && n.length() < 20; i++) n.put(old.get(i));
            p(c).edit().putString("sched_log", n.toString()).apply();
        } catch (Exception ignored) {}
    }

    static String getLogJson(Context c) {
        return p(c).getString("sched_log", "[]");
    }

    // ── phone notification ─────────────────────────────────────
    static void notifyResult(Context c, String title, String text, boolean ok) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CH_SCHED, "Schedule results",
                NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = ok ? android.R.drawable.ic_lock_idle_alarm : android.R.drawable.ic_dialog_alert;
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(c, CH_SCHED)
            : new Notification.Builder(c);
        Notification n = b.setSmallIcon(icon)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).build();
        nm.notify(NOTIF_BASE + (int) (System.currentTimeMillis() % 1000), n);
    }
}
