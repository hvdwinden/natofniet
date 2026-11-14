package nl.natofnietnat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Regenalarm: haalt de 2-uursverwachting van Buienradar op voor de laatst gekozen
 * locatie en stuurt een melding als het gaat regenen of droog wordt.
 */
public final class RainCheck {

    static final String PREFS = "settings";
    static final String STATE = "alarmState";
    static final String CHANNEL = "regenalarm";
    private static final int JOB_ID = 1;
    private static final double WET = 0.1;            // mm/u: vanaf hier telt het als regen
    private static final long SAME_EVENT_MS = 20 * 60_000L;

    private RainCheck() {}

    /** Eén 5-minutenwaarde uit de verwachting. */
    static final class Point {
        final long time; final double mm; final String hhmm;
        Point(long time, double mm, String hhmm) { this.time = time; this.mm = mm; this.hhmm = hhmm; }
    }

    // ---------- Planning ----------

    static boolean anyAlertOn(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        return p.getBoolean("rainAlert", false) || p.getBoolean("dryAlert", false);
    }

    /** Start of stop de periodieke controle, afhankelijk van de instellingen. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        // Nodig voor het regenalarm en/of om de widget op het startscherm actueel te houden
        if (!anyAlertOn(c) && !RainWidget.hasWidgets(c)) { js.cancel(JOB_ID); return; }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, RainJobService.class))
                .setPeriodic(15 * 60_000L)                    // minimum dat Android toestaat
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)                           // ook na herstart van de telefoon
                .build();
        try { js.schedule(job); }
        catch (RuntimeException e) { android.util.Log.e("NatOfNiet", "Kon regencontrole niet plannen", e); }
    }

    // ---------- Controle ----------

    /** Voert de controle uit (niet op de UI-thread aanroepen). */
    static void run(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        if (!p.contains("lat")) return;
        boolean rainAlert = p.getBoolean("rainAlert", false);
        boolean dryAlert = p.getBoolean("dryAlert", false);
        if (!rainAlert && !dryAlert) return;
        int lead = p.getInt("lead", 30);
        String place = p.getString("name", "je locatie");

        List<Point> f;
        try { f = fetch(p.getFloat("lat", 0), p.getFloat("lon", 0)); }
        catch (Exception e) { return; }
        if (f.isEmpty()) return;

        SharedPreferences s = c.getSharedPreferences(STATE, 0);
        long now = System.currentTimeMillis();
        boolean wetNow = f.get(0).mm >= WET;
        if (!wetNow) s.edit().putLong("lastDryObserved", now).apply();

        int firstWet = indexOf(f, 0, true);
        int firstDry = indexOf(f, 0, false);

        // Regen op komst (of al begonnen zonder dat we gemeld hebben)
        if (rainAlert && firstWet != -1) {
            Point start = f.get(firstWet);
            long startT = wetNow ? now : start.time;
            long minutes = Math.max(0, Math.round((startT - now) / 60_000.0));
            long lastRain = s.getLong("lastRainStart", 0);
            boolean already = Math.abs(startT - lastRain) < SAME_EVENT_MS
                    || (wetNow && lastRain > s.getLong("lastDryObserved", 0));
            if (minutes <= lead && !already
                    && notify(c, 1, rainTitle(f, firstWet, wetNow, minutes), rainText(f, firstWet, place))) {
                s.edit().putLong("lastRainStart", startT).apply();
            }
        }

        // Droog op komst terwijl het nu regent
        if (dryAlert && wetNow && firstDry != -1) {
            Point dry = f.get(firstDry);
            long minutes = Math.max(0, Math.round((dry.time - now) / 60_000.0));
            long lastDry = s.getLong("lastDryStart", 0);
            if (minutes <= lead && Math.abs(dry.time - lastDry) >= SAME_EVENT_MS
                    && notify(c, 2, "🌤️ Droog om " + dry.hhmm + " (over " + minutes + " min)", dryText(f, firstDry, place))) {
                s.edit().putLong("lastDryStart", dry.time).apply();
            }
        }
    }

    /** Testmelding met de echte verwachting van dit moment. */
    static void test(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        String place = p.getString("name", "je locatie");
        List<Point> f;
        try { f = fetch(p.getFloat("lat", 52.37f), p.getFloat("lon", 4.89f)); }
        catch (Exception e) { notify(c, 3, "Testmelding", "Kon de verwachting niet ophalen."); return; }
        if (f.isEmpty()) { notify(c, 3, "Testmelding", "Geen verwachting beschikbaar voor " + place + "."); return; }
        long now = System.currentTimeMillis();
        boolean wetNow = f.get(0).mm >= WET;
        int firstWet = indexOf(f, 0, true);
        if (firstWet == -1) {
            notify(c, 3, "🌤️ Droog in " + place, "De komende 2 uur (tot " + f.get(f.size() - 1).hhmm + ") geen regen verwacht.");
        } else {
            long minutes = Math.max(0, Math.round((f.get(firstWet).time - now) / 60_000.0));
            notify(c, 3, rainTitle(f, firstWet, wetNow, minutes), rainText(f, firstWet, place));
        }
    }

    // ---------- Teksten ----------

    private static String rainTitle(List<Point> f, int firstWet, boolean wetNow, long minutes) {
        if (wetNow) return "🌧️ Het regent nu";
        return "☔ Regen om " + f.get(firstWet).hhmm + " (over " + minutes + " min)";
    }

