package nl.natofnietnat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pollenverwachting.
 * Hoofdbron: de Kleenex-pollenradar (onofficieel, bekend van Home Assistant; per soort, 5 dagen).
 * Reserve: Open-Meteo luchtkwaliteit (Copernicus CAMS-model, officiële gratis API).
 *
 * Resultaat (JSON): { source, place, days: [ { date, trees|grass|weeds: { level, value, details: [ {name, value, level} ] } } ] }
 * Niveaus: low, moderate, high, very-high.
 */
public final class Pollen {

    static final String[] GROUPS = {"trees", "grass", "weeds"};

    private Pollen() {}

    static JSONObject get(String city, double lat, double lon) {
        if (city != null && !city.isEmpty() && !city.matches("[-0-9., ]+")) {
            try {
                JSONObject k = kleenex(city, lat, lon);
                if (k != null) return k;
            } catch (Exception ignored) { /* val terug op Open-Meteo */ }
        }
        try { return openMeteo(lat, lon); }
        catch (Exception e) { return null; }
    }

    // ---------- Kleenex ----------

    private static final Pattern DAY = Pattern.compile("<button[^>]*class=\"day-link[^\"]*\"[^>]*>");
    private static final Pattern ATTR = Pattern.compile("data-([a-z-]+)=\"([^\"]*)\"");

    /** Kleenex heeft dezelfde pollenradar in Nederland, het Verenigd Koninkrijk en Frankrijk. */
    static JSONObject kleenex(String city, double lat, double lon) throws Exception {
        for (String site : sites(lat, lon)) {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            names.add(city);
            // De app kent de Nederlandse naam ("Londen", "Rijsel"); Kleenex daar de Engelse of Franse
            if (!site.endsWith(".nl")) {
                try { String local = localName(lat, lon, site.endsWith(".fr") ? "fr" : "en"); if (local != null) names.add(local); }
                catch (Exception ignored) { }
            }
            for (String name : names) {
                String html = http("https://" + site + "/api/sitecore/Pollen/GetPollenContentCity?city="
                        + URLEncoder.encode(name, "UTF-8"), true);
                JSONObject k = parseKleenex(html, name);
                if (k != null) return k;
            }
        }
        return null;
    }

    /** Plaatsnaam in de taal van het land (OpenStreetMap). */
    private static String localName(double lat, double lon, String lang) throws Exception {
        JSONObject j = new JSONObject(http(String.format(Locale.ROOT,
                "https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&accept-language=%s&lat=%.4f&lon=%.4f", lang, lat, lon), true));
        JSONObject a = j.optJSONObject("address");
        if (a == null) return null;
        for (String k : new String[]{"city", "town", "village", "municipality"}) if (a.has(k)) return a.getString(k);
        return null;
    }

    /** Welke Kleenex-site(s) bij deze plek horen (ruwe grenzen; bij twijfel ook Nederland). */
    static String[] sites(double lat, double lon) {
        boolean uk = lat > 49.8 && lat < 61 && lon > -8.7 && lon < 1.8 && (lon < -1.9 || lat > 51.05);
        boolean fr = lat > 41.2 && lat < 51.1 && lon > -5.2 && lon < 9.7 && !uk && !(lat > 49.4 && lon > 2.6);
        if (uk) return new String[]{"www.kleenex.co.uk"};
        if (fr) return new String[]{"www.kleenex.fr"};
        if (lat > 49.4 && lat < 53.7 && lon > 2.5 && lon < 7.3) return new String[]{"www.kleenex.nl", "www.kleenex.fr"};
        return new String[0];
    }

    /** Soortnamen van de Engelse en Franse site in het Nederlands. */
    private static final java.util.Map<String, String> NAMES = new java.util.HashMap<>();
    static {
        String[][] n = {{"Hazel", "Hazelaar"}, {"Elm", "Iep"}, {"Pine", "Pijnboom"}, {"Alder", "Els"}, {"Poplar", "Populier"},
                {"Oak", "Eik"}, {"Plane", "Plataan"}, {"Birch", "Berk"}, {"Cypress", "Cipres"}, {"Mugwort", "Bijvoet"},
                {"Chenopod", "Ganzevoet"}, {"Ragweed", "Ambrosia"}, {"Nettle", "Brandnetel"},
                {"Noisetier", "Hazelaar"}, {"Orme", "Iep"}, {"Pin", "Pijnboom"}, {"Aulne", "Els"}, {"Peuplier", "Populier"},
                {"Chêne", "Eik"}, {"Platane", "Plataan"}, {"Bouleau", "Berk"}, {"Cyprès", "Cipres"}, {"Armoise", "Bijvoet"},
                {"Chénopodes", "Ganzevoet"}, {"Ambroisie", "Ambrosia"}, {"Ortie", "Brandnetel"}, {"Urticacées", "Brandnetel"}};
        for (String[] p : n) NAMES.put(p[0], p[1]);
    }

    /** "Ch&#234;ne" → "Chêne" */
    static String unescape(String s) {
        Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        m.appendTail(b);
        return s.indexOf('&') < 0 ? s : b.toString().replace("&amp;", "&");
    }

