package dev.gtv.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/** Plays the adhan on the media stream - the one the remote's volume buttons
 *  control - at whatever volume the viewer has set, holding focus so other
 *  players pause, pausing their media sessions, and stopping itself when the
 *  recording ends. The volume is never touched.
 *
 *  The sound lives here rather than in the activity on purpose: it has to
 *  start from the broadcast receiver and keep playing even if the adhan
 *  screen never makes it to the front. */
final class AdhanPlayer {

    private static final long LOCK_EXTRA_MS = 60_000L;
    private static final long SAFETY_GRACE_MS = 30_000L;

    private static MediaPlayer player;
    private static AudioManager audio;
    private static AudioFocusRequest focus;
    private static PowerManager.WakeLock wake;
    private static Handler handler;
    private static Runnable autoStop;
    private static Runnable stoppedListener;
    /** Application context held on purpose: the sound has to outlive any UI. */
    @SuppressLint("StaticFieldLeak")
    private static Context app;
    private static long endAt;
    private static boolean playing;

    private AdhanPlayer() {
    }

    /** Starts the adhan and plays it through once. A second call while it
     *  plays is ignored. */
    static synchronized void start(Context ctx) {
        if (playing) return;
        app = ctx.getApplicationContext();
        handler = new Handler(Looper.getMainLooper());
        playing = true;
        endAt = 0;

        requestFocus();

        Uri uri = adhanUri(app);
        if (uri != null) {
            try {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                player.setDataSource(app, uri);
                player.setLooping(false);
                player.setOnPreparedListener(mp -> {
                    if (!playing) return;       // stopped while preparing
                    long duration = Math.max(0, mp.getDuration());
                    if (duration > 0) {
                        // The screen counts down to the end of the recording
                        // and everything closes when the last second plays.
                        endAt = System.currentTimeMillis() + duration;
                        scheduleSafetyStop(duration + SAFETY_GRACE_MS);
                    } else {
                        scheduleSafetyStop(safetyCap());
                    }
                    mp.start();
                });
                // Finished on its own: close the screen, notification and all.
                player.setOnCompletionListener(mp -> stop());
                player.setOnErrorListener((mp, what, extra) -> {
                    releasePlayer();
                    stop();
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                releasePlayer();
                scheduleSafetyStop(safetyCap());
            }
        } else {
            scheduleSafetyStop(safetyCap());
        }

        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gtv:adhan");
            wake.setReferenceCounted(false);
            try {
                wake.acquire(safetyCap() + LOCK_EXTRA_MS);
            } catch (Exception ignored) {
            }
        }

        // Sessions take a moment to show up; try a few times.
        MediaPauseListener.pauseAll(app);
        if (handler != null) {
            handler.postDelayed(() -> MediaPauseListener.pauseAll(app), 1_500L);
            handler.postDelayed(() -> MediaPauseListener.pauseAll(app), 5_000L);
        }

        Log.i(PrayerAlerts.TAG, "adhan sound started ("
                + (usingSystemTone(app) ? "device tone" : "res/raw/adhan") + ")");
    }

    /** Stops the sound and takes the adhan notification down. */
    static synchronized void stop() {
        if (!playing) return;
        playing = false;

        if (handler != null && autoStop != null) handler.removeCallbacks(autoStop);
        autoStop = null;
        endAt = 0;

        releasePlayer();

        if (audio != null && focus != null) {
            try {
                audio.abandonAudioFocusRequest(focus);
            } catch (Exception ignored) {
            }
        }
        audio = null;
        focus = null;

        if (wake != null && wake.isHeld()) {
            try {
                wake.release();
            } catch (Exception ignored) {
            }
        }
        wake = null;

        if (app != null) PrayerAlerts.cancelAdhan(app);

        Runnable listener = stoppedListener;
        stoppedListener = null;
        Log.i(PrayerAlerts.TAG, "adhan sound stopped");
        if (listener != null && handler != null) handler.post(listener);
    }

    static synchronized boolean isPlaying() {
        return playing;
    }

    /** When the recording ends; zero until the player knows its length. */
    static long endAt() {
        return endAt;
    }

    /** Called by the adhan screen so the end of the recording closes it too. */
    static synchronized void setStoppedListener(Runnable r) {
        stoppedListener = r;
    }

    // ------------------------------------------------------------ internals

    /** A guard for a broken or endless file: the configured cap, never the
     *  normal exit (completion handles that). */
    private static long safetyCap() {
        return app.getResources().getInteger(R.integer.adhan_max_minutes) * 60_000L;
    }

    private static void scheduleSafetyStop(long delayMs) {
        if (handler == null) return;
        if (autoStop != null) handler.removeCallbacks(autoStop);
        autoStop = AdhanPlayer::stop;
        handler.postDelayed(autoStop, Math.max(5_000L, delayMs));
    }

    private static void requestFocus() {
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        try {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .build();
            audio.requestAudioFocus(focus);
        } catch (Exception ignored) {
            focus = null;
        }
    }

    private static void releasePlayer() {
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    /** res/raw/adhan when present, else the device's own alarm tone. */
    private static Uri adhanUri(Context ctx) {
        int id = ctx.getResources().getIdentifier("adhan", "raw", ctx.getPackageName());
        if (id != 0) {
            return Uri.parse("android.resource://" + ctx.getPackageName() + "/" + id);
        }
        try {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the shipped recording is missing and the alarm tone is used. */
    static boolean usingSystemTone(Context ctx) {
        return ctx.getResources().getIdentifier("adhan", "raw", ctx.getPackageName()) == 0;
    }
}