    /** Hoe hard, en wanneer de bui naar verwachting voorbij is. */
    private static String rainText(List<Point> f, int firstWet, String place) {
        int end = indexOf(f, firstWet, false);
        double peak = 0;
        for (int i = firstWet; i < (end == -1 ? f.size() : end); i++) peak = Math.max(peak, f.get(i).mm);
        String how = cap(intensity(peak)) + " in " + place + ". ";
        if (end == -1) return how + "Het blijft zeker tot " + f.get(f.size() - 1).hhmm + " nat.";
        String txt = how + "Naar verwachting droog om " + f.get(end).hhmm + ".";
        int again = indexOf(f, end, true);
        if (again != -1) txt += " Daarna weer regen vanaf " + f.get(again).hhmm + ".";
        return txt;
    }

    private static String dryText(List<Point> f, int firstDry, String place) {
        int again = indexOf(f, firstDry, true);
        if (again == -1) return "Daarna blijft het in " + place + " droog tot zeker " + f.get(f.size() - 1).hhmm + ".";
        return "Let op: vanaf " + f.get(again).hhmm + " weer " + intensity(f.get(again).mm) + " in " + place + ".";
    }

    private static String intensity(double mm) {
        if (mm < 2.5) return "lichte regen";
        if (mm < 10) return "matige regen";
        if (mm < 50) return "zware regen";
        return "hevige regen";
    }

    private static String cap(String s) { return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    /** Eerste index vanaf 'from' die nat (wet=true) of droog (wet=false) is, of -1. */
    private static int indexOf(List<Point> f, int from, boolean wet) {
        for (int i = from; i < f.size(); i++) if ((f.get(i).mm >= WET) == wet) return i;
        return -1;
    }

    // ---------- Data ----------

    /** Regen de komende 2 uur: Buienradar in Nederland en België, anders onze radarverwachting of Open-Meteo. */
    static List<Point> fetch(double lat, double lon) throws Exception {
        try {
            List<Point> b = fetchBuienradar(lat, lon);
            if (!b.isEmpty()) return b;
        } catch (Exception ignored) { /* buiten het gebied of geen antwoord */ }
        // Elders in Europa: onze eigen radarverwachting (RainViewer), anders het weermodel van Open-Meteo
        try {
            List<Point> r = RadarCast.fetch(lat, lon);
            if (r != null && !r.isEmpty()) return r;
        } catch (Exception ignored) { /* geen radar */ }
        return fetchOpenMeteo(lat, lon);
    }

    /**
     * Open-Meteo: per kwartier de neerslag (mm) van het voorafgaande kwartier. Omgezet naar 5-minutenwaarden in mm/u,
     * zodat de rest hetzelfde werkt als met Buienradar.
     */
    static List<Point> fetchOpenMeteo(double lat, double lon) throws Exception {
        URL url = new URL(String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&minutely_15=precipitation&past_minutely_15=1&forecast_minutely_15=10&timeformat=unixtime", lat, lon));
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(15_000);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            con.disconnect();
        }
        return parseOpenMeteo(sb.toString(), System.currentTimeMillis());
    }

    static List<Point> parseOpenMeteo(String json, long now) throws Exception {
        org.json.JSONObject m = new org.json.JSONObject(json).getJSONObject("minutely_15");
        org.json.JSONArray T = m.getJSONArray("time"), P = m.getJSONArray("precipitation");
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT);
        long t0 = now / 300_000L * 300_000L;
        List<Point> out = new ArrayList<>();
        for (int k = 0; k <= 24; k++) {
            long s = t0 + k * 300_000L;
            int i = -1;
            for (int j = 0; j < T.length(); j++) if (T.getLong(j) * 1000 > s) { i = j; break; }
            if (i == -1 || P.isNull(i)) break;
            out.add(new Point(s, P.getDouble(i) * 4, hm.format(new java.util.Date(s))));
        }
        return out;
    }

    /** Buienradar raintext: "waarde|HH:MM" per regel; mm/u = 10^((waarde-109)/32). */
    static List<Point> fetchBuienradar(double lat, double lon) throws Exception {
        URL url = new URL(String.format(Locale.ROOT,
                "https://gpsgadget.buienradar.nl/data/raintext?lat=%.2f&lon=%.2f", lat, lon));
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(15_000);
        List<Point> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            long now = System.currentTimeMillis();
            long prev = 0;
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\|");
                if (parts.length != 2) continue;
                int v = Integer.parseInt(parts[0].trim());
                String hhmm = parts[1].trim();
                String[] hm = hhmm.split(":");
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                cal.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long t = cal.getTimeInMillis();
                if (t - now > 12 * 3600_000L) t -= 24 * 3600_000L;   // rond middernacht
                if (prev != 0 && t < prev) t += 24 * 3600_000L;
                prev = t;
                double mm = v <= 0 ? 0 : Math.pow(10, (v - 109) / 32.0);
                out.add(new Point(t, mm, hhmm));
            }
        } finally {
            con.disconnect();
        }
        return out;
    }

    // ---------- Melding ----------

    static void createChannel(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Regenalarm", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Waarschuwt als het gaat regenen of droog wordt");
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    static boolean canNotify(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Toont een melding; geeft false als dat niet mag (geen toestemming). */
    static boolean notify(Context c, int id, String title, String text) {
        if (!canNotify(c)) return false;
        createChannel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
        android.util.Log.i("NatOfNiet", "Melding: " + title);
        return true;
    }
}
