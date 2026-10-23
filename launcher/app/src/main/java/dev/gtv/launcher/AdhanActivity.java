package dev.gtv.launcher;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/** The adhan screen: one prayer in very large type, a countdown to the
 *  automatic stop, and a single Stop button. Nothing else can take focus,
 *  OK stops the sound and Back does the same. */
public class AdhanActivity extends Activity {

    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_TIME = "time";
    public static final String EXTRA_LOCATION = "location";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView remaining;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long left = AdhanPlayer.endAt() - System.currentTimeMillis();
            if (left <= 0) {
                finish();                    // the player already stopped
                return;
            }
            if (remaining != null) {
                remaining.setText(getString(R.string.adhan_stops_in, clock(left)));
            }
            handler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);

        setContentView(R.layout.activity_adhan);
        hideSystemUi();

        // Normally the receiver started the sound a moment before this screen
        // appeared; if the process was restarted by the full-screen intent,
        // start it here instead so the screen is never silent.
        if (!AdhanPlayer.isPlaying()) {
            long stopAfter = getResources()
                    .getInteger(R.integer.adhan_auto_stop_minutes) * 60_000L;
            AdhanPlayer.start(this, stopAfter);
        }

        String name = str(getIntent().getStringExtra(EXTRA_NAME));
        String time = str(getIntent().getStringExtra(EXTRA_TIME));
        String location = str(getIntent().getStringExtra(EXTRA_LOCATION));

        TextView title = findViewById(R.id.adhan_title);
        title.setText(getString(R.string.adhan_title, name));
        TextView clock = findViewById(R.id.adhan_time);
        clock.setText(time);
        TextView place = findViewById(R.id.adhan_location);
        place.setText(location);
        place.setVisibility(location.isEmpty() ? View.GONE : View.VISIBLE);

        remaining = findViewById(R.id.adhan_remaining);

        View stop = findViewById(R.id.adhan_stop);
        stop.setOnClickListener(v -> finish());
        stop.requestFocus();

        if (AdhanPlayer.usingSystemTone(this)) {
            TextView hint = findViewById(R.id.adhan_hint);
            hint.setVisibility(View.VISIBLE);
        }

        // The screen must close itself when the sound runs out on its own.
        AdhanPlayer.setStoppedListener(this::finish);
        Log.i(PrayerAlerts.TAG, "adhan screen shown for " + name);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        if (isFinishing()) AdhanPlayer.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        AdhanPlayer.setStoppedListener(null);
        if (isFinishing()) AdhanPlayer.stop();
    }

    @Override
    public void onBackPressed() {
        finish();                            // Back stops the adhan too
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** "09:42" from a remaining millisecond count. */
    static String clock(long millis) {
        long total = Math.max(0, millis) / 1000L;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
