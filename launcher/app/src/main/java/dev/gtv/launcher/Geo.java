package dev.gtv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Where the TV is, and what that place is called.
 *
 *  Prayer times are calculated from the device's coordinates (KEMENAG via
 *  api.aladhan.com), so the launcher needs a fix, and the place shown with
 *  the schedule is reverse geocoded from the same coordinates
 *  (OpenStreetMap Nominatim, cached until the TV moves a few kilometres).
 *
 *  Everything here blocks, so it only ever runs on the io executor. */
final class Geo {

    private static final String KEY_PLACE = "place_cache";
    /** Who is calling: Nominatim's policy wants an identifying User-Agent. */
    private static final String USER_AGENT = "tv-launcher/1.0 (github.com/gifary/tv-laucher)";
    /** How far the TV may move before the place name is looked up again. */
    private static final double PLACE_SLACK = 0.05;      // ~5 km

    private Geo() {
    }

    /** The place, or null when the TV has no location at all: no permission,
     *  no enabled provider, or no fix within timeoutMs. */
    static Place locate(Context ctx, long timeoutMs) {
        Location fix = bestKnownFix(ctx);
        if (fix == null) fix = waitFirstFix(ctx, timeoutMs);
        if (fix == null) return null;
        return new Place(fix.getLatitude(), fix.getLongitude(), placeName(ctx, fix));
    }

    /** A readable name for the coordinates, e.g. "Tasikmalaya, Jawa Barat". */
    static final class Place {
        final double lat;
        final double lng;
        final String label;

        Place(double lat, double lng, String label) {
            this.lat = lat;
            this.lng = lng;
            this.label = label == null ? "" : label;
        }

        String latText() {
            return String.format(Locale.US, "%.6f", lat);
        }

        String lngText() {
            return String.format(Locale.US, "%.6f", lng);
        }
    }

    // ------------------------------------------------------------ internals

    /** Newest of whatever the providers remember; usually enough on a TV. */
    private static Location bestKnownFix(Context ctx) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        try {
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best;
        } catch (SecurityException e) {
            return null;                       // permission not granted
        } catch (Exception e) {
            return null;
        }
    }

    /** Asks every provider for one fresh fix and waits a little for it. */
    @SuppressWarnings("deprecation")           // requestSingleUpdate: no-arg callbacks era
    private static Location waitFirstFix(Context ctx, long timeoutMs) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;

        final AtomicReference<Location> got = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                got.set(location);
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        try {
            for (String provider : lm.getProviders(true)) {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (SecurityException e) {
            return null;
        } catch (Exception ignored) {
        } finally {
            try {
                lm.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }
        return got.get();
    }

    /** The place name, cached so a quiet day costs no network calls. */
    private static String placeName(Context ctx, Location fix) {
        SharedPreferences prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        try {
            JSONObject cached = new JSONObject(prefs.getString(KEY_PLACE, ""));
            String label = cached.optString("label", "");
            if (!label.isEmpty()
                    && Math.abs(cached.optDouble("lat", 99) - fix.getLatitude()) < PLACE_SLACK
                    && Math.abs(cached.optDouble("lng", 99) - fix.getLongitude()) < PLACE_SLACK) {
                return label;
            }
        } catch (Exception ignored) {
        }

        String label = reverseGeocode(ctx, fix.getLatitude(), fix.getLongitude());
        if (!label.isEmpty()) {
            try {
                prefs.edit().putString(KEY_PLACE, new JSONObject()
                        .put("lat", fix.getLatitude())
                        .put("lng", fix.getLongitude())
                        .put("label", label).toString()).apply();
            } catch (Exception ignored) {
            }
        }
        return label;
    }

    /** "Kabupaten Tasikmalaya, Jawa Barat" -> "Tasikmalaya, Jawa Barat". */
    private static String reverseGeocode(Context ctx, double lat, double lng) {
        try {
            String spec = String.format(Locale.US,
                    ctx.getString(R.string.reverse_geocode_api)
                            + "?format=jsonv2&lat=%.6f&lon=%.6f&zoom=10",
                    lat, lng);
            JSONObject geo = new JSONObject(Net.get(spec, USER_AGENT, "id,en"));
            JSONObject address = geo.optJSONObject("address");
            String city = first(address, "city", "town", "municipality", "county", "state");
            String state = address == null ? "" : address.optString("state", "");
            String label = city;
            if (!state.isEmpty() && !state.equalsIgnoreCase(city)) {
                label = city.isEmpty() ? state : city + ", " + state;
            }
            if (label.isEmpty()) label = geo.optString("display_name", "");
            return label;
        } catch (Exception e) {
            return "";                          // offline: the config name shows instead
        }
    }

    private static String first(JSONObject source, String... keys) {
        if (source == null) return "";
        for (String key : keys) {
            String value = source.optString(key, "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }
}
