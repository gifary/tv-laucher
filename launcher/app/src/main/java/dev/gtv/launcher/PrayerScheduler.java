package dev.gtv.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.util.Calendar;

/** Arms the two alarms for the next prayer: a reminder a few minutes early and
 *  then the adhan itself. Everything is read from the cached schedule, so this
 *  needs no network and keeps working with the launcher process dead.
 *
 *  The alarms are re-armed after each alert, after the daily prayer refresh,
 *  and after a reboot ({@link BootReceiver}). */
final class PrayerScheduler {

    private static final int REQ_REMINDER = 501;
    private static final int REQ_ADHAN = 502;

    /** Mirrors the named prayers in MainActivity. */
    private static final String[] KEYS = {"subuh", "dzuhur", "ashar", "maghrib", "isya"};
    private static final String[] NAMES = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};

    private PrayerScheduler() {
    }

    /** Re-registers both alarms for the next prayer. Safe to call any time. */
    static void arm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        if (!ctx.getResources().getBoolean(R.bool.prayer_alerts_enabled)) {
            cancelAll(ctx);
            Log.d(PrayerAlerts.TAG, "alerts switched off in config");
            return;
        }

        long now = System.currentTimeMillis();
        long lead = ctx.getResources().getInteger(R.integer.prayer_alert_lead_minutes) * 60_000L;
        long testDelay = ctx.getResources().getInteger(R.integer.prayer_alert_test_delay_seconds);

        String name;
        String time;
        String location;
        long adhanAt;
        long reminderAt;

        if (testDelay > 0) {
            // Test pair: remind now +N seconds, adhan ten seconds later.
            name = NAMES[0];
            time = "test";
            location = label(ctx, null);
            reminderAt = now + testDelay * 1000L;
            adhanAt = reminderAt + 10_000L;
        } else {
            JSONObject cache = readCache(ctx);
            JSONObject times = cache == null ? null : cache.optJSONObject("times");
            if (times == null) {
                cancelAll(ctx);       // no schedule yet; loadPrayer() re-arms later
                Log.d(PrayerAlerts.TAG, "no cached schedule yet");
                return;
            }
            long best = Long.MAX_VALUE;
            int bestIndex = -1;
            // Today's remaining prayers, then tomorrow's (times repeat daily).
            for (int day = 0; day <= 1; day++) {
                for (int i = 0; i < KEYS.length; i++) {
                    long at = at(times.optString(KEYS[i], ""), day, now);
                    if (at > now + 5_000L && at < best) {
                        best = at;
                        bestIndex = i;
                    }
                }
            }
            if (bestIndex < 0) {
                cancelAll(ctx);
                Log.d(PrayerAlerts.TAG, "no prayer left today");
                return;
            }
            name = NAMES[bestIndex];
            time = times.optString(KEYS[bestIndex], "");
            location = label(ctx, cache);
            adhanAt = best;
            // Inside the last minutes the warning would be wrong, so skip it.
            reminderAt = adhanAt - lead > now + 1_000L ? adhanAt - lead : 0L;
        }

        if (reminderAt > 0) {
            set(ctx, am, REQ_REMINDER, reminderAt, PrayerAlerts.KIND_REMINDER, name, time, location);
        } else {
            cancel(ctx, REQ_REMINDER);
        }
        set(ctx, am, REQ_ADHAN, adhanAt, PrayerAlerts.KIND_ADHAN, name, time, location);
    }

    /** Re-arms after an alert. A test pair is deliberately one-shot, so a
     *  test does not start looping every few seconds. */
    static void armAfterAlert(Context ctx) {
        if (ctx.getResources().getInteger(R.integer.prayer_alert_test_delay_seconds) > 0) {
            return;
        }
        arm(ctx);
    }

    /** Drops both alarms, e.g. when alerts are switched off in config.xml. */
    static void cancelAll(Context ctx) {
        cancel(ctx, REQ_REMINDER);
        cancel(ctx, REQ_ADHAN);
    }

    // ------------------------------------------------------------ internals

    private static void set(Context ctx, AlarmManager am, int request, long at,
                            String kind, String name, String time, String location) {
        Intent i = new Intent(ctx, PrayerAlertReceiver.class).setAction(PrayerAlerts.ACTION_ALERT);
        i.putExtra(PrayerAlerts.EXTRA_KIND, kind);
        i.putExtra(PrayerAlerts.EXTRA_NAME, name);
        i.putExtra(PrayerAlerts.EXTRA_TIME, time);
        i.putExtra(PrayerAlerts.EXTRA_LOCATION, location);

        PendingIntent pi = PendingIntent.getBroadcast(ctx, request, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long in = Math.max(0, at - System.currentTimeMillis()) / 1000L;
        Log.d(PrayerAlerts.TAG, kind + " " + name + " in " + in + "s");
        try {
            // Android 12+ may have exact alarms switched off for this app.
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception ignored) {
        }
    }

    private static void cancel(Context ctx, int request) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, PrayerAlertReceiver.class).setAction(PrayerAlerts.ACTION_ALERT);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, request, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.cancel(pi);
            pi.cancel();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject readCache(Context ctx) {
        try {
            String raw = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                    .getString("prayer_cache", null);
            return raw == null ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** The place shown as the schedule's location, e.g. "Tasikmalaya, Jawa
     *  Barat". Same rule as MainActivity.locationLabel(): the name reverse
     *  geocoded from the TV's coordinates first, then config, then the API. */
    private static String label(Context ctx, JSONObject source) {
        String place = source == null ? "" : source.optString("place", "");
        if (!place.isEmpty()) return place;
        String custom = ctx.getString(R.string.prayer_location_label);
        if (custom != null && !custom.isEmpty()) return custom;
        String kabko = source == null ? "" : source.optString("kabko", "");
        String prov = source == null ? "" : source.optString("prov", "");
        if (kabko.isEmpty()) return prov;
        if (prov.isEmpty() || kabko.equalsIgnoreCase(prov)) return kabko;
        return kabko + " · " + prov;
    }

    /** Milliseconds for "HH:mm" today (day 0) or tomorrow (day 1), else 0. */
    private static long at(String hhmm, int day, long now) {
        if (hhmm == null || hhmm.length() < 5) return 0;
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2).trim());
            int m = Integer.parseInt(hhmm.substring(3, 5).trim());
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            c.add(Calendar.DAY_OF_YEAR, day);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}
