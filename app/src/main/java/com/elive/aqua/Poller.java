package com.elive.aqua;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class Poller {

    private static final String TAG      = "AquaPoller";
    private static final String BASE     = "https://e-live.in";
    public  static final String CH_ALERT = "aqua_alerts";
    public  static final String CH_SVC   = "aqua_service";

    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CH_SVC) == null)
                nm.createNotificationChannel(new NotificationChannel(
                    CH_SVC, "Aqua Monitor Service", NotificationManager.IMPORTANCE_MIN));
            if (nm.getNotificationChannel(CH_ALERT) == null) {
                NotificationChannel ac = new NotificationChannel(
                    CH_ALERT, "Water Level Alerts", NotificationManager.IMPORTANCE_HIGH);
                ac.enableVibration(true);
                ac.setShowBadge(true);
                nm.createNotificationChannel(ac);
            }
        }
    }

    /** Main entry point — call from AlarmReceiver, PollJobService and the service. */
    public static void run(Context ctx) {
        // Same store as NativeBridge / ControlClient ("aqua_prefs")
        SharedPreferences p = ctx.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
        String tgToken  = p.getString("tg_token",  "");
        String tgChatId = p.getString("tg_chatid", "");
        if (tgToken.isEmpty() || tgChatId.isEmpty()) {
            Log.d(TAG, "No Telegram config — skipping");
            return;
        }

        String base     = p.getString("tb_host", BASE).trim();
        if (base.isEmpty()) base = BASE;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String email    = p.getString("tb_email",    "").trim();
        String password = p.getString("tb_password", "").trim();
        if (email.isEmpty() || password.isEmpty()) {
            Log.d(TAG, "Not logged in — open the app and connect once");
            return;
        }

        int fallback    = p.getInt("threshold", 30);
        int sumpThr     = threshold(p, "SumpLevel", fallback);
        int tankThr     = threshold(p, "TankLevel", fallback);
        int coolMins    = p.getInt("cooldown_minutes", 60);

        String token = login(base, email, password);
        if (token == null) { Log.e(TAG, "Login failed"); return; }

        String devId = getFirstDevice(base, token);
        if (devId == null) { Log.e(TAG, "No device found"); return; }

        String tele = getTelemetry(base, token, devId);
        if (tele == null) { Log.e(TAG, "No telemetry"); return; }

        Log.d(TAG, "Telemetry: " + tele.substring(0, Math.min(200, tele.length())));

        createChannels(ctx);
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        checkLevel(ctx, p, tele, nm, "sump",  "Sump",  sumpThr, coolMins, tgToken, tgChatId);
        checkLevel(ctx, p, tele, nm, "tank",  "Tank",  tankThr, coolMins, tgToken, tgChatId);
        checkLevel(ctx, p, tele, nm, "level", "Level", tankThr, coolMins, tgToken, tgChatId);
        checkLevel(ctx, p, tele, nm, "water", "Water", tankThr, coolMins, tgToken, tgChatId);

        Log.d(TAG, "Poll complete: " + new java.util.Date());
    }

    /** Per-sensor threshold from the app's threshold map: sensor key, then "default", then fallback. */
    private static int threshold(SharedPreferences p, String key, int fallback) {
        try {
            JSONObject m = new JSONObject(p.getString("threshold_map", "{}"));
            if (m.has(key))       return m.getInt(key);
            if (m.has("default")) return m.getInt("default");
        } catch (Exception ignored) {}
        return fallback;
    }

    private static void checkLevel(Context ctx, SharedPreferences p, String json,
            NotificationManager nm, String key, String label,
            int thresh, int coolMins, String tgTok, String tgChat) {
        try {
            // Find the sensor key (case-insensitive match)
            String lo = json.toLowerCase();
            int ki = lo.indexOf("\"" + key);
            if (ki < 0) { Log.d(TAG, "Key not found: " + key); return; }

            // Find value field near that key
            int vi = json.indexOf("\"value\"", ki);
            if (vi < 0 || vi - ki > 500) return;
            int vs = json.indexOf("\"", vi + 8) + 1;
            int ve = json.indexOf("\"", vs);
            if (vs <= 0 || ve <= vs) return;

            float pct = Float.parseFloat(json.substring(vs, ve));
            Log.d(TAG, label + " = " + pct + "% (thresh=" + thresh + "%)");

            String coolKey = "alerted_" + key;
            long   lastAlert = p.getLong(coolKey, 0);
            long   coolMs    = (long) coolMins * 60_000L;
            boolean canAlert = (System.currentTimeMillis() - lastAlert) > coolMs;

            if (pct < thresh && canAlert) {
                String msg = "\u26a0\ufe0f " + label + " water LOW: "
                    + (int)pct + "% (minimum " + thresh + "%)\nPlease start the motor!";
                sendPhoneNotif(ctx, nm, label + " Level Low!", msg);
                sendTelegram(tgTok, tgChat,
                    "\u26a0\ufe0f WATER LEVEL ALERT\n\n"
                    + "Sensor: " + label + "\n"
                    + "Level: " + (int)pct + "%\n"
                    + "Minimum: " + thresh + "%\n\n"
                    + "Please start the motor!");
                p.edit().putLong(coolKey, System.currentTimeMillis()).apply();
            } else if (pct >= thresh && lastAlert > 0) {
                // Level restored — clear alert so next drop triggers fresh alert
                p.edit().remove(coolKey).apply();
            }
        } catch (Exception e) { Log.e(TAG, "checkLevel " + key, e); }
    }

    private static void sendPhoneNotif(Context ctx, NotificationManager nm,
                                       String title, String text) {
        Intent tap = new Intent(ctx, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(ctx, CH_ALERT)
                .setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi).setAutoCancel(true)
                .setVibrate(new long[]{0,500,200,500}).build();
        } else {
            n = new Notification.Builder(ctx)
                .setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setPriority(Notification.PRIORITY_MAX)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi).setAutoCancel(true)
                .setVibrate(new long[]{0,500,200,500}).build();
        }
        nm.notify(2001, n);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────
    private static String login(String base, String email, String pw) {
        try {
            String body = "{\"username\":\"" + email + "\",\"password\":\"" + pw + "\"}";
            String resp = post(base + "/api/auth/login", null, body);
            if (resp == null) return null;
            int t = resp.indexOf("\"token\""); if (t < 0) return null;
            int s = resp.indexOf("\"", t + 8) + 1;
            return resp.substring(s, resp.indexOf("\"", s));
        } catch (Exception e) { Log.e(TAG, "login", e); return null; }
    }

    private static String getFirstDevice(String base, String tok) {
        try {
            for (String ep : new String[]{
                    base + "/api/tenant/devices?pageSize=100&page=0",
                    base + "/api/customer/devices?pageSize=100&page=0"}) {
                String r = get(ep, tok);
                if (r == null) continue;
                int ii = r.indexOf("\"id\""); if (ii < 0) continue;
                int oi = r.indexOf("{", ii + 4); if (oi < 0) continue;
                int si = r.indexOf("\"id\"", oi); if (si < 0) continue;
                int vs = r.indexOf("\"", si + 5) + 1;
                return r.substring(vs, r.indexOf("\"", vs));
            }
        } catch (Exception e) { Log.e(TAG, "getFirstDevice", e); }
        return null;
    }

    private static String getTelemetry(String base, String tok, String devId) {
        return get(base + "/api/plugins/telemetry/DEVICE/" + devId + "/values/timeseries", tok);
    }

    static String get(String url, String tok) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(15000);
            if (tok != null) c.setRequestProperty("X-Authorization", "Bearer " + tok);
            if (c.getResponseCode() >= 400) return null;
            BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private static String post(String url, String tok, String body) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000); c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json");
            if (tok != null) c.setRequestProperty("X-Authorization", "Bearer " + tok);
            c.setDoOutput(true);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            if (c.getResponseCode() >= 400) return null;
            BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private static void sendTelegram(String tok, String chat, String msg) {
        try {
            get("https://api.telegram.org/bot" + tok
                + "/sendMessage?chat_id=" + URLEncoder.encode(chat, "UTF-8")
                + "&text=" + URLEncoder.encode(msg, "UTF-8"), null);
        } catch (Exception e) { Log.e(TAG, "telegram", e); }
    }
}
