package com.elive.aqua;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import org.json.JSONObject;

public class NativeBridge {
    private final Context ctx;
    NativeBridge(Context ctx) { this.ctx = ctx; }

    private SharedPreferences p() {
        return ctx.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public void saveServer(String host, String email, String pass) {
        SharedPreferences.Editor e = p().edit()
                  .putString("tb_host", host.trim())
                  .putString("tb_email", email.trim());
        // never wipe the saved password with an empty one (background checks need it)
        if (pass != null && !pass.trim().isEmpty()) e.putString("tb_password", pass.trim());
        e.apply();
        toast("Server saved ✓");
    }

    @JavascriptInterface
    public void saveTelegram(String token, String chatId) {
        p().edit().putString("tg_token", token.trim())
                  .putString("tg_chatid", chatId.trim()).apply();
        toast("Telegram saved ✓");
    }

    @JavascriptInterface
    public void saveThreshold(int pct) {
        p().edit().putInt("threshold", pct).apply();
    }

    @JavascriptInterface
    public void saveThresholdMap(String json) {
        p().edit().putString("threshold_map", json).apply();
        toast("Thresholds saved ✓");
    }

    @JavascriptInterface
    public void saveCooldownMinutes(int mins) {
        p().edit().putInt("cooldown_minutes", Math.max(1, Math.min(1440, mins))).apply();
        toast("Cooldown saved ✓");
    }

    /** What the background (app-closed) alert check has and what it did last. */
    @JavascriptInterface
    public String getBackgroundStatus() {
        try {
            SharedPreferences sp = p();
            JSONObject o = new JSONObject();
            o.put("telegram", !sp.getString("tg_token", "").trim().isEmpty() && !sp.getString("tg_chatid", "").trim().isEmpty());
            o.put("login",    !sp.getString("tb_email", "").trim().isEmpty() && !sp.getString("tb_password", "").trim().isEmpty());
            o.put("device",   !sp.getString("ctl_device_id", "").trim().isEmpty());
            o.put("lastTs",   sp.getLong("bg_last_ts", 0));
            o.put("lastMsg",  safe(sp.getString("bg_last_msg", "")));
            o.put("threshold", sp.getInt("threshold", 30));
            o.put("thresholdMap", safe(sp.getString("threshold_map", "{}")));
            o.put("cooldownMins", sp.getInt("cooldown_minutes", 60));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** What Android currently allows this app to do in the background. */
    @JavascriptInterface
    public String getReliability() {
        try {
            JSONObject o = new JSONObject();
            boolean battery = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                battery = pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
            }
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                exact = am != null && am.canScheduleExactAlarms();
            }
            boolean notif = true;
            if (Build.VERSION.SDK_INT >= 24) {
                android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                notif = nm != null && nm.areNotificationsEnabled();
            }
            o.put("battery", battery);
            o.put("exact", exact);
            o.put("notifications", notif);
            o.put("manufacturer", safe(Build.MANUFACTURER));
            o.put("sdk", Build.VERSION.SDK_INT);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Asks Android to stop restricting this app's battery use (needed for alerts when the app is closed). */
    @JavascriptInterface
    public void requestBatteryUnrestricted() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) { openAppSettings(); }
        }
    }

    @JavascriptInterface
    public void openNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) { openAppSettings(); }
    }

    @JavascriptInterface
    public void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    /** Runs the background alert check once, right now (off the UI thread). */
    @JavascriptInterface
    public void runBackgroundCheck() {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() { public void run() { Poller.run(app, true); } }).start();
    }

    @JavascriptInterface
    public String getSettings() {
        try {
            SharedPreferences sp = p();
            JSONObject o = new JSONObject();
            o.put("tgToken",        safe(sp.getString("tg_token",   "")));
            o.put("tgChatId",       safe(sp.getString("tg_chatid",  "")));
            o.put("threshold",      sp.getInt("threshold", 30));
            o.put("cooldownMins",   sp.getInt("cooldown_minutes", 60));
            o.put("thresholdMap",   safe(sp.getString("threshold_map", "{}")));
            o.put("tbHost",         safe(sp.getString("tb_host",  "https://e-live.in")));
            o.put("tbEmail",        safe(sp.getString("tb_email", "")));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }


    // ── Manual/Bypass control + schedule ───────────────────────

    /** Saved login so the app can auto-fill the form (nothing is hard-coded in the HTML). */
    @JavascriptInterface
    public String getSavedLogin() {
        try {
            SharedPreferences sp = p();
            JSONObject o = new JSONObject();
            o.put("email", safe(sp.getString("tb_email", "")));
            o.put("password", safe(sp.getString("tb_password", "")));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** mode: rpc2 | rpc1 | attr   style: bool | int */
    @JavascriptInterface
    public void saveControlConfig(String deviceId, String bypassName, String mode, String style) {
        p().edit().putString("ctl_device_id", deviceId == null ? "" : deviceId.trim())
                  .putString("ctl_bypass_name", bypassName == null ? "" : bypassName.trim())
                  .putString("ctl_mode", mode == null ? "rpc2" : mode.trim())
                  .putString("ctl_value_style", style == null ? "bool" : style.trim()).apply();
    }

    @JavascriptInterface
    public void saveSchedules(String json) {
        ScheduleManager.saveSchedules(ctx, json);
        toast("Schedule saved \u2713");
    }

    @JavascriptInterface
    public String getSchedules() { return ScheduleManager.getSchedulesJson(ctx); }

    @JavascriptInterface
    public String getScheduleLog() { return ScheduleManager.getLogJson(ctx); }

    @JavascriptInterface
    public boolean canExactAlarms() { return ScheduleManager.canExact(ctx); }

    @JavascriptInterface
    public void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public boolean isServiceRunning() { return MonitorService.isRunning; }

    private String safe(String s) { return s == null ? "" : s; }

    private void toast(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
