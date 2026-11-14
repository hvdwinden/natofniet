package nl.natofnietnat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RadarCastTest {

    private static final int W = 384, BLUE = 0xFF0077AA;   // 25 dBZ

    private static int[] blob(int cx, int cy) {
        int[] px = new int[W * W];
        for (int y = cy - 6; y <= cy + 6; y++) for (int x = cx - 6; x <= cx + 6; x++) px[y * W + x] = BLUE;
        return px;
    }

    @Test
    public void kleurWordtMmPerUur() {
        assertEquals(0, RadarCast.pixelMm(0), 0);
        assertEquals(1.33, RadarCast.pixelMm(BLUE), 0.01);
    }

    @Test
    public void buiDieNaarJeToeTrektKomtOpTijdAan() {
        // Bui ligt 20 px ten westen van je plek en schoof in een half uur 10 px naar het oosten
        int hx = 192, hy = 192;
        long last = 1_000_000L;
        List<RainCheck.Point> f = RadarCast.cast(blob(hx - 30, hy), blob(hx - 20, hy), W, W, hx, hy, 1800, last, last * 1000);
        double[] v = RadarCast.motion(blob(hx - 30, hy), blob(hx - 20, hy), W, W, hx, hy);
        assertEquals(10, v[0], 0.6);
        assertEquals(0, v[1], 0.6);
        assertEquals(0, f.get(0).mm, 1e-9);                 // nu droog
        assertEquals(0, f.get(4).mm, 1e-9);                 // over 20 min nog droog (rand bui op 14 px)
        assertTrue(f.get(12).mm > 1);                       // over een uur nat (bui is dan bij je)
        assertEquals(0, f.get(24).mm, 1e-9);                // na 2 uur weer voorbij
    }
}
