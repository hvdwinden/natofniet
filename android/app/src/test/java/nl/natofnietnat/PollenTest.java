package nl.natofnietnat;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** Controleert het uitlezen van de Kleenex-pagina en de tekst van de pollenmelding. */
public class PollenTest {

    // Twee dagen in het formaat van kleenex.nl (waarden zoals in het voorbeeld van de Home Assistant-integratie)
    private static final String HTML =
            "<div><input id=\"cityName\" class=\"city-name\" type=\"hidden\" value=\"Delft\" />"
            + "<button class=\"day-link active\" data-grass=\"moderate\" data-trees=\"high\" data-weeds=\"low\" "
            + "data-grass-count=\"45 PPM\" data-weeds-count=\"3 PPM\" data-trees-count=\"260 PPM\" "
            + "data-grass-detail=\"Poaceae,45,moderate\" "
            + "data-tree-detail=\"Hazelaar,9,low|Els,120,moderate|Berk,0,low|Cipres,83,high\" "
            + "data-weed-detail=\"Bijvoet,0,low|Ambrosia,3,low\"><span class=\"day-number\">13</span></button>"
            + "<button class=\"day-link\" data-grass=\"low\" data-trees=\"low\" data-weeds=\"low\" "
            + "data-grass-count=\"0 PPM\" data-weeds-count=\"0 PPM\" data-trees-count=\"10 PPM\" "
            + "data-grass-detail=\"Poaceae,0,low\" data-tree-detail=\"Berk,10,low\" data-weed-detail=\"Bijvoet,0,low\">"
            + "</button></div>";

    @Test
    public void leestDagenEnSoorten() throws Exception {
        JSONObject p = Pollen.parseKleenex(HTML, "delft");
        assertEquals("kleenex", p.getString("source"));
        assertEquals("Delft", p.getString("place"));
        assertEquals(2, p.getJSONArray("days").length());
        JSONObject trees = p.getJSONArray("days").getJSONObject(0).getJSONObject("trees");
        assertEquals("high", trees.getString("level"));
        assertEquals(260, trees.getInt("value"));
        assertEquals("Cipres", trees.getJSONArray("details").getJSONObject(3).getString("name"));
    }

    @Test
    public void meldingNoemtSoortenVanafMatig() throws Exception {
        JSONObject p = Pollen.parseKleenex(HTML, "Delft");
        assertEquals("Els matig, Cipres hoog, Poaceae matig", Pollen.alertText(p, "trees,grass,weeds", "moderate"));
    }

    @Test
    public void meldingAlleenGekozenGroepenEnNiveau() throws Exception {
        JSONObject p = Pollen.parseKleenex(HTML, "Delft");
        assertEquals("Cipres hoog", Pollen.alertText(p, "trees", "high"));
        assertNull(Pollen.alertText(p, "grass,weeds", "high"));
    }

    @Test
    public void geenDagenGeeftNull() throws Exception {
        assertNull(Pollen.parseKleenex("<html>Plaats niet gevonden</html>", "Xyz"));
    }

    @Test
    public void niveausVoorReservebron() {
        assertEquals("low", Pollen.levelFor("grass", 20));
        assertEquals("moderate", Pollen.levelFor("grass", 50));
        assertEquals("very-high", Pollen.levelFor("trees", 900));
    }

    @Test
    public void fransePaginaKrijgtNederlandseNamen() throws Exception {
        String html = "<button class=\"day-link active\" data-grass=\"low\" data-trees=\"moderate\" data-weeds=\"low\" "
                + "data-grass-count=\"0 PPM\" data-weeds-count=\"0 PPM\" data-trees-count=\"120 PPM\" data-grass-detail=\"Poaceae ,0,low\" "
                + "data-tree-detail=\"Ch&#234;ne,120,moderate|Bouleau,3,low\" data-weed-detail=\"Armoise,0,low\">";
        JSONObject p = Pollen.parseKleenex(html, "Lyon");
        org.json.JSONArray trees = p.getJSONArray("days").getJSONObject(0).getJSONObject("trees").getJSONArray("details");
        assertEquals("Eik", trees.getJSONObject(0).getString("name"));
        assertEquals("Berk", trees.getJSONObject(1).getString("name"));
        assertEquals("Poaceae", p.getJSONArray("days").getJSONObject(0).getJSONObject("grass").getJSONArray("details").getJSONObject(0).getString("name"));
    }

    @Test
    public void juisteKleenexSitePerLand() {
        assertEquals("www.kleenex.nl", Pollen.siteFor("nl"));
        assertEquals("www.kleenex.co.uk", Pollen.siteFor("gb"));
        assertEquals("www.kleenex.fr", Pollen.siteFor("fr"));
        assertEquals("www.it.scottex.com", Pollen.siteFor("it"));
        assertEquals("www.kleenex.com", Pollen.siteFor("us"));
        assertEquals("www.kleenex.com.au", Pollen.siteFor("au"));
        assertNull(Pollen.siteFor("ca"));   // Canada: de Amerikaanse site kent alleen Toronto in Ohio
        assertNull(Pollen.siteFor("de"));
    }

    @Test
    public void afstandEnCoordinatenUitPagina() {
        double[] p = Pollen.latLon("<input id=\"pollenlat\" class=\"pollen-lat\" type=\"hidden\" value=\"-35.3065\" />"
                + "<input id=\"pollenlng\" class=\"pollen-lng\" type=\"hidden\" value=\"149.12\" />");
        assertEquals(-35.3065, p[0], 1e-9);
        assertTrue(Pollen.km(-34.93, 138.6, p[0], p[1]) > 900);   // Adelaide ↔ Canberra
        assertTrue(Pollen.km(52.09, 5.12, 52.0919, 5.1229) < 1);
    }

    @Test
    public void scottexItalie() throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                getClass().getClassLoader().getResource("scottex-milano.json").toURI())), "UTF-8");
        JSONObject p = Pollen.parseScottex(json, "Milano");
        assertEquals("Milan", p.getString("place"));
        assertEquals(45.4685, p.getDouble("lat"), 1e-3);
        org.json.JSONArray days = p.getJSONArray("days");
        assertEquals(5, days.length());
        JSONObject trees = days.getJSONObject(0).getJSONObject("trees");
        assertEquals("low", trees.getString("level"));
        assertEquals(5, trees.getInt("value"));
        assertEquals("Cipres", trees.getJSONArray("details").getJSONObject(0).getString("name"));
        assertNull(Pollen.parseScottex("{\"html\":\"\",\"city\":null}", "Xyz"));
    }

    @Test
    public void legeWaardenBuitenEuropaGevenGeenPollen() throws Exception {
        String[][] sp = {{"grass_pollen", "Gras", "grass"}};
        JSONObject leeg = new JSONObject("{\"time\":[\"2026-11-14T00:00\",\"2026-11-14T01:00\"],\"grass_pollen\":[null,null]}");
        assertNull(Pollen.parseOpenMeteo(leeg, sp));
        JSONObject vol = new JSONObject("{\"time\":[\"2026-11-14T00:00\",\"2026-11-14T01:00\"],\"grass_pollen\":[null,40]}");
        assertEquals("moderate", Pollen.parseOpenMeteo(vol, sp).getJSONArray("days").getJSONObject(0).getJSONObject("grass").getString("level"));
    }
}
