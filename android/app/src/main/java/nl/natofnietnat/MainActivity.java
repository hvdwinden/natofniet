package nl.natofnietnat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;

/**
 * Toont de webapp (assets/index.html) in een WebView.
 * De bestanden worden geserveerd vanaf een https-adres, zodat de pagina een
 * "secure context" is en de browser-geolocatie werkt.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_LOCATION = 1;
    private static final int REQ_NOTIFY = 2;

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(webView);
        setContentView(root);
        // Android 15+ tekent tot onder de statusbalk; houd ruimte vrij voor systeembalken
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        // Brug zodat de webpagina instellingen en meldingen kan regelen
        webView.addJavascriptInterface(new Bridge(), "NatBridge");
        RainCheck.createChannel(this);
        RainCheck.schedule(this);
        MorningReport.schedule(this);

        // Terugknop: eerst terug binnen de pagina (bijv. instellingen sluiten), anders de app uit
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!HOST.equals(uri.getHost())) return null; // gewoon via internet
                String path = uri.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mimeType(path), "utf-8", in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                // Externe links (bijv. attributie) openen in de gewone browser
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, hasLocationPermission(), false);
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (hasLocationPermission()) {
            webView.loadUrl(START_URL);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Ook zonder toestemming laden; de app valt dan terug op Amsterdam
        if (requestCode == REQ_LOCATION) webView.loadUrl(START_URL);
        if (requestCode == REQ_NOTIFY) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                new Thread(() -> RainCheck.run(getApplicationContext())).start();   // meteen controleren
            } else if (settingsIfDenied) {
                // Android toont de vraag niet meer: stuur door naar de systeeminstellingen
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
            settingsIfDenied = false;
            refreshWebSettings();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static String mimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** Laat de pagina de instellingen opnieuw inlezen (na toestemming of terugkeer uit systeeminstellingen). */
    private void refreshWebSettings() {
        if (webView != null) webView.evaluateJavascript("window.onNativeChanged && window.onNativeChanged()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWebSettings();
    }

    private boolean settingsIfDenied = false;

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !RainCheck.canNotify(this)) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
        }
    }

    /** Methoden die vanuit JavaScript aangeroepen worden als NatBridge.xxx(). */
    private class Bridge {
        private SharedPreferences prefs() { return getSharedPreferences(RainCheck.PREFS, 0); }

        @JavascriptInterface
        public String getSettings() {
            SharedPreferences p = prefs();
            PowerManager pm = getSystemService(PowerManager.class);
            try {
                return new JSONObject()
                        .put("rainAlert", p.getBoolean("rainAlert", false))
                        .put("dryAlert", p.getBoolean("dryAlert", false))
                        .put("lead", p.getInt("lead", 30))
                        .put("daily", p.getBoolean("daily", false))
                        .put("uvAlert", p.getBoolean("uvAlert", false))
                        .put("morningTime", p.getInt("morningTime", 7 * 60))
                        .put("uvMin", p.getInt("uvMin", 6))
                        .put("pollenAlert", p.getBoolean("pollenAlert", false))
                        .put("pollenMin", p.getString("pollenMin", "moderate"))
                        .put("pollenGroups", p.getString("pollenGroups", "trees,grass,weeds"))
                        .put("name", p.getString("name", ""))
                        .put("notifyAllowed", RainCheck.canNotify(MainActivity.this))
                        .put("batteryOk", pm.isIgnoringBatteryOptimizations(getPackageName()))
                        .toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void setAlert(String key, boolean on) {
            if (!key.equals("rainAlert") && !key.equals("dryAlert")) return;
            prefs().edit().putBoolean(key, on).apply();
            RainCheck.schedule(MainActivity.this);
            if (on) {
                runOnUiThread(MainActivity.this::askNotificationPermission);
                new Thread(() -> RainCheck.run(getApplicationContext())).start();   // meteen een eerste controle
            }
        }

        /** Ochtendmeldingen: "daily" (weerbericht) of "uvAlert". */
        @JavascriptInterface
        public void setMorning(String key, boolean on) {
            if (!key.equals("daily") && !key.equals("uvAlert") && !key.equals("pollenAlert")) return;
            prefs().edit().putBoolean(key, on).apply();
            MorningReport.schedule(MainActivity.this);
            if (on) runOnUiThread(MainActivity.this::askNotificationPermission);
        }

        @JavascriptInterface
        public void setMorningTime(int minutes) {
            prefs().edit().putInt("morningTime", Math.max(0, Math.min(24 * 60 - 1, minutes))).apply();
            MorningReport.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public void setPollenPrefs(String minLevel, String groups) {
            prefs().edit().putString("pollenMin", minLevel).putString("pollenGroups", groups).apply();
        }

        /** Haalt de pollenverwachting op en geeft die terug via window.onPollen(json) (of null). */
        @JavascriptInterface
        public void fetchPollen(String city, double lat, double lon) {
            new Thread(() -> {
                org.json.JSONObject res = Pollen.get(city, lat, lon);
                String arg = res == null ? "null" : res.toString();
                runOnUiThread(() -> webView.evaluateJavascript("window.onPollen && window.onPollen(" + arg + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void setUvMin(int uv) {
            prefs().edit().putInt("uvMin", Math.max(1, Math.min(11, uv))).apply();
        }

        /** Eenheden voor de meldingen (de pagina zelf bewaart ze ook). */
        @JavascriptInterface
        public void setUnits(String temp, String wind) {
            prefs().edit().putString("tempUnit", temp).putString("windUnit", wind).apply();
        }

        @JavascriptInterface
        public void testMorning() {
            if (!RainCheck.canNotify(MainActivity.this)) { runOnUiThread(MainActivity.this::askNotificationPermission); return; }
            new Thread(() -> MorningReport.send(getApplicationContext(), true)).start();
        }

        @JavascriptInterface
        public void setLead(int minutes) {
            prefs().edit().putInt("lead", Math.max(15, Math.min(120, minutes))).apply();
        }

        @JavascriptInterface
        public void setLocation(double lat, double lon, String name) {
            prefs().edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon)
                    .putString("name", name == null || name.isEmpty() ? "je locatie" : name).apply();
        }

        @JavascriptInterface
        public void requestNotifications() {
            runOnUiThread(() -> { settingsIfDenied = true; askNotificationPermission(); });
        }

        @JavascriptInterface
        public void testNotification() {
            if (!RainCheck.canNotify(MainActivity.this)) { runOnUiThread(MainActivity.this::askNotificationPermission); return; }
            new Thread(() -> RainCheck.test(getApplicationContext())).start();
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }

        @JavascriptInterface
        public void requestBatteryExemption() {
            // Play staat de directe vraag (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) niet toe voor deze soort app;
            // daarom openen we de app-info, waar je onder Batterij "Onbeperkt" kiest.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    private void handleBack() {
        if (webView.canGoBack()) webView.goBack();
        else finish();
    }

    @Override
    public void onBackPressed() {   // Android 12 en ouder
        handleBack();
    }
}
