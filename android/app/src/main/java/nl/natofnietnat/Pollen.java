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

    /**
     * Kleenex heeft dezelfde pollenradar in Nederland, het VK, Frankrijk, de VS en Australië; in Italië heet hij
     * Scottex (ander formaat). Het land bepalen we met OpenStreetMap, niet met een rechthoek op de kaart:
     * anders krijgt Toronto (Canada) de cijfers van Toronto in Ohio.
     */
    static JSONObject kleenex(String city, double lat, double lon) throws Exception {
        // Nederland en Vlaanderen: meteen proberen, zonder opzoekactie
        if (lat > 50.7 && lat < 53.7 && lon > 3.3 && lon < 7.3) {
            JSONObject k = trySite("www.kleenex.nl", city, lat, lon);
            if (k != null) return k;
        }
        JSONObject where = reverse(lat, lon, "en");
        JSONObject a = where == null ? null : where.optJSONObject("address");
        if (a == null) return null;
        String cc = a.optString("country_code", "");
        String site = siteFor(cc);
        if (site == null) return null;
        String lang = cc.equals("fr") || cc.equals("be") ? "fr" : cc.equals("it") ? "it" : cc.equals("nl") ? "nl" : "en";
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.add(city);
        String local = lang.equals("en") ? placeName(a) : placeName(addressOf(reverse(lat, lon, lang)));
        if (local != null) names.add(local);
        for (String name : names) {
            JSONObject k = trySite(site, name, lat, lon);
            if (k != null) return k;
        }
        return null;
    }

    /** Kleenex-site per land (landcode van OpenStreetMap), of null als Kleenex daar niets heeft. */
    static String siteFor(String cc) {
        switch (cc) {
            case "nl": return "www.kleenex.nl";
            case "be": case "fr": case "mc": return "www.kleenex.fr";
            case "gb": case "im": case "je": case "gg": return "www.kleenex.co.uk";
            case "it": case "sm": case "va": return "www.it.scottex.com";
            case "us": return "www.kleenex.com";
            case "au": return "www.kleenex.com.au";
            default: return null;
        }
    }

    private static JSONObject trySite(String site, String name, double lat, double lon) throws Exception {
        JSONObject k;
        double[] at;
        if (site.contains("scottex")) {
            String json = httpPost("https://" + site + "/api/sitecore/Pollen/GetPollenData", "location=" + URLEncoder.encode(name, "UTF-8"));
            k = parseScottex(json, name);
            at = k == null ? null : new double[]{k.optDouble("lat"), k.optDouble("lon")};
        } else {
            String html = http("https://" + site + "/api/sitecore/Pollen/GetPollenContentCity?city=" + URLEncoder.encode(name, "UTF-8"), true);
            k = parseKleenex(html, name);
            at = latLon(html);
        }
        if (k == null) return null;
        // De site zoekt zelf de plaats op; bij een onbekende naam valt hij soms terug op het hele land. Alleen
        // gebruiken als de gevonden plek dicht bij de jouwe ligt.
        if (at != null && !Double.isNaN(at[0]) && km(lat, lon, at[0], at[1]) > 75) return null;
        k.remove("lat"); k.remove("lon");
        return k;
    }

    static double km(double la1, double lo1, double la2, double lo2) {
        double x = Math.toRadians(lo2 - lo1) * Math.cos(Math.toRadians((la1 + la2) / 2)), y = Math.toRadians(la2 - la1);
        return Math.sqrt(x * x + y * y) * 6371;
    }

    static double[] latLon(String html) {
        Matcher la = Pattern.compile("id=\"pollenlat\"[^>]*value=\"([-0-9.]+)\"").matcher(html);
        Matcher lo = Pattern.compile("id=\"pollenlng\"[^>]*value=\"([-0-9.]+)\"").matcher(html);
        return la.find() && lo.find() ? new double[]{Double.parseDouble(la.group(1)), Double.parseDouble(lo.group(1))} : null;
    }

    private static JSONObject reverse(double lat, double lon, String lang) throws Exception {
        return new JSONObject(http(String.format(Locale.ROOT,
                "https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&accept-language=%s&lat=%.4f&lon=%.4f", lang, lat, lon), true));
    }

    private static JSONObject addressOf(JSONObject j) { return j == null ? null : j.optJSONObject("address"); }

    private static String placeName(JSONObject a) {
        if (a == null) return null;
        for (String k : new String[]{"city", "town", "village", "municipality"}) if (a.has(k)) return a.optString(k);
        return null;
    }

    // ---------- Scottex (Italië) ----------

    private static final Pattern IT_CARD = Pattern.compile(
            "data-show=\"(grass|tree|weed)-pollen-analysis-day-(\\d)\"[\\s\\S]*?data-status=\"([^\"]*)\"[\\s\\S]*?number-text\">\\s*([0-9]+)");
    private static final Pattern IT_DETAIL = Pattern.compile("name-text\">([^<]*)</span>\\s*<span class=\"quality-text\">[^0-9<]*([0-9]+)");

    /** Antwoord van it.scottex.com: {"html": ..., "city": "Milan|45.46|9.18"}. */
    static JSONObject parseScottex(String json, String city) throws Exception {
        JSONObject j = new JSONObject(json);
        String html = j.optString("html", "");
        if (html.isEmpty()) return null;
        String[] c = j.optString("city", "").split("\\|");
        JSONObject[] days = new JSONObject[5];
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        for (int d = 0; d < 5; d++) { days[d] = new JSONObject().put("date", iso.format(cal.getTime())); cal.add(Calendar.DAY_OF_YEAR, 1); }
        Matcher m = IT_CARD.matcher(html);
        int found = 0;
        while (m.find()) {
            int d = Integer.parseInt(m.group(2)) - 1;
            if (d < 0 || d >= 5) continue;
            String g = m.group(1).equals("tree") ? "trees" : m.group(1).equals("weed") ? "weeds" : "grass";
            int value = Integer.parseInt(m.group(4));
            String level = m.group(3).trim().toLowerCase(Locale.ROOT).replace(' ', '-');
            if (!level.matches("low|moderate|high|very-high")) level = levelFor(g, value);
            JSONArray details = new JSONArray();
            // Soorten staan in een eigen blok per groep en dag
            int at = html.indexOf(m.group(1) + "-pollen-analysis-day-" + (d + 1) + "\" id=");
            if (at >= 0) {
                int end = html.indexOf("-pollen-analysis-day-", at + 30);
                Matcher dm = IT_DETAIL.matcher(html.substring(at, end < 0 ? html.length() : end));
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (dm.find()) {
                    String name = NAMES.getOrDefault(dm.group(1).trim(), dm.group(1).trim());
                    if (!seen.add(name)) continue;
                    int v = Integer.parseInt(dm.group(2));
                    details.put(new JSONObject().put("name", name).put("value", v).put("level", levelFor(g, v)));
                }
            }
            days[d].put(g, new JSONObject().put("level", level).put("value", value).put("details", details));
            found++;
        }
        if (found == 0) return null;
        JSONArray arr = new JSONArray();
        for (JSONObject d : days) if (d.has("grass") || d.has("trees") || d.has("weeds")) arr.put(d);
        JSONObject out = new JSONObject().put("source", "kleenex").put("place", c[0].isEmpty() ? city : c[0]).put("days", arr);
        if (c.length >= 3) out.put("lat", Double.parseDouble(c[1])).put("lon", Double.parseDouble(c[2]));
        return out;
    }

    /** Soortnamen van de Engelse en Franse site in het Nederlands. */
    private static final java.util.Map<String, String> NAMES = new java.util.HashMap<>();
    static {
        String[][] n = {{"Hazel", "Hazelaar"}, {"Elm", "Iep"}, {"Pine", "Pijnboom"}, {"Alder", "Els"}, {"Poplar", "Populier"},
                {"Oak", "Eik"}, {"Plane", "Plataan"}, {"Birch", "Berk"}, {"Cypress", "Cipres"}, {"Mugwort", "Bijvoet"},
                {"Chenopod", "Ganzevoet"}, {"Ragweed", "Ambrosia"}, {"Nettle", "Brandnetel"},
                {"Noisetier", "Hazelaar"}, {"Orme", "Iep"}, {"Pin", "Pijnboom"}, {"Aulne", "Els"}, {"Peuplier", "Populier"},
                {"Chêne", "Eik"}, {"Platane", "Plataan"}, {"Bouleau", "Berk"}, {"Cyprès", "Cipres"}, {"Armoise", "Bijvoet"},
                {"Chénopodes", "Ganzevoet"}, {"Ambroisie", "Ambrosia"}, {"Ortie", "Brandnetel"}, {"Urticacées", "Brandnetel"},
                {"Chenepod", "Ganzevoet"}, {"GrassPoaceae", "Poaceae"}, {"Casuarina", "Casuarina"}, {"Acacia", "Acacia"},
                {"Myrtaceae", "Mirtefamilie"}, {"Olive", "Olijf"}, {"Willow", "Wilg"}, {"Sedges", "Zegge"}, {"Aster", "Aster"},
                {"Plantago", "Weegbree"}, {"Rumex", "Zuring"}};
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
        return parseOpenMeteo(j.getJSONObject("hourly"), species);
    }

    /** Buiten Europa geeft Copernicus alleen lege waarden: dan null (geen pollenkaart), niet "Laag". */
    static JSONObject parseOpenMeteo(JSONObject h, String[][] species) throws Exception {
        boolean any = false;
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
                    for (int i = start; i < Math.min(start + 24, arr.length()); i++) {
                        if (arr.isNull(i)) continue;
                        any = true;
                        max = Math.max(max, arr.optDouble(i, 0));
                    }
                    sum += max;
                    details.put(new JSONObject().put("name", s[1]).put("value", Math.round(max)).put("level", levelFor(g, max)));
                }
                day.put(g, new JSONObject().put("level", levelFor(g, sum)).put("value", Math.round(sum)).put("details", details));
            }
            days.put(day);
        }
        return any ? new JSONObject().put("source", "openmeteo").put("days", days) : null;
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

    private static String httpPost(String u, String form) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setConnectTimeout(12_000);
        con.setReadTimeout(12_000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NatOfNiet/1.0");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try {
            con.getOutputStream().write(form.getBytes("UTF-8"));
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } finally {
            con.disconnect();
        }
    }
}
