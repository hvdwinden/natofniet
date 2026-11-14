package nl.natofnietnat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class RainCheckTest {

    // Kwartierwaarden (mm in het voorafgaande kwartier), tijden in seconden
    private static final String JSON = "{\"minutely_15\":{\"time\":[1000800,1001700,1002600,1003500,1004400,1005300,1006200,1007100,1008000,1008900,1009800],"
            + "\"precipitation\":[0,0.5,0,0,1,0,0,0,0,0,0]}}";

    @Test
    public void openMeteoWordtVijfMinutenInMmPerUur() throws Exception {
        long now = 1000800_000L + 60_000L;            // 1 minuut na het eerste kwartier
        List<RainCheck.Point> f = RainCheck.parseOpenMeteo(JSON, now);
        assertEquals(25, f.size());
        assertEquals(1000800_000L, f.get(0).time);  // afgerond op 5 minuten
        assertEquals(2.0, f.get(0).mm, 1e-9);       // valt in het kwartier tot 1001700: 0,5 mm × 4
        assertEquals(2.0, f.get(2).mm, 1e-9);
        assertEquals(0.0, f.get(3).mm, 1e-9);
        assertEquals(4.0, f.get(9).mm, 1e-9);       // kwartier tot 1004400
    }
}