    /** Leest de dagknoppen uit de Kleenex-HTML (los van het ophalen, zodat het te testen is). */
    static JSONObject parseKleenex(String html, String city) throws Exception {
        Matcher m = DAY.matcher(html);
        JSONArray days = new JSONArray();
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        while (m.find()) {
            JSONObject a = new JSONObject();
            Matcher am = ATTR.matcher(m.group());
            while (am.find()) a.put(am.group(1), am.group(2));
            JSONObject day = new JSONObject().put("date", iso.format(cal.getTime()));
            day.put("trees", group(a, "trees", "tree"));
            day.put("grass", group(a, "grass", "grass"));
            day.put("weeds", group(a, "weeds", "weed"));
            days.put(day);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (days.length() == 0) return null;   // pagina veranderd of plaats onbekend
        Matcher name = Pattern.compile("id=\"cityName\"[^>]*value=\"([^\"]*)\"").matcher(html);
        return new JSONObject().put("source", "kleenex").put("place", name.find() ? name.group(1) : city).put("days", days);
    }

    private static JSONObject group(JSONObject a, String key, String detailKey) throws Exception {
        String digits = a.optString(key + "-count", "").replaceAll("[^0-9]", "");   // "133 PPM" → 133
        int value = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        String level = a.optString(key, "");
        if (level.isEmpty()) level = levelFor(key, value);
        JSONArray details = new JSONArray();
        for (String item : a.optString(detailKey + "-detail", "").split("\\|")) {
            String[] p = item.split(",");
            if (p.length != 3) continue;
            String name = unescape(p[0]).trim();
            name = NAMES.getOrDefault(name, name);
            details.put(new JSONObject().put("name", name).put("value", Integer.parseInt(p[1].trim())).put("level", p[2]));
        }
        return new JSONObject().put("level", level).put("value", value).put("details", details);
    }

    /** Drempels zoals de Kleenex-integratie ze gebruikt (korrels per m³). */
    static String levelFor(String group, double v) {
        double[] t = group.equals("trees") ? new double[]{95, 207, 703}
                : group.equals("grass") ? new double[]{29, 60, 341} : new double[]{20, 77, 266};
        if (v <= t[0]) return "low";
        if (v <= t[1]) return "moderate";
        if (v <= t[2]) return "high";
        return "very-high";
    }

    // ---------- Open-Meteo (reserve) ----------

    static JSONObject openMeteo(double lat, double lon) throws Exception {
        String[][] species = {
                {"alder_pollen", "Els", "trees"}, {"birch_pollen", "Berk", "trees"},
                {"grass_pollen", "Gras", "grass"},
                {"mugwort_pollen", "Bijvoet", "weeds"}, {"ragweed_pollen", "Ambrosia", "weeds"}};
        StringBuilder vars = new StringBuilder();
        for (String[] s : species) vars.append(vars.length() > 0 ? "," : "").append(s[0]);
        JSONObject j = new JSONObject(http(String.format(Locale.ROOT,
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.3f&longitude=%.3f&hourly=%s&forecast_days=4&timezone=auto",
                lat, lon, vars), false));
        JSONObject h = j.getJSONObject("hourly");
        JSONArray times = h.getJSONArray("time");
        JSONArray days = new JSONArray();
        for (int start = 0; start < times.length(); start += 24) {
            JSONObject day = new JSONObject().put("date", times.getString(start).substring(0, 10));
            for (String g : GROUPS) {
                double sum = 0;
                JSONArray details = new JSONArray();
                for (String[] s : species) {
                    if (!s[2].equals(g)) continue;
                    JSONArray arr = h.getJSONArray(s[0]);
                    double max = 0;
                    for (int i = start; i < Math.min(start + 24, arr.length()); i++) max = Math.max(max, arr.optDouble(i, 0));
                    sum += max;
                    details.put(new JSONObject().put("name", s[1]).put("value", Math.round(max)).put("level", levelFor(g, max)));
                }
                day.put(g, new JSONObject().put("level", levelFor(g, sum)).put("value", Math.round(sum)).put("details", details));
            }
            days.put(day);
        }
        return new JSONObject().put("source", "openmeteo").put("days", days);
    }

    // ---------- Melding-tekst ----------

    static int rank(String level) {
        switch (level) {
            case "moderate": return 1;
            case "high": return 2;
            case "very-high": return 3;
            default: return 0;
        }
    }

    static String levelNl(String level) {
        switch (level) {
            case "moderate": return "matig";
            case "high": return "hoog";
            case "very-high": return "zeer hoog";
            default: return "laag";
        }
    }

    static String groupNl(String g) {
        return g.equals("trees") ? "Bomen" : g.equals("grass") ? "Gras" : "Kruiden";
    }

    /**
     * Tekst voor de soorten (uit de gekozen groepen) die vandaag minstens 'minLevel' halen, of null als er niets is.
     */
    static String alertText(JSONObject p, String groups, String minLevel) throws Exception {
        JSONObject today = p.getJSONArray("days").getJSONObject(0);
        int min = rank(minLevel);
        StringBuilder sb = new StringBuilder();
        for (String g : GROUPS) {
            if (!groups.contains(g)) continue;
            JSONObject grp = today.getJSONObject(g);
            JSONArray det = grp.getJSONArray("details");
            boolean any = false;
            for (int i = 0; i < det.length(); i++) {
                JSONObject d = det.getJSONObject(i);
                if (rank(d.getString("level")) >= min) {
                    sb.append(sb.length() > 0 ? ", " : "").append(d.getString("name")).append(" ").append(levelNl(d.getString("level")));
                    any = true;
                }
            }
            if (!any && rank(grp.getString("level")) >= min) {
                sb.append(sb.length() > 0 ? ", " : "").append(groupNl(g)).append(" ").append(levelNl(grp.getString("level")));
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ---------- HTTP ----------

    private static String http(String u, boolean browserAgent) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setConnectTimeout(12_000);
        con.setReadTimeout(12_000);
        if (browserAgent) con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NatOfNiet/1.0");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }
}
