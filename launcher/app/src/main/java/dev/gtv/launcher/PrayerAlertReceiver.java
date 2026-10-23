package dev.gtv.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Fired by {@link PrayerScheduler} at prayer time minus the lead minutes and
 *  again at prayer time.
 *
 *  The reminder posts a notification and, when Home is in front, the banner.
 *  The adhan starts the sound first (it plays even if the screen cannot be
 *  taken over), then asks the system for the full-screen adhan screen, which
 *  is also what lifts it over a playing video. */
public class PrayerAlertReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!PrayerAlerts.ACTION_ALERT.equals(intent.getAction())) return;

        String kind = intent.getStringExtra(PrayerAlerts.EXTRA_KIND);
        String name = str(intent.getStringExtra(PrayerAlerts.EXTRA_NAME));
        String time = str(intent.getStringExtra(PrayerAlerts.EXTRA_TIME));
        String location = str(intent.getStringExtra(PrayerAlerts.EXTRA_LOCATION));

        if (PrayerAlerts.KIND_ADHAN.equals(kind)) {
            startAdhan(ctx, name, time, location);
        } else {
            remind(ctx, name, time, location);
        }
    }

    /** "Subuh in 5 minutes" on Home and in the notification tray. */
    private void remind(Context ctx, String name, String time, String location) {
        int lead = ctx.getResources().getInteger(R.integer.prayer_alert_lead_minutes);
        String title = ctx.getString(R.string.alert_prepare_title, name, lead);
        String text = join(time, location);
        Log.i(PrayerAlerts.TAG, "reminder for " + name + " (" + lead + " min) " + text);

        PrayerAlerts.postReminder(ctx, title, text, openLauncher(ctx));
        PrayerAlerts.dispatchBanner(title, text);
    }

    private void startAdhan(Context ctx, String name, String time, String location) {
        long stopAfter = ctx.getResources()
                .getInteger(R.integer.adhan_auto_stop_minutes) * 60_000L;

        Log.i(PrayerAlerts.TAG, "adhan for " + name + " " + time
                + " (auto stop in " + (stopAfter / 60_000L) + " min)");

        // The time is here: drop the "in 5 minutes" reminder if it is up.
        PrayerAlerts.cancelReminder(ctx);

        // Sound first: it survives even when the screen stays on another app.
        AdhanPlayer.start(ctx, stopAfter);

        Intent screen = new Intent(ctx, AdhanActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        screen.putExtra(AdhanActivity.EXTRA_NAME, name);
        screen.putExtra(AdhanActivity.EXTRA_TIME, time);
        screen.putExtra(AdhanActivity.EXTRA_LOCATION, location);

        PrayerAlerts.postAdhan(ctx, name, join(time, location),
                PendingIntent.getActivity(ctx, 1, screen,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE),
                PendingIntent.getActivity(ctx, 2, screen,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            ctx.startActivity(screen);        // allowed while Home is in front
        } catch (Exception ignored) {
            // Background: the full-screen intent above makes the system start it.
        }

        PrayerScheduler.armAfterAlert(ctx);   // line up the next prayer
    }

    private static PendingIntent openLauncher(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).setAction(Intent.ACTION_MAIN);
        return PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String join(String time, String location) {
        if (time.isEmpty()) return location;
        if (location.isEmpty()) return time;
        return time + "  ·  " + location;
    }
}
