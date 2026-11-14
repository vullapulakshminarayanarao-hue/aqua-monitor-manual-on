package com.elive.aqua;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONObject;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    private boolean svcBound = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { svcBound = true; }
        @Override public void onServiceDisconnected(ComponentName n) { svcBound = false; }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Ask to disable battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    startActivity(new Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }
        }

        // Start service + schedule alarm
        Intent si = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(si);
        else
            startService(si);
        bindService(si, conn, BIND_AUTO_CREATE);
        AlarmScheduler.schedule(this);
        migrateLegacyPrefs();
        ScheduleManager.rescheduleAll(getApplicationContext());

        // Setup WebView
        web = findViewById(R.id.webView);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new NativeBridge(this), "NativeApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if (!url.startsWith("file://") && !url.startsWith("https://e-live.in")
                        && !url.startsWith("https://api.telegram.org")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.loadUrl("file:///android_asset/index.html");
    }

    /**
     * One-time copy of settings saved by older versions (prefs "aqua") into the
     * store used by the current bridge (prefs "aqua_prefs"), so upgrading keeps
     * the login, Telegram and threshold settings.
     */
    private void migrateLegacyPrefs() {
        try {
            SharedPreferences np = getSharedPreferences("aqua_prefs", MODE_PRIVATE);
            SharedPreferences op = getSharedPreferences("aqua", MODE_PRIVATE);
            if (np.getBoolean("legacy_migrated", false) || op.getAll().isEmpty()) return;
            SharedPreferences.Editor e = np.edit();
            if (np.getString("tg_token", "").isEmpty() && op.contains("tgToken"))
                e.putString("tg_token", op.getString("tgToken", ""));
            if (np.getString("tg_chatid", "").isEmpty() && op.contains("tgChatId"))
                e.putString("tg_chatid", op.getString("tgChatId", ""));
            if (np.getString("tb_email", "").isEmpty() && op.contains("email"))
                e.putString("tb_email", op.getString("email", ""));
            if (np.getString("tb_password", "").isEmpty() && op.contains("password"))
                e.putString("tb_password", op.getString("password", ""));
            if (!np.contains("cooldown_minutes") && op.contains("coolMins"))
                e.putInt("cooldown_minutes", op.getInt("coolMins", 60));
            if (!np.contains("threshold_map") && (op.contains("sumpThresh") || op.contains("tankThresh"))) {
                JSONObject m = new JSONObject();
                if (op.contains("sumpThresh")) m.put("SumpLevel", op.getInt("sumpThresh", 30));
                if (op.contains("tankThresh")) m.put("TankLevel", op.getInt("tankThresh", 30));
                e.putString("threshold_map", m.toString());
            }
            e.putBoolean("legacy_migrated", true).apply();
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        if (svcBound) { unbindService(conn); svcBound = false; }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
