package dev.gtv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** An installed, launchable app. */
public class AppItem {
    public final String pkg;
    public final String label;
    public final Drawable icon;
    public final Drawable banner;

    public AppItem(String pkg, String label, Drawable icon, Drawable banner) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
        this.banner = banner;
    }

    /** All apps that advertise themselves to a TV launcher (LEANBACK_LAUNCHER). */
    public static List<AppItem> loadAll(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> seen = new LinkedHashSet<>();
        List<AppItem> out = new ArrayList<>();

        Intent leanback = new Intent(Intent.ACTION_MAIN);
        leanback.addCategory("android.intent.category.LEANBACK_LAUNCHER");
        collect(ctx, pm, leanback, seen, out);

        // Some local TV apps only register the phone category; include them so
        // they are not silently missing from the grid.
        Intent phone = new Intent(Intent.ACTION_MAIN);
        phone.addCategory(Intent.CATEGORY_LAUNCHER);
        collect(ctx, pm, phone, seen, out);

        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private static void collect(Context ctx, PackageManager pm, Intent intent,
                                Set<String> seen, List<AppItem> out) {
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) {
                try {
                    banner = pm.getApplicationBanner(pkg);
                } catch (Exception ignored) {
                }
            }
            out.add(new AppItem(pkg,
                    String.valueOf(ri.loadLabel(pm)),
                    ri.loadIcon(pm),
                    banner));
        }
    }

    public Intent launchIntent(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** Average colour of the icon, ignoring near-black and near-white pixels. */
    public static int ambientColor(Drawable d) {
        try {
            int w = 48, h = 48;
            Bitmap bmp;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                bmp = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), w, h, true);
            } else {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, w, h);
                d.draw(c);
            }
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = bmp.getPixel(x, y);
                    int a = (p >>> 24) & 0xFF;
                    int rr = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, bb = p & 0xFF;
                    int lum = (rr * 299 + gg * 587 + bb * 114) / 1000;
                    if (a < 200 || lum < 24 || lum > 232) continue;
                    r += rr; g += gg; b += bb; n++;
                }
            }
            if (n == 0) return 0xFF4A4A4A;
            return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        } catch (Throwable t) {
            return 0xFF4A4A4A;
        }
    }

    public ComponentName component(Context ctx) {
        Intent i = launchIntent(ctx);
        return i == null ? null : i.getComponent();
    }
}
