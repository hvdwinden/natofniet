package nl.natofnietnat;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Startschermwidget: "Droog." of "Nat." met een korte toelichting en de komende 2 uur in staafjes.
 * Wordt bijgewerkt door de periodieke controle (RainJobService), bij het openen van de app en elk half uur door Android.
 */
public class RainWidget extends AppWidgetProvider {

    private static final double WET = 0.1;

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        PendingResult pr = goAsync();
        new Thread(() -> {
            try { updateAll(c.getApplicationContext()); }
            finally { pr.finish(); }
        }).start();
    }

    @Override
    public void onEnabled(Context c) { RainCheck.schedule(c); }

    @Override
    public void onDisabled(Context c) { RainCheck.schedule(c); }

    static boolean hasWidgets(Context c) {
        return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, RainWidget.class)).length > 0;
    }

    /** Haalt de verwachting op en werkt alle widgets bij. Niet op de UI-thread aanroepen. */
    static void updateAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(c, RainWidget.class));
        if (ids.length == 0) return;
        SharedPreferences p = c.getSharedPreferences(RainCheck.PREFS, 0);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_rain);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 10, open, PendingIntent.FLAG_IMMUTABLE));

        if (!p.contains("lat")) {
            v.setTextViewText(R.id.w_title, title(c, "Nat of niet?"));
            v.setTextViewText(R.id.w_sub, "Open de app om je locatie te bepalen");
            v.setTextViewText(R.id.w_foot, "");
            mgr.updateAppWidget(ids, v);
            return;
        }
        String place = p.getString("name", "je locatie");
        List<RainCheck.Point> f = null;
        try { f = RainCheck.fetch(p.getFloat("lat", 0), p.getFloat("lon", 0)); } catch (Exception ignored) { }

        if (f == null || f.isEmpty()) {
            // Geen verbinding: laat het vorige antwoord staan, alleen de voettekst aanpassen
            v.setTextViewText(R.id.w_foot, place + " · geen verbinding");
            mgr.partiallyUpdateAppWidget(ids, v);
            return;
        }
        String[] t = texts(f);
        v.setTextViewText(R.id.w_title, title(c, t[0]));
        v.setTextViewText(R.id.w_sub, t[1]);
        v.setTextViewText(R.id.w_foot, place + " · bijgewerkt " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date()));
        v.setImageViewBitmap(R.id.w_chart, chart(c, f));
        mgr.updateAppWidget(ids, v);
    }

    /** Kop en toelichting, zoals bovenaan in de app. */
    static String[] texts(List<RainCheck.Point> f) {
        int firstWet = -1, firstDry = -1;
        for (int i = 0; i < f.size(); i++) {
            boolean wet = f.get(i).mm >= WET;
            if (wet && firstWet == -1) firstWet = i;
            if (!wet && firstDry == -1) firstDry = i;
        }
        boolean wetNow = f.get(0).mm >= WET;
        if (firstWet == -1) return new String[]{"Droog", "Geen regen tot " + f.get(f.size() - 1).hhmm};
        if (wetNow) return new String[]{"Nat", firstDry == -1 ? "Voorlopig niet droog" : "Droog om " + f.get(firstDry).hhmm};
        long min = Math.max(0, Math.round((f.get(firstWet).time - f.get(0).time) / 60_000.0));
        return new String[]{"Droog", "Regen om " + f.get(firstWet).hhmm + " (over " + min + " min)"};
    }

    /** Kop met gekleurde punt: "Droog." */
    private static CharSequence title(Context c, String word) {
        if (word.length() > 6) return word;
        SpannableString s = new SpannableString(word + ".");
        s.setSpan(new ForegroundColorSpan(c.getColor(R.color.w_dot)), word.length(), word.length() + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** Staafje per 5 minuten, logaritmische hoogte zoals in de app. */
    private static Bitmap chart(Context c, List<RainCheck.Point> f) {
        int n = Math.min(f.size(), 25), W = 600, H = 110;
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float slot = W / (float) n, gap = slot * .22f, r = Math.min(6, (slot - gap) / 2);
        for (int i = 0; i < n; i++) {
            double mm = f.get(i).mm;
            float h;
            if (mm < WET) { h = 7; paint.setColor(c.getColor(R.color.w_stub)); }
            else {
                double frac = Math.min(1, Math.log(mm / 0.05) / Math.log(60 / 0.05));
                h = (float) Math.max(14, frac * H);
                paint.setColor(c.getColor(mm < 0.8 ? R.color.w_r1 : mm < 2.5 ? R.color.w_r2 : mm < 10 ? R.color.w_r3 : R.color.w_r4));
            }
            float x = i * slot + gap / 2;
            cv.drawRoundRect(new RectF(x, H - h, x + slot - gap, H), r, r, paint);
        }
        return b;
    }
}
