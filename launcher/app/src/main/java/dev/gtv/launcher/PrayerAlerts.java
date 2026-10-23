package dev.gtv.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;

import android.content.Context;

/** Prayer alert plumbing: the broadcast action, the notification channel, and
 *  the hook the launcher uses to show the five-minute banner on Home. */
final class PrayerAlerts {

    static final String ACTION_ALERT = "dev.gtv.launcher.PRAYER_ALERT";
    static final String EXTRA_KIND = "kind";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_TIME = "time";
    static final String EXTRA_LOCATION = "location";

    static final String KIND_REMINDER = "reminder";
    static final String KIND_ADHAN = "adhan";

    static final String CHANNEL_ID = "prayer_alerts";
    /** One logcat tag for everything prayer-alert related. */
    static final String TAG = "PrayerAlert";
    private static final int ID_REMINDER = 41;
    private static final int ID_ADHAN = 42;

    /** Implemented by the launcher so the banner can appear on Home. */
    interface Banner {
        void onPrayerAlert(String title, String text);
    }

    private static Banner banner;

    private PrayerAlerts() {
    }

    static synchronized void setBanner(Banner b) {
        banner = b;
    }

    /** Called on the main thread; does nothing while Home is not showing. */
    static synchronized void dispatchBanner(String title, String text) {
        Banner b = banner;
        if (b != null) b.onPrayerAlert(title, text);
    }

    /** The five-minute warning: a normal notification, dismissible. */
    static void postReminder(Context ctx, String title, String text, PendingIntent content) {
        NotificationManager nm = manager(ctx);
        if (nm == null) return;
        channel(ctx, nm);
        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_banner)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        notifySafe(nm, ID_REMINDER, n);
    }

    /** The adhan itself: ongoing, and a full-screen intent so the system
     *  launches the adhan screen even while another app is in front. */
    static void postAdhan(Context ctx, String title, String text,
                          PendingIntent content, PendingIntent fullScreen) {
        NotificationManager nm = manager(ctx);
        if (nm == null) return;
        channel(ctx, nm);
        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_banner)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(content)
                .setFullScreenIntent(fullScreen, true)
                .build();
        notifySafe(nm, ID_ADHAN, n);
    }

    /** Called when the prayer time arrives: the reminder has done its job. */
    static void cancelReminder(Context ctx) {
        NotificationManager nm = manager(ctx);
        if (nm != null) {
            try {
                nm.cancel(ID_REMINDER);
            } catch (Exception ignored) {
            }
        }
    }

    static void cancelAdhan(Context ctx) {
        NotificationManager nm = manager(ctx);
        if (nm != null) {
            try {
                nm.cancel(ID_ADHAN);
            } catch (Exception ignored) {
            }
        }
    }

    private static NotificationManager manager(Context ctx) {
        try {
            return ctx.getSystemService(NotificationManager.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static void channel(Context ctx, NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                ctx.getString(R.string.prayer_alert_channel), NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 350, 220, 350});
        try {
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {
        }
    }

    private static void notifySafe(NotificationManager nm, int id, Notification n) {
        try {
            nm.notify(id, n);
        } catch (Exception ignored) {
        }
    }
}
