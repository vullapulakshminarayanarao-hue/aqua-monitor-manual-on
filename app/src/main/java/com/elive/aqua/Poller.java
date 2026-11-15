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
                    CH_SVC, "Trust Water Controller Service", NotificationManager.IMPORTANCE_MIN));
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
        SharedPreferences p = ctx.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
        String result;
        try { result = runInner(ctx, p); }
        catch (Exception e) { Log.e(TAG, "run", e); result = "Error: " + e.getMessage(); }
        // The app shows this under Settings > Notifications so a silent failure is visible.
        p.edit().putLong("bg_last_ts", System.currentTimeMillis()).putString("bg_last_msg", result).apply();
        Log.d(TAG, "Poll complete: " + result);
    }

    private static String runInner(Context ctx, SharedPreferences p) {
        String tgToken  = p.getString("tg_token",  "").trim();
        String tgChatId = p.getString("tg_chatid", "").trim();
        boolean tg = !tgToken.isEmpty() && !tgChatId.isEmpty();

        String base = p.getString("tb_host", BASE).trim();
        if (base.isEmpty()) base = BASE;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String email    = p.getString("tb_email",    "").trim();
        String password = p.getString("tb_password", "").trim();
        if (email.isEmpty() || password.isEmpty()) {
            return "Not signed in - open the app and sign in once";
        }

        int fallback = p.getInt("threshold", 30);
        int sumpThr  = threshold(p, "SumpLevel", fallback);
        int tankThr  = threshold(p, "TankLevel", fallback);
        int coolMins = p.getInt("cooldown_minutes", 60);

        String token = login(base, email, password);
        if (token == null) return "Sign-in to e-live.in failed - check the email and password";

        String devId = p.getString("ctl_device_id", "").trim();
        if (devId.isEmpty()) devId = getFirstDevice(base, token);
        if (devId == null) return "No device found for this account";

        String tele = getTelemetry(base, token, devId);
        if (tele == null) return "No data received from the device";

        createChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        StringBuilder sb = new StringBuilder();
        String[] parts = {
            checkLevel(ctx, p, tele, nm, "sump",  "Sump",  sumpThr, coolMins, tgToken, tgChatId, tg),
            checkLevel(ctx, p, tele, nm, "tank",  "Tank",  tankThr, coolMins, tgToken, tgChatId, tg),
            checkLevel(ctx, p, tele, nm, "level", "Level", tankThr, coolMins, tgToken, tgChatId, tg),
            checkLevel(ctx, p, tele, nm, "water", "Water", tankThr, coolMins, tgToken, tgChatId, tg)
        };
        for (String s : parts) if (s != null) { if (sb.length() > 0) sb.append("; "); sb.append(s); }
        if (sb.length() == 0) return "No tank or sump level found in the device data";
        return (tg ? "" : "Telegram not saved - phone alerts only. ") + sb;
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

    /** Returns a short result for the status line, or null if this key is not in the data. */
    private static String checkLevel(Context ctx, SharedPreferences p, String json,
            NotificationManager nm, String key, String label,
            int thresh, int coolMins, String tgTok, String tgChat, boolean tg) {
        try {
            String lo = json.toLowerCase();
            int ki = lo.indexOf("\"" + key);
            if (ki < 0) return null;

            int vi = json.indexOf("\"value\"", ki);
            if (vi < 0 || vi - ki > 500) return null;
            int vs = json.indexOf("\"", vi + 8) + 1;
            int ve = json.indexOf("\"", vs);
            if (vs <= 0 || ve <= vs) return null;

            float pct = Float.parseFloat(json.substring(vs, ve));
            String coolKey  = "alerted_" + key;
            long lastAlert  = p.getLong(coolKey, 0);
            long coolMs     = (long) coolMins * 60_000L;
            boolean canAlert = (System.currentTimeMillis() - lastAlert) > coolMs;

            if (pct >= thresh) {
                // Level restored - the next drop alerts straight away
                if (lastAlert > 0) p.edit().remove(coolKey).apply();
                return label + " " + (int) pct + "% ok";
            }
            if (!canAlert) return label + " " + (int) pct + "% low (alert already sent)";

            String msg = "\u26a0\ufe0f " + label + " water LOW: " + (int) pct + "% (minimum " + thresh + "%)\nPlease start the motor!";
            sendPhoneNotif(ctx, nm, label + " Level Low!", msg);
            boolean sent = tg && sendTelegram(tgTok, tgChat,
                "\u26a0\ufe0f WATER LEVEL ALERT\n\n"
                + "Sensor: " + label + "\n"
                + "Level: " + (int) pct + "%\n"
                + "Minimum: " + thresh + "%\n\n"
                + "Please start the motor!");
            // Count it as done only if it was delivered (or Telegram is not set up);
            // otherwise try again in about 2 minutes instead of waiting a full cooldown.
            long stamp = (sent || !tg) ? System.currentTimeMillis()
                                       : System.currentTimeMillis() - coolMs + 2 * 60_000L;
            p.edit().putLong(coolKey, stamp).apply();
            return label + " " + (int) pct + "% LOW - " + (sent ? "Telegram sent" : tg ? "Telegram FAILED (will retry)" : "phone alert only");
        } catch (Exception e) {
            Log.e(TAG, "checkLevel " + key, e);
            return null;
        }
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
            JSONObject o = new JSONObject();
            o.put("username", email); o.put("password", pw);
            String resp = post(base + "/api/auth/login", null, o.toString());
            if (resp == null) return null;
            return new JSONObject(resp).optString("token", null);
        } catch (Exception e) { Log.e(TAG, "login", e); return null; }
    }

    private static String getFirstDevice(String base, String tok) {
        try {
            java.util.List<String> eps = new java.util.ArrayList<String>();
            // Same order the dashboard uses: customer devices first (customer logins), then tenant.
            String u = get(base + "/api/auth/user", tok);
            if (u != null) {
                JSONObject cj = new JSONObject(u).optJSONObject("customerId");
                String cid = cj == null ? "" : cj.optString("id", "");
                if (!cid.isEmpty() && !"13814000-1dd2-11b2-8080-808080808080".equals(cid)) {
                    eps.add(base + "/api/customer/" + cid + "/devices?pageSize=100&page=0");
                }
            }
            eps.add(base + "/api/tenant/devices?pageSize=100&page=0");
            eps.add(base + "/api/customer/devices?pageSize=100&page=0");
            for (String ep : eps) {
                String r = get(ep, tok);
                if (r == null) continue;
                org.json.JSONArray d = new JSONObject(r).optJSONArray("data");
                if (d == null || d.length() == 0) continue;
                JSONObject id = d.getJSONObject(0).optJSONObject("id");
                if (id != null && !id.optString("id", "").isEmpty()) return id.getString("id");
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

    private static boolean sendTelegram(String tok, String chat, String msg) {
        try {
            String r = get("https://api.telegram.org/bot" + tok
                + "/sendMessage?chat_id=" + URLEncoder.encode(chat, "UTF-8")
                + "&text=" + URLEncoder.encode(msg, "UTF-8"), null);
            return r != null && r.contains("\"ok\":true");
        } catch (Exception e) { Log.e(TAG, "telegram", e); return false; }
    }
}
