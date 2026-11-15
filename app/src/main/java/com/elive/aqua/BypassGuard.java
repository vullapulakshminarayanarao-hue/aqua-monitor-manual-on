package com.elive.aqua;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether a scheduled Manual/Bypass command may be sent right now.
 *
 *  - No power at the site (device offline, no fresh data, or supply voltage ~0):
 *    nothing is sent, and nothing is retried or replayed when power comes back.
 *  - ON is only sent while the main tank is BELOW the limit (default 50 %).
 *  - OFF is always allowed while the device has power.
 *
 * The tank / voltage keys are found with the same name rules the dashboard uses,
 * so the level checked here is the one shown as "Main Tank" in the app.
 *
 * Optional overrides in prefs "aqua_prefs" (ints):
 *   bypass_tank_max_pct (default 50), power_stale_minutes (default 10), power_min_volts (default 1)
 */
final class BypassGuard {

    static final String TAG = "AquaGuard";

    static final class Result {
        final boolean allowed;
        /** true = a problem stopped us (no power / cannot check); false = normal skip (tank high). */
        final boolean warn;
        final String reason;
        Result(boolean allowed, boolean warn, String reason) {
            this.allowed = allowed; this.warn = warn; this.reason = reason;
        }
    }

    private BypassGuard() {}

    static Result evaluate(Context ctx, boolean wantOn) {
        SharedPreferences p = ctx.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
        String host  = str(p.getString("tb_host", "https://e-live.in"));
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        String email = str(p.getString("tb_email", ""));
        String pass  = str(p.getString("tb_password", ""));
        String devId = str(p.getString("ctl_device_id", ""));
        int tankMax  = p.getInt("bypass_tank_max_pct", 50);
        long staleMs = Math.max(1, p.getInt("power_stale_minutes", 10)) * 60000L;
        int minVolts = p.getInt("power_min_volts", 1);

        if (email.isEmpty() || pass.isEmpty() || devId.isEmpty()) {
            return new Result(false, true, "Cannot check tank/power - not logged in or no device selected");
        }

        try {
            String token = ControlClient.login(host, email, pass);
            if (token == null) return new Result(false, true, "Cannot check tank/power - login to e-live.in failed");

            String tele = ControlClient.get(host + "/api/plugins/telemetry/DEVICE/" + devId + "/values/timeseries", token);
            if (tele == null) return new Result(false, true, "Cannot check tank/power - no reply from e-live.in");
            JSONObject t = new JSONObject(tele);

            // ── 1. Is there power? ─────────────────────────────
            if (Boolean.FALSE.equals(readActive(host, devId, token))) {
                return noPower("device is offline");
            }
            List<String> keys = new ArrayList<String>();
            long newest = 0;
            Iterator<String> it = t.keys();
            while (it.hasNext()) {
                String k = it.next();
                keys.add(k);
                JSONArray a = t.optJSONArray(k);
                if (a != null && a.length() > 0) newest = Math.max(newest, a.getJSONObject(0).optLong("ts", 0));
            }
            if (keys.isEmpty() || newest == 0) return noPower("device has no data");
            long age = System.currentTimeMillis() - newest;
            if (age > staleMs) return noPower("no data from the device for " + (age / 60000L) + " min");

            String vk = fk(keys, "volt|vcc");
            if (vk != null) {
                double vv = num(t, vk);
                if (!Double.isNaN(vv) && vv < minVolts) {
                    return noPower("supply voltage is " + fmt(vv) + " V");
                }
            }

            // ── 2. OFF only needs power ────────────────────────
            if (!wantOn) return new Result(true, false, "Power OK");

            // ── 3. ON needs the main tank below the limit ──────
            String sk = fk(keys, "sump", "underground|under_level");
            List<String> mainKeys = new ArrayList<String>(keys);
            if (sk != null) mainKeys.remove(sk);
            String lk = fk(mainKeys,
                "^(WaterLevel|MainLevel|TankLevel|OverheadLevel|OHTLevel)$",
                "main.*level|overhead.*level|tank.*level|oht",
                "^level$|^water$|^wl$",
                "water.*level|level.*water",
                "level|water|wl|litre|liter|height",
                "lvl|lev");
            String use = lk != null ? lk : sk;   // same fallback the dashboard uses
            if (use == null) return new Result(false, true, "Main tank level not found in the device data");

            double raw = num(t, use);
            if (Double.isNaN(raw)) return new Result(false, true, "Main tank level (" + use + ") is not readable");
            double pct = Math.min(100, Math.max(0, raw));
            String shown = "Main tank (" + use + ") at " + fmt(pct) + "%";
            if (pct >= tankMax) {
                return new Result(false, false, shown + " - not below " + tankMax + "%");
            }
            return new Result(true, false, shown + " - below " + tankMax + "%");

        } catch (Exception e) {
            Log.e(TAG, "evaluate: " + e.getMessage());
            return new Result(false, true, "Cannot check tank/power - " + e.getMessage());
        }
    }

    private static Result noPower(String why) {
        return new Result(false, true, "No power (" + why + ") - not applied");
    }

    /** ThingsBoard "active" server attribute: false while the device is disconnected. */
    private static Boolean readActive(String host, String devId, String token) {
        try {
            String s = ControlClient.get(host + "/api/plugins/telemetry/DEVICE/" + devId
                + "/values/attributes/SERVER_SCOPE?keys=active", token);
            if (s == null) return null;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if ("active".equals(o.optString("key"))) {
                    return Boolean.valueOf("true".equalsIgnoreCase(String.valueOf(o.opt("value"))));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** First key matching the first pattern that matches any key (case-insensitive). */
    private static String fk(List<String> keys, String... pats) {
        for (String pat : pats) {
            Pattern re = Pattern.compile(pat, Pattern.CASE_INSENSITIVE);
            for (String k : keys) if (re.matcher(k).find()) return k;
        }
        return null;
    }

    private static double num(JSONObject t, String key) {
        try {
            JSONArray a = t.getJSONArray(key);
            return Double.parseDouble(String.valueOf(a.getJSONObject(0).get("value")).trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.US, "%.1f", d);
    }

    private static String str(String s) { return s == null ? "" : s.trim(); }
}
