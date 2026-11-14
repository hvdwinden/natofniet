package nl.natofnietnat;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * Ochtendmeldingen: het dagelijkse weerbericht en de UV-melding.
 * Een wekker (AlarmManager) gaat elke dag op het gekozen tijdstip af; daarna wordt de volgende dag ingepland.
 * Deze klasse is ook de ontvanger voor die wekker en voor het opnieuw opstarten van de telefoon.
 */
public class MorningReport extends BroadcastReceiver {

    static final String ACTION = "nl.natofnietnat.MORNING";

    // ---------- Planning ----------

    static boolean anyOn(Context c) {
        SharedPreferences p = c.getSharedPreferences(RainCheck.PREFS, 0);
        return p.getBoolean("daily", false) || p.getBoolean("uvAlert", false) || p.getBoolean("pollenAlert", false);
    }

    /** Zet de wekker voor de eerstvolgende ochtendmelding, of haalt hem weg als alles uit staat. */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 10,
                new Intent(c, MorningReport.class).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE);
        if (!anyOn(c)) { am.cancel(pi); return; }
        int minutes = c.getSharedPreferences(RainCheck.PREFS, 0).getInt("morningTime", 7 * 60);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minutes / 60);
        cal.set(Calendar.MINUTE, minutes % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 30_000) cal.add(Calendar.DAY_OF_YEAR, 1);
        // Precies op tijd, ook in slaapstand; lukt dat niet, dan een niet-exacte wekker
        boolean exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (ACTION.equals(a)) {
            PendingResult result = goAsync();
            new Thread(() -> {
                try { send(c.getApplicationContext(), false); }
                finally { schedule(c); result.finish(); }
            }).start();
        } else {
            // Telefoon herstart of app bijgewerkt: wekkers en achtergrondcontrole opnieuw zetten
            schedule(c);
            RainCheck.schedule(c);
        }
    }

    // ---------- Inhoud ----------

    /** Stuurt het weerbericht en/of de UV-melding. test = true: allebei, ongeacht instellingen. */
    static void send(Context c, boolean test) {
        SharedPreferences p = c.getSharedPreferences(RainCheck.PREFS, 0);
        if (!p.contains("lat")) {
            if (test) RainCheck.notify(c, 4, "Ochtendmelding", "Open de app eerst even, zodat je locatie bekend is.");
            return;
        }
        String place = p.getString("name", "je locatie");
        JSONObject j;
        try { j = fetch(p.getFloat("lat", 0), p.getFloat("lon", 0)); }
        catch (Exception e) {
            if (test) RainCheck.notify(c, 4, "Ochtendmelding", "Kon de verwachting niet ophalen.");
            return;
        }
        try {
            JSONObject d = j.getJSONObject("daily");
            int code = d.getJSONArray("weather_code").getInt(0);
            double tmax = d.getJSONArray("temperature_2m_max").getDouble(0);
            double tmin = d.getJSONArray("temperature_2m_min").getDouble(0);
            double mm = d.getJSONArray("precipitation_sum").optDouble(0, 0);
            int prob = d.getJSONArray("precipitation_probability_max").optInt(0, 0);
            double wind = d.getJSONArray("wind_speed_10m_max").getDouble(0);
            int dir = d.getJSONArray("wind_direction_10m_dominant").getInt(0);
            double uvMax = d.getJSONArray("uv_index_max").optDouble(0, 0);

            if (test || p.getBoolean("daily", false)) {
                String[] w = weather(code);
                String title = w[0] + " Vandaag in " + place + ": " + temp(p, tmin) + "/" + temp(p, tmax);
                StringBuilder txt = new StringBuilder(w[1]).append(". ");
                if (mm < 0.1 && prob < 30) txt.append("Droog. ");
                else txt.append("Neerslag ").append(num(mm)).append(" mm (kans ").append(prob).append("%). ");
                txt.append("Wind ").append(windText(p, wind, dir)).append(".");
                if (uvMax >= 3) txt.append(" UV-index tot ").append(Math.round(uvMax)).append(".");
                RainCheck.notify(c, 4, title, txt.toString());
            }

            int uvMin = p.getInt("uvMin", 6);
            if (test || (p.getBoolean("uvAlert", false) && uvMax >= uvMin)) {
                int threshold = test ? Math.min(uvMin, Math.max(1, (int) Math.floor(uvMax))) : uvMin;
                RainCheck.notify(c, 5, "🧴 " + uvLabel(uvMax) + " UV-index vandaag (" + Math.round(uvMax) + ")",
                        uvText(j, threshold, place, uvMax));
            }
        } catch (Exception e) {
            if (test) RainCheck.notify(c, 4, "Ochtendmelding", "De verwachting kon niet gelezen worden.");
        }

        // Pollen
        if (test || p.getBoolean("pollenAlert", false)) {
            try {
                JSONObject pol = Pollen.get(p.getString("name", ""), p.getFloat("lat", 0), p.getFloat("lon", 0));
                if (pol == null) {
                    if (test) RainCheck.notify(c, 6, "🤧 Pollen", "Kon de pollenverwachting niet ophalen.");
                    return;
                }
                String groups = p.getString("pollenGroups", "trees,grass,weeds");
                String min = p.getString("pollenMin", "moderate");
                String txt = Pollen.alertText(pol, groups, min);
                if (txt != null) {
                    RainCheck.notify(c, 6, "🤧 Veel pollen vandaag in " + place, txt + ". Bron: " + sourceName(pol) + ".");
                } else if (test) {
                    RainCheck.notify(c, 6, "🤧 Weinig pollen vandaag in " + place,
                            "Geen soorten op \"" + Pollen.levelNl(min) + "\" of hoger. Bron: " + sourceName(pol) + ".");
                }
            } catch (Exception e) {
                if (test) RainCheck.notify(c, 6, "🤧 Pollen", "De pollenverwachting kon niet gelezen worden.");
            }
        }
    }

    private static String sourceName(JSONObject pol) {
        return "kleenex".equals(pol.optString("source")) ? "Kleenex pollenradar" : "Open-Meteo (CAMS)";
    }

    /** Tussen welke uren is de UV-index minstens 'threshold'? */
    private static String uvText(JSONObject j, int threshold, String place, double uvMax) throws Exception {
        JSONObject h = j.getJSONObject("hourly");
        JSONArray times = h.getJSONArray("time"), uv = h.getJSONArray("uv_index");
        String from = null, to = null;
        for (int i = 0; i < times.length(); i++) {
            if (uv.optDouble(i, 0) >= threshold) {
                String hhmm = times.getString(i).substring(11, 16);
                if (from == null) from = hhmm;
                to = String.format(Locale.ROOT, "%02d:00", Integer.parseInt(hhmm.substring(0, 2)) + 1);
            }
        }
        String advice = uvMax >= 8 ? "Smeer je goed in en blijf rond het middaguur uit de zon."
                : uvMax >= 6 ? "Smeer je in en zoek rond het middaguur de schaduw."
                : uvMax >= 3 ? "Bij langer buiten zijn: smeer je in." : "Weinig risico.";
        if (from == null) return "Maximaal " + Math.round(uvMax) + " in " + place + ". " + advice;
        return "Tussen " + from + " en " + to + " UV-index " + threshold + " of hoger in " + place + ". " + advice;
    }

    private static String uvLabel(double uv) {
        if (uv >= 11) return "Extreme";
        if (uv >= 8) return "Zeer hoge";
        if (uv >= 6) return "Hoge";
        if (uv >= 3) return "Matige";
        return "Lage";
    }

    // ---------- Hulpjes ----------

    private static JSONObject fetch(double lat, double lon) throws Exception {
        URL url = new URL(String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
                "precipitation_probability_max,wind_speed_10m_max,wind_direction_10m_dominant,uv_index_max" +
                "&hourly=uv_index&forecast_days=1&timezone=auto&wind_speed_unit=ms", lat, lon));
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(15_000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } finally {
            con.disconnect();
        }
    }

    private static String num(double v) { return String.format(Locale.GERMANY, "%.1f", v); }

    private static String temp(SharedPreferences p, double c) {
        boolean f = "F".equals(p.getString("tempUnit", "C"));
        return Math.round(f ? c * 9 / 5 + 32 : c) + "°";
    }

    private static String windText(SharedPreferences p, double ms, int dir) {
        String d = new String[]{"N", "NO", "O", "ZO", "Z", "ZW", "W", "NW"}[Math.round(dir / 45f) % 8];
        String unit = p.getString("windUnit", "bft");
        if ("kmh".equals(unit)) return d + " " + Math.round(ms * 3.6) + " km/u";
        if ("ms".equals(unit)) return d + " " + Math.round(ms) + " m/s";
        double[] lim = {0.3, 1.6, 3.4, 5.5, 8, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7};
        int b = 12;
        for (int i = 0; i < lim.length; i++) if (ms < lim[i]) { b = i; break; }
        return d + " " + b + " Bft";
    }

    /** WMO-weercode → {icoon, omschrijving}. */
    private static String[] weather(int code) {
        switch (code) {
            case 0: return new String[]{"☀️", "Zonnig"};
            case 1: return new String[]{"🌤️", "Overwegend zonnig"};
            case 2: return new String[]{"⛅", "Half bewolkt"};
            case 3: return new String[]{"☁️", "Bewolkt"};
            case 45: case 48: return new String[]{"🌫️", "Mist"};
            case 51: case 53: return new String[]{"🌦️", "Motregen"};
            case 55: case 56: case 57: return new String[]{"🌧️", "Dichte motregen"};
            case 61: return new String[]{"🌧️", "Lichte regen"};
            case 63: return new String[]{"🌧️", "Regen"};
            case 65: case 66: case 67: return new String[]{"🌧️", "Zware regen"};
            case 71: case 73: case 77: case 85: return new String[]{"🌨️", "Sneeuw"};
            case 75: case 86: return new String[]{"❄️", "Zware sneeuw"};
            case 80: return new String[]{"🌦️", "Af en toe een bui"};
            case 81: return new String[]{"🌦️", "Buien"};
            case 82: return new String[]{"⛈️", "Zware buien"};
            case 95: case 96: case 99: return new String[]{"⛈️", "Onweer"};
            default: return new String[]{"🌡️", "Wisselend"};
        }
    }
}
