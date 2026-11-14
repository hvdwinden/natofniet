package nl.natofnietnat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Eigen radarverwachting buiten Nederland en België (zelfde rekenwijze als de app):
 * twee RainViewer-beelden (nu en een half uur eerder) → in welke richting trekken de buien →
 * het laatste beeld doorschuiven en per 5 minuten aflezen hoeveel regen er op jouw plek valt.
 */
final class RadarCast {

    private RadarCast() {}

    static final int Z = 6, SIZE = 384;          // 3×3 tegels van 256 px, verkleind naar de helft
    static final int R = 60, M = 18;              // gebied ±R px rond je plek, verschuiving zoeken tot ±M px

    /** RainViewer-kleuren (Universal Blue): {r, g, b, a, dBZ}. */
    static final int[][] COLORS = {{146,136,113,100,5},{158,147,117,110,6},{170,158,121,120,7},{182,169,126,130,8},{194,180,130,140,9},{206,192,135,150,10},{210,196,139,160,11},{214,200,143,170,12},{218,204,147,180,13},{222,208,151,190,14},{136,221,238,255,15},{108,209,235,255,16},{81,197,232,255,17},{54,186,229,255,18},{27,174,226,255,19},{0,163,224,255,20},{0,154,213,255,21},{0,145,202,255,22},{0,136,191,255,23},{0,127,180,255,24},{0,119,170,255,25},{0,112,163,255,26},{0,105,156,255,27},{0,98,149,255,28},{0,91,142,255,29},{0,85,136,255,30},{0,81,128,255,31},{0,78,120,255,32},{0,74,112,255,33},{0,71,104,255,34},{255,238,0,255,35},{255,224,0,255,36},{255,210,0,255,37},{255,197,0,255,38},{255,183,0,255,39},{255,170,0,255,40},{255,159,0,255,41},{255,149,0,255,42},{255,139,0,255,43},{255,129,0,255,44},{255,68,0,255,45},{242,54,0,255,46},{230,40,0,255,47},{217,27,0,255,48},{205,13,0,255,49},{193,0,0,255,50},{168,0,0,255,51},{143,0,0,255,52},{118,0,0,255,53},{93,0,0,255,54},{255,170,255,255,55},{255,159,255,255,56},{255,149,255,255,57},{255,139,255,255,58},{255,129,255,255,59},{255,119,255,255,60},{255,108,255,255,61},{255,98,255,255,62},{255,88,255,255,63},{255,78,255,255,64},{255,255,255,255,65},{0,255,0,255,75},{195,255,255,191,5},{194,255,255,204,6},{193,255,255,216,7},{192,255,255,229,8},{191,255,255,242,9},{191,255,255,255,10},{184,248,255,255,11},{178,242,255,255,12},{171,235,255,255,13},{165,229,255,255,14},{159,223,255,255,15},{152,216,255,255,16},{146,210,255,255,17},{139,203,255,255,18},{133,197,255,255,19},{127,191,255,255,20},{120,184,255,255,21},{114,178,255,255,22},{107,171,255,255,23},{101,165,255,255,24},{95,159,255,255,25},{91,155,255,255,26},{88,152,255,255,27},{85,149,255,255,28},{82,146,255,255,29},{79,143,255,255,30},{75,139,255,255,31},{72,136,255,255,32},{69,133,255,255,33},{66,130,255,255,34},{63,127,255,255,35},{59,123,255,255,36},{56,120,255,255,37},{53,117,255,255,38},{50,114,255,255,39},{47,111,255,255,40},{43,107,255,255,41},{40,104,255,255,42},{37,101,255,255,43},{34,98,255,255,44},{31,95,255,255,45},{27,91,255,255,46},{24,88,255,255,47},{21,85,255,255,48},{18,82,255,255,49},{15,79,255,255,50},{12,75,255,255,51},{9,72,255,255,52},{6,69,255,255,53},{2,66,255,255,54},{0,63,255,255,55},{0,59,255,255,56},{0,56,255,255,57},{0,53,255,255,58},{0,50,255,255,59},{0,47,255,255,60},{0,43,255,255,61},{0,40,255,255,62},{0,37,255,255,63},{0,34,255,255,64},{0,31,255,255,65},{0,27,255,255,66},{0,24,255,255,67},{0,21,255,255,68},{0,18,255,255,69},{0,15,255,255,70},{0,12,255,255,71},{0,9,255,255,72},{0,6,255,255,73},{0,2,255,255,74},{0,0,255,255,75}};
    static List<RainCheck.Point> fetch(double lat, double lon) throws Exception {
        JSONObject maps = new JSONObject(new String(get("https://api.rainviewer.com/public/weather-maps.json"), "UTF-8"));
        String host = maps.getString("host");
        JSONArray past = maps.getJSONObject("radar").getJSONArray("past");
        if (past.length() < 2) return null;
        JSONObject last = past.getJSONObject(past.length() - 1);
        long lastTime = last.getLong("time");
        if (lastTime * 1000 < System.currentTimeMillis() - 30 * 60_000L) return null;   // verouderd
        JSONObject early = last;
        for (int i = 0; i < past.length(); i++) {
            JSONObject f = past.getJSONObject(i);
            if (Math.abs(f.getLong("time") - (lastTime - 1800)) < Math.abs(early.getLong("time") - (lastTime - 1800))) early = f;
        }
        long dt = lastTime - early.getLong("time");
        if (dt < 900) return null;

        double n = 256 * Math.pow(2, Z);
        double wx = (lon + 180) / 360 * n;
        double wy = (1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) / Math.PI) / 2 * n;
        int tx = (int) Math.floor(wx / 256) - 1, ty = (int) Math.floor(wy / 256) - 1;
        int[] b = pixels(host, last.getString("path"), tx, ty);
        int[] a = pixels(host, early.getString("path"), tx, ty);
        double hx = (wx - tx * 256) / 2, hy = (wy - ty * 256) / 2;
        return cast(a, b, SIZE, SIZE, hx, hy, dt, lastTime, System.currentTimeMillis());
    }

    /** Rekenwerk los van het ophalen, zodat het te testen is. a = eerder beeld, b = nu; ARGB per pixel. */
    static List<RainCheck.Point> cast(int[] a, int[] b, int w, int h, double hx, double hy, long dt, long lastTime, long now) {
        double[] v = motion(a, b, w, h, hx, hy);
        double vx = v[0] * 1800.0 / dt, vy = v[1] * 1800.0 / dt;   // pixels per 30 minuten
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        long t0 = now / 300_000L * 300_000L;
        List<RainCheck.Point> out = new ArrayList<>();
        for (int k = 0; k <= 24; k++) {
            long s = t0 + k * 300_000L;
            double mins = (s / 1000.0 - lastTime) / 60;
            out.add(new RainCheck.Point(s, mmAt(b, w, h, hx - vx * mins / 30, hy - vy * mins / 30), hm.format(new Date(s))));
        }
        return out;
    }

    /** Verschuiving (pixels) van beeld a naar b rond je plek; {0, 0} als er te weinig buien zijn. */
    static double[] motion(int[] a, int[] b, int w, int h, double hx, double hy) {
        int x0 = Math.max(M, (int) Math.round(hx - R)), x1 = Math.min(w - M, (int) Math.round(hx + R));
        int y0 = Math.max(M, (int) Math.round(hy - R)), y1 = Math.min(h - M, (int) Math.round(hy + R));
        float[] fa = alpha(a), fb = alpha(b);
        double mass = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) mass += fa[y * w + x];
        if (mass < 3) return new double[]{0, 0};
        int bdx = 0, bdy = 0; double best = -1;
        double[][] sc = new double[2 * M + 1][2 * M + 1];
        for (int dy = -M; dy <= M; dy++) for (int dx = -M; dx <= M; dx++) {
            double s = 0;
            for (int y = y0; y < y1; y++) {
                int ra = y * w, rb = (y + dy) * w + dx;
                for (int x = x0; x < x1; x++) s += fa[ra + x] * fb[rb + x];
            }
            sc[dy + M][dx + M] = s;
            if (s > best) { best = s; bdx = dx; bdy = dy; }
        }
        if (best <= 0) return new double[]{0, 0};
        double fx = 0, fy = 0;
        if (Math.abs(bdx) < M) fx = sub(sc[bdy + M][bdx + M - 1], best, sc[bdy + M][bdx + M + 1]);
        if (Math.abs(bdy) < M) fy = sub(sc[bdy + M - 1][bdx + M], best, sc[bdy + M + 1][bdx + M]);
        return new double[]{bdx + fx, bdy + fy};
    }

    private static double sub(double m, double c, double p) {
        double d = m - 2 * c + p;
        return d < 0 ? Math.max(-.5, Math.min(.5, (m - p) / (2 * d))) : 0;
    }

    private static float[] alpha(int[] px) {
        float[] f = new float[px.length];
        for (int i = 0; i < px.length; i++) f[i] = (px[i] >>> 24) / 255f;
        return f;
    }

    /** Gemiddelde regen (mm/u) in een vakje van 3×3 pixels. */
    static double mmAt(int[] px, int w, int h, double x, double y) {
        double sum = 0; int n = 0;
        int cx = (int) Math.round(x), cy = (int) Math.round(y);
        for (int yy = cy - 1; yy <= cy + 1; yy++) for (int xx = cx - 1; xx <= cx + 1; xx++) {
            if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
            sum += pixelMm(px[yy * w + xx]); n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Kleur → dBZ (dichtstbijzijnde kleur) → mm/u (Marshall-Palmer: Z = 200·R^1,6). */
    static double pixelMm(int c) {
        int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
        if (a < 60) return 0;
        long best = Long.MAX_VALUE; int dbz = 0;
        for (int[] k : COLORS) {
            long e = (long) (k[0] - r) * (k[0] - r) + (long) (k[1] - g) * (k[1] - g) + (long) (k[2] - bl) * (k[2] - bl) + (long) (k[3] - a) * (k[3] - a);
            if (e < best) { best = e; dbz = k[4]; }
        }
        return Math.pow(Math.pow(10, dbz / 10.0) / 200, 1 / 1.6);
    }

    private static int[] pixels(String host, String path, int tx, int ty) throws Exception {
        Bitmap big = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(big);
        for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
            byte[] png = get(host + path + "/256/" + Z + "/" + (tx + i) + "/" + (ty + j) + "/4/1_1.png");
            Bitmap t = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (t != null) { cv.drawBitmap(t, i * 256, j * 256, null); t.recycle(); }
        }
        Bitmap small = Bitmap.createScaledBitmap(big, SIZE, SIZE, true);
        int[] px = new int[SIZE * SIZE];
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        big.recycle(); small.recycle();
        return px;
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(15_000);
        try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            con.disconnect();
        }
    }
}
