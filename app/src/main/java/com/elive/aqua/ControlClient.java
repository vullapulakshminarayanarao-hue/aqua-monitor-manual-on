package com.elive.aqua;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Sends the Manual/Bypass command to the device through ThingsBoard.
 * Used by the scheduler (works with the app closed).
 *
 * Settings come from SharedPreferences "aqua_prefs":
 *   ctl_device_id    – ThingsBoard device UUID to control
 *   ctl_bypass_name  – RPC method name (or attribute key) for Manual/Bypass
 *   ctl_mode         – "rpc2" (two-way RPC), "rpc1" (one-way RPC) or "attr" (shared attribute)
 *   ctl_value_style  – "bool" (true/false) or "int" (1/0)
 */
final class ControlClient {

    static final String TAG = "AquaControl";

    private ControlClient() {}

    /** @return null when the command was accepted, otherwise a short error message. */
    static String sendBypass(Context ctx, boolean on) {
        SharedPreferences p = ctx.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
        String host  = str(p.getString("tb_host", "https://e-live.in"));
        String email = str(p.getString("tb_email", ""));
        String pass  = str(p.getString("tb_password", ""));
        String devId = str(p.getString("ctl_device_id", ""));
        String name  = str(p.getString("ctl_bypass_name", "setBypass"));
        String mode  = str(p.getString("ctl_mode", "rpc2"));
        String style = str(p.getString("ctl_value_style", "bool"));

        if (email.isEmpty() || pass.isEmpty()) return "Not logged in - open the app and connect once";
        if (devId.isEmpty())                   return "No device selected - open the app first";
        if (name.isEmpty())                    return "Bypass command name is empty";

        try {
            String token = login(host, email, pass);
            if (token == null) return "Login to e-live.in failed";

            Object val = "int".equals(style) ? (Object) Integer.valueOf(on ? 1 : 0)
                                             : (Object) Boolean.valueOf(on);
            JSONObject body = new JSONObject();
            String url;
            if ("attr".equals(mode)) {
                url = host + "/api/plugins/telemetry/DEVICE/" + devId + "/attributes/SHARED_SCOPE";
                body.put(name, val);
            } else {
                url = host + "/api/plugins/rpc/" + ("rpc1".equals(mode) ? "oneway/" : "twoway/") + devId;
                body.put("method", name);
                body.put("params", val);
                if (!"rpc1".equals(mode)) body.put("timeout", 15000);
            }

            int code = post(url, token, body.toString());
            if (code >= 200 && code < 300) return null;
            if (code == 408) return "Device did not respond (offline?)";
            if (code == 409) return "Device is offline";
            if (code == 401 || code == 403) return "Not allowed (HTTP " + code + ")";
            return "Server error HTTP " + code;
        } catch (Exception e) {
            Log.e(TAG, "sendBypass: " + e.getMessage());
            return "Network error: " + e.getMessage();
        }
    }

    /** Sends a plain Telegram text using the saved token / chat id (if any). */
    static void telegram(Context ctx, String text) {
        try {
            SharedPreferences p = ctx.getSharedPreferences("aqua_prefs", Context.MODE_PRIVATE);
            String tok = str(p.getString("tg_token", ""));
            String chat = str(p.getString("tg_chatid", ""));
            if (tok.isEmpty() || chat.isEmpty()) return;
            String url = "https://api.telegram.org/bot" + tok
                + "/sendMessage?chat_id=" + URLEncoder.encode(chat, "UTF-8")
                + "&text=" + URLEncoder.encode(text, "UTF-8");
            HttpURLConnection c = open(url);
            c.getResponseCode();
            c.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "telegram: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────

    private static String login(String host, String email, String pass) throws Exception {
        JSONObject b = new JSONObject();
        b.put("username", email);
        b.put("password", pass);
        HttpURLConnection c = open(host + "/api/auth/login");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(b.toString().getBytes("UTF-8"));
        try {
            if (c.getResponseCode() == 200) {
                String t = new JSONObject(read(c.getInputStream())).optString("token", "");
                return t.isEmpty() ? null : t;
            }
            return null;
        } finally {
            c.disconnect();
        }
    }

    private static int post(String url, String token, String json) throws Exception {
        HttpURLConnection c = open(url);
        c.setReadTimeout(20000); // two-way RPC waits for the device
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Authorization", "Bearer " + token);
        c.getOutputStream().write(json.getBytes("UTF-8"));
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        return c;
    }

    private static String read(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static String str(String s) { return s == null ? "" : s.trim(); }
}
