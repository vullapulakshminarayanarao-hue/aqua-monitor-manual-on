package com.elive.aqua;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
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

    private static final Object LOCK = new Object();

    /** Main entry point - call from AlarmReceiver, PollJobService and the service. */
    public static void run(Context ctx) { run(ctx, false); }

    /** force = true skips the "just ran a moment ago" guard (used by "Check now"). */
    public static void run(Context ctx, boolean force) {
        synchronized (LOCK) {
            SharedPreferences p = ctx.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
            // Three triggers (alarm, job, service loop) may fire close together - run once.
            if (!force && System.currentTimeMillis() - p.getLong("bg_last_ts", 0) < 25_000L) return;
            String result;
            try { result = runInner(ctx, p); }
            catch (Exception e) { Log.e(TAG, "run", e); result = "Error: " + e.getMessage(); }
            // Shown under Settings > Notifications so a silent failure is visible.
            p.edit().putLong("bg_last_ts", System.currentTimeMillis()).putString("bg_last_msg", result).apply();
            Log.d(TAG, "Poll complete: " + result);
        }
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
        if (!online(ctx)) return "No internet on the phone right now";

        int fallback = p.getInt("threshold", 30);
        int sumpThr  = threshold(p, "SumpLevel", fallback);
        int tankThr  = threshold(p, "TankLevel", fallback);
        int coolMins = p.getInt("cooldown_minutes", 60);

        String token = authToken(p, base, email, password, false);
        if (token == null) return "Sign-in to e-live.in failed - check the email and password, or the internet";

        String devId = p.getString("ctl_device_id", "").trim();
        if (devId.isEmpty()) devId = p.getString("bg_device_id", "").trim();
        if (devId.isEmpty()) {
            String first = getFirstDevice(base, token);
            if (first != null) { devId = first; p.edit().putString("bg_device_id", first).apply(); }
        }
        if (devId.isEmpty()) return "No device found for this account";

        String tele = getTelemetry(base, token, devId);
        if (tele == null) {                       // the saved sign-in may have expired: once more, fresh
            token = authToken(p, base, email, password, true);
            if (token == null) return "Sign-in to e-live.in failed - check the email and password, or the internet";
            tele = getTelemetry(base, token, devId);
        }
        if (tele == null) return "No data received (e-live.in did not answer)";

        createChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        // ---- power cut / back online ----
        long now      = System.currentTimeMillis();
        long newest   = newestTs(tele);
        long staleMs  = Math.max(1, p.getInt("power_stale_minutes", 10)) * 60_000L;
        long offSince = p.getLong("bg_offline_since", 0);
        if (newest > 0 && now - newest > staleMs) {
            long mins = (now - newest) / 60000L;
            if (offSince == 0) {
                String text = "No data from the device for " + mins + " min.\nLast data: " + fmtTime(newest)
                    + "\nPossible power cut, or no internet at the site.";
                boolean sent = alertBoth(ctx, nm, 2002, "Device offline", text,
                    "\u26a0\ufe0f POWER CUT / DEVICE OFFLINE", tgToken, tgChatId, tg);
                if (sent || !tg) p.edit().putLong("bg_offline_since", newest).apply();
            }
            updateLive(ctx, p, "Device offline - no data for " + mins + " min");
            String sumOff = maybeSendSummary(ctx, p, base, token, devId, tele, tgToken, tgChatId, tg, false);
            // old readings must not trigger level alerts
            return "Device offline - no data for " + mins + " min (last data " + fmtTime(newest) + ")" + sumOff;
        }
        String restored = "";
        if (offSince > 0 && newest > 0) {
            long down = Math.max(0, (newest - offSince) / 60000L);
            String text = "The device is sending data again.\nIt was offline for about " + down + " min (since " + fmtTime(offSince) + ").";
            boolean sent = alertBoth(ctx, nm, 2003, "Power restored", text,
                "\u2705 POWER RESTORED", tgToken, tgChatId, tg);
            if (sent || !tg) p.edit().remove("bg_offline_since").apply();
            restored = "Back online. ";
        }

        updateLive(ctx, p, liveLine(tele));

        // ---- water levels ----
        StringBuilder sb = new StringBuilder();
        String[] parts = {
            checkLevel(ctx, p, tele, nm, "sump",  "Sump",  sumpThr, coolMins, tgToken, tgChatId, tg),
            checkLevel(ctx, p, tele, nm, "tank",  "Tank",  tankThr, coolMins, tgToken, tgChatId, tg),
            checkLevel(ctx, p, tele, nm, "level", "Level", tankThr, coolMins, tgToken, tgChatId, tg),
            checkLevel(ctx, p, tele, nm, "water", "Water", tankThr, coolMins, tgToken, tgChatId, tg)
        };
        for (String s : parts) if (s != null) { if (sb.length() > 0) sb.append("; "); sb.append(s); }
        String sumTxt = maybeSendSummary(ctx, p, base, token, devId, tele, tgToken, tgChatId, tg, false);
        if (sb.length() == 0) return restored + "No tank or sump level found in the device data" + sumTxt;
        return restored + (tg ? "" : "Telegram not saved - phone alerts only. ") + sb + sumTxt;
    }

    // ---- level / motor keys: the same name rules the app uses ----

    private static String findKey(java.util.List<String> keys, String... pats) {
        for (String pat : pats) {
            java.util.regex.Pattern re = java.util.regex.Pattern.compile(pat, java.util.regex.Pattern.CASE_INSENSITIVE);
            for (String k : keys) if (re.matcher(k).find()) return k;
        }
        return null;
    }

    /** {main tank key, sump key, motor key}; any of them may be null. */
    private static String[] roles(JSONObject latest) {
        java.util.List<String> keys = new java.util.ArrayList<String>();
        java.util.Iterator<String> it = latest.keys();
        while (it.hasNext()) keys.add(it.next());
        String sump = findKey(keys, "sump", "underground|under_level");
        java.util.List<String> main = new java.util.ArrayList<String>(keys);
        if (sump != null) main.remove(sump);
        String tank = findKey(main,
            "^(WaterLevel|MainLevel|TankLevel|OverheadLevel|OHTLevel)$",
            "main.*level|overhead.*level|tank.*level|oht",
            "^level$|^water$|^wl$", "water.*level|level.*water",
            "level|water|wl|litre|liter|height", "lvl|lev");
        String motor = findKey(keys, "motor|pump|relay", "switch|run|output", "status|state");
        return new String[] { tank, sump, motor };
    }

    private static Object lastValue(JSONObject latest, String key) {
        try { return latest.getJSONArray(key).getJSONObject(0).get("value"); } catch (Exception e) { return null; }
    }

    private static double numOf(Object v) {
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception e) { return Double.NaN; }
    }

    private static boolean isOnValue(Object v) {
        String s = String.valueOf(v).trim().toLowerCase(Locale.US);
        if (s.equals("true") || s.equals("on") || s.equals("running")) return true;
        double d = numOf(s);
        return !Double.isNaN(d) && d > 0.5;
    }

    private static int pct(double v) { return (int) Math.round(Math.max(0, Math.min(100, v))); }

    /** "Main 42% · Sump 80% · Motor ON" for the status notification. */
    private static String liveLine(String tele) {
        try {
            JSONObject o = new JSONObject(tele);
            String[] r = roles(o);
            StringBuilder sb = new StringBuilder();
            if (r[0] != null) { double v = numOf(lastValue(o, r[0])); if (!Double.isNaN(v)) sb.append("Main ").append(pct(v)).append("%"); }
            if (r[1] != null) { double v = numOf(lastValue(o, r[1])); if (!Double.isNaN(v)) { if (sb.length() > 0) sb.append(" \u00b7 "); sb.append("Sump ").append(pct(v)).append("%"); } }
            if (r[2] != null) { Object raw = lastValue(o, r[2]); if (raw != null) { if (sb.length() > 0) sb.append(" \u00b7 "); sb.append("Motor ").append(isOnValue(raw) ? "ON" : "OFF"); } }
            return sb.length() > 0 ? sb.toString() : "Monitoring water levels";
        } catch (Exception e) { return "Monitoring water levels"; }
    }

    private static void updateLive(Context ctx, SharedPreferences p, String text) {
        p.edit().putString("live_text", text).apply();
        String sub = "Updated " + new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());
        MonitorService.updateNotification(ctx, text, sub);
    }

    // ---- daily summary ----

    private static String fmtDur(long mins) {
        if (mins < 60) return mins + " min";
        long h = mins / 60, m = mins % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    /** Sorted-by-time points of one key: each row is {timestamp, value as text}. */
    private static java.util.List<Object[]> series(JSONObject hist, String key) {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        try {
            org.json.JSONArray a = hist.optJSONArray(key);
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject pt = a.getJSONObject(i);
                out.add(new Object[] { Long.valueOf(pt.optLong("ts", 0)), pt.get("value") });
            }
            java.util.Collections.sort(out, new java.util.Comparator<Object[]>() {
                @Override public int compare(Object[] x, Object[] y) { return ((Long) x[0]).compareTo((Long) y[0]); }
            });
        } catch (Exception ignored) {}
        return out;
    }

    /** Text of the last-24-hours summary, or null if the history could not be read. */
    private static String buildSummary(SharedPreferences p, String base, String token, String devId, String tele, long now) {
        try {
            JSONObject latest = new JSONObject(tele);
            String[] r = roles(latest);
            StringBuilder keys = new StringBuilder();
            for (String k : r) if (k != null) { if (keys.length() > 0) keys.append(","); keys.append(k); }
            if (keys.length() == 0) return null;

            String url = base + "/api/plugins/telemetry/DEVICE/" + devId + "/values/timeseries?keys="
                + URLEncoder.encode(keys.toString(), "UTF-8") + "&startTs=" + (now - 24L * 3600000L)
                + "&endTs=" + now + "&limit=5000&agg=NONE";
            String body = get(url, token);
            if (body == null) return null;
            JSONObject hist = new JSONObject(body);

            long staleMs = Math.max(1, p.getInt("power_stale_minutes", 10)) * 60_000L;
            StringBuilder sb = new StringBuilder();
            sb.append("\ud83d\udcca DAILY SUMMARY\n");
            String name = null;
            String dj = get(base + "/api/device/" + devId, token);
            if (dj != null) { try { name = new JSONObject(dj).optString("name", null); } catch (Exception ignored) {} }
            sb.append("Trust Water Controller").append(name != null ? " \u00b7 " + name : "").append("\n");
            sb.append("Last 24 hours, up to ").append(fmtTime(now)).append("\n\n");

            String[] labels = { "Main tank", "Sump" };
            for (int i = 0; i < 2; i++) {
                if (r[i] == null) continue;
                java.util.List<Object[]> s = series(hist, r[i]);
                double mn = 1e9, mx = -1e9; int cnt = 0;
                for (Object[] row : s) { double v = numOf(row[1]); if (Double.isNaN(v)) continue; v = Math.max(0, Math.min(100, v)); mn = Math.min(mn, v); mx = Math.max(mx, v); cnt++; }
                double cur = numOf(lastValue(latest, r[i]));
                sb.append(labels[i]).append(": ");
                if (!Double.isNaN(cur)) sb.append("now ").append(pct(cur)).append("%");
                if (cnt > 0) sb.append(" \u00b7 low ").append(pct(mn)).append("% \u00b7 high ").append(pct(mx)).append("%");
                sb.append("\n");
            }

            if (r[2] != null) {
                java.util.List<Object[]> s = series(hist, r[2]);
                long cap = 10 * 60_000L, runMs = 0, longest = 0, cur = 0; int starts = 0;
                for (int i = 0; i < s.size(); i++) {
                    long t = (Long) s.get(i)[0];
                    boolean on = isOnValue(s.get(i)[1]);
                    long next = i + 1 < s.size() ? (Long) s.get(i + 1)[0] : now;
                    long dur = Math.min(Math.max(0, next - t), cap);
                    if (on) {
                        runMs += dur;
                        boolean contiguous = i > 0 && isOnValue(s.get(i - 1)[1]) && (t - (Long) s.get(i - 1)[0]) <= cap;
                        if (!contiguous) { cur = 0; if (i > 0) starts++; }
                        cur += dur; longest = Math.max(longest, cur);
                    }
                }
                sb.append("Motor: ran ").append(fmtDur(runMs / 60000L)).append(" \u00b7 ").append(starts)
                  .append(starts == 1 ? " start" : " starts");
                if (longest > 0) sb.append(" \u00b7 longest ").append(fmtDur(longest / 60000L));
                sb.append("\n");
            }

            // gaps in the data = power cuts or no internet at the site
            String ref = r[0] != null ? r[0] : (r[1] != null ? r[1] : r[2]);
            java.util.List<Object[]> s = series(hist, ref);
            int gaps = 0; long gapMs = 0;
            for (int i = 1; i < s.size(); i++) {
                long d = (Long) s.get(i)[0] - (Long) s.get(i - 1)[0];
                if (d > staleMs) { gaps++; gapMs += d; }
            }
            if (!s.isEmpty() && now - (Long) s.get(s.size() - 1)[0] > staleMs) { gaps++; gapMs += now - (Long) s.get(s.size() - 1)[0]; }
            sb.append("Power / data: ");
            sb.append(gaps == 0 ? "no gaps" : gaps + (gaps == 1 ? " gap" : " gaps") + " (" + fmtDur(gapMs / 60000L) + " in total)");
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "buildSummary", e);
            return null;
        }
    }

    private static String ymd() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Sends the daily summary when it is due. force = send now (button). Returns text for the status line. */
    private static String maybeSendSummary(Context ctx, SharedPreferences p, String base, String token, String devId,
                                           String tele, String tgTok, String tgChat, boolean tg, boolean force) {
        try {
            if (!force && !p.getBoolean("summary_enabled", false)) return "";
            if (!tg) return force ? "Summary not sent: Telegram is not saved" : "";
            long now = System.currentTimeMillis();
            if (!force) {
                String[] hm = p.getString("summary_time", "20:00").split(":");
                int target = Integer.parseInt(hm[0].trim()) * 60 + Integer.parseInt(hm[1].trim());
                Calendar c = Calendar.getInstance();
                int nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
                if (nowMin < target) return "";
                if (ymd().equals(p.getString("summary_last_date", ""))) return "";
                if (now - p.getLong("summary_last_try", 0) < 10 * 60_000L) return "";
            }
            p.edit().putLong("summary_last_try", now).apply();
            String msg = buildSummary(p, base, token, devId, tele, now);
            if (msg == null) {
                p.edit().putString("summary_last_msg", "Could not read the last 24 hours of data").apply();
                return "; summary: could not read history";
            }
            boolean ok = sendTelegram(tgTok, tgChat, msg);
            String res = ok ? "Summary sent " + fmtTime(now) : "Summary not delivered (Telegram error), will retry";
            SharedPreferences.Editor ed = p.edit().putString("summary_last_msg", res);
            if (ok && !force) ed.putString("summary_last_date", ymd());
            ed.apply();
            return "; " + res.toLowerCase(Locale.US).replace("summary", "summary");
        } catch (Exception e) {
            Log.e(TAG, "summary", e);
            return "";
        }
    }

    /** "Send summary now" button: runs a full check path just for the summary. */
    public static void sendSummaryNow(Context ctx) {
        synchronized (LOCK) {
            SharedPreferences p = ctx.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
            String res;
            try {
                String tgTok = p.getString("tg_token", "").trim(), tgChat = p.getString("tg_chatid", "").trim();
                String base = p.getString("tb_host", BASE).trim();
                if (base.isEmpty()) base = BASE;
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                String email = p.getString("tb_email", "").trim(), pw = p.getString("tb_password", "").trim();
                if (email.isEmpty() || pw.isEmpty()) res = "Summary not sent: not signed in";
                else if (tgTok.isEmpty() || tgChat.isEmpty()) res = "Summary not sent: Telegram is not saved";
                else {
                    String token = authToken(p, base, email, pw, false);
                    String devId = p.getString("ctl_device_id", "").trim();
                    if (devId.isEmpty()) devId = p.getString("bg_device_id", "").trim();
                    if (token == null) res = "Summary not sent: sign-in failed";
                    else if (devId.isEmpty()) res = "Summary not sent: no device selected";
                    else {
                        String tele = getTelemetry(base, token, devId);
                        if (tele == null) res = "Summary not sent: no data from e-live.in";
                        else {
                            maybeSendSummary(ctx, p, base, token, devId, tele, tgTok, tgChat, true, true);
                            res = p.getString("summary_last_msg", "Done");
                        }
                    }
                }
            } catch (Exception e) { res = "Summary error: " + e.getMessage(); }
            p.edit().putString("summary_last_msg", res).apply();
        }
    }

    // ---- helpers for the checks above ----

    private static boolean online(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return true; }   // if unsure, just try
    }

    /** Reuses the saved sign-in token until shortly before it expires (one request fewer per check). */
    private static String authToken(SharedPreferences p, String base, String email, String pw, boolean fresh) {
        long now = System.currentTimeMillis();
        String key = base + "|" + email;
        String tok = p.getString("bg_token", "");
        if (!fresh && !tok.isEmpty() && key.equals(p.getString("bg_token_for", ""))
                && p.getLong("bg_token_exp", 0) > now + 120_000L) {
            return tok;
        }
        tok = login(base, email, pw);
        if (tok != null) {
            p.edit().putString("bg_token", tok).putLong("bg_token_exp", jwtExpiryMs(tok, now))
                    .putString("bg_token_for", key).apply();
        }
        return tok;
    }

    private static long jwtExpiryMs(String jwt, long now) {
        try {
            String[] parts = jwt.split("\\.");
            byte[] b = android.util.Base64.decode(parts[1],
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            long exp = new JSONObject(new String(b, "UTF-8")).optLong("exp", 0);
            if (exp > 0) return exp * 1000L;
        } catch (Exception ignored) {}
        return now + 30 * 60_000L;
    }

    /** Timestamp of the newest reading of any sensor, or 0. */
    private static long newestTs(String tele) {
        long newest = 0;
        try {
            JSONObject o = new JSONObject(tele);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                org.json.JSONArray a = o.optJSONArray(it.next());
                if (a != null && a.length() > 0) newest = Math.max(newest, a.getJSONObject(0).optLong("ts", 0));
            }
        } catch (Exception ignored) {}
        return newest;
    }

    static String fmtTime(long ms) {
        return new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date(ms));
    }

    /** Phone notification + Telegram. Returns true only if the Telegram message was delivered. */
    private static boolean alertBoth(Context ctx, NotificationManager nm, int id, String title, String text,
                                     String tgHeader, String tgTok, String tgChat, boolean tg) {
        sendPhoneNotif(ctx, nm, id, title, text);
        return tg && sendTelegram(tgTok, tgChat,
            tgHeader + "\n\n" + text + "\nTime: " + fmtTime(System.currentTimeMillis()));
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
            sendPhoneNotif(ctx, nm, 2001, label + " Level Low!", msg);
            boolean sent = tg && sendTelegram(tgTok, tgChat,
                "\u26a0\ufe0f WATER LEVEL ALERT\n\n"
                + "Sensor: " + label + "\n"
                + "Level: " + (int) pct + "%\n"
                + "Minimum: " + thresh + "%\n"
                + "Time: " + fmtTime(System.currentTimeMillis()) + "\n\n"
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

    private static void sendPhoneNotif(Context ctx, NotificationManager nm, int id,
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
        nm.notify(id, n);
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
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
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
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
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
