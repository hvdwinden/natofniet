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
        assertEquals("www.kleenex.nl", Pollen.sites(52.09, 5.12)[0]);     // Utrecht
        assertEquals("www.kleenex.nl", Pollen.sites(51.05, 3.72)[0]);     // Gent
        assertEquals("www.kleenex.co.uk", Pollen.sites(51.5, -0.12)[0]);  // Londen
        assertEquals("www.kleenex.co.uk", Pollen.sites(54.6, -5.93)[0]);  // Belfast
        assertEquals("www.kleenex.fr", Pollen.sites(45.76, 4.84)[0]);     // Lyon
        assertEquals("www.kleenex.fr", Pollen.sites(48.85, 2.35)[0]);     // Parijs
        assertEquals(0, Pollen.sites(52.52, 13.4).length);                // Berlijn: geen Kleenex
    }
}
